package com.privatevault.app

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// T2: the lock controller on the app vault directory: setup, unlock, wrong
// PIN counting and delays, attempt recorded before the stretch, PIN change,
// migration from a provider 2 vault with interruptions at every rewrap
// step, Keystore key missing, lock during a slowed capture, capture refused
// while locked, erase from both states, and an interrupted erase. Test PINs
// are throwaway values. The app vault on the test device is replaced.
@RunWith(AndroidJUnit4::class)
class LockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val lock = app.lock
    private val pin = "246810".toByteArray(Charsets.US_ASCII)
    private val wrong = "135791".toByteArray(Charsets.US_ASCII)
    private val other = "99887766".toByteArray(Charsets.US_ASCII)

    // INTERACTIVE parameters for the vaults these tests make, so the suite
    // stays short; defaultsAreRecorded checks the real defaults once.
    private val testKdf = 2L to (64L shl 20)

    @Before
    fun setUp() {
        lock.testHooks.failAt = null
        lock.testHooks.beforeStretch = null
        lock.testHooks.kdfOverride = testKdf
        NativeBridge.nativeSetTestHooks(0, 0)
        NativeBridge.nativeSetPinStretchFailure(false)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        assertEquals(LockState.NO_VAULT, lock.state)
    }

    @After
    fun tearDown() {
        lock.testHooks.failAt = null
        lock.testHooks.beforeStretch = null
        lock.testHooks.kdfOverride = null
        NativeBridge.nativeSetTestHooks(0, 0)
        NativeBridge.nativeSetPinStretchFailure(false)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
    }

    private fun objectsDir() = File(lock.dir, "objects")
    private fun partials() = objectsDir().listFiles()?.filter { it.name.endsWith(".partial") } ?: emptyList()

    @Test
    fun setupUnlockWrongPinAndDelays() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        assertEquals(LockState.UNLOCKED, lock.state)
        assertEquals(LockController.VaultKind.PROVIDER3, lock.vaultKind())
        val alias = lock.currentAlias!!
        assertTrue(lock.hmac.exists(alias))
        val id = app.vaultSession.vault!!.encryptSample(TestKeys.SEED, 30_000L)
        lock.lock("test")
        assertEquals(LockState.LOCKED, lock.state)
        assertNull(app.vaultSession.vault)

        // Four wrong PINs are free, the fifth starts a 30 s delay.
        for (i in 1..4) {
            val r = lock.unlock(wrong)
            assertTrue("attempt $i", r is LockController.Result.WrongPin)
            assertEquals(0L, (r as LockController.Result.WrongPin).remainingMs)
            assertEquals(i, lock.attempts.failures)
        }
        val fifth = lock.unlock(wrong)
        assertTrue(fifth is LockController.Result.WrongPin)
        // The remaining time is read after the stretch, which takes a few seconds in the debug build.
        assertTrue((fifth as LockController.Result.WrongPin).remainingMs in 15_000..30_000)
        assertEquals(5, lock.attempts.failures)
        val delayed = lock.unlock(pin)
        assertTrue(delayed is LockController.Result.Delayed)
        assertEquals(5, lock.attempts.failures)  // refused attempts do not count
        // The delay survives a new policy object (a process restart reads the same file).
        assertTrue(AttemptPolicy(context).remainingMs() > 0)
        lock.attempts.reset()
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        assertEquals(0, lock.attempts.failures)
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
        assertTrue(lock.lastUnlockMs > 0)
        Log.i("PrivateVaultTest", "unlock took ${lock.lastUnlockMs} ms; ${lock.describeKey()}")
    }

    // Clock injection: forward wall clock does not shorten the wait, a reboot restarts it.
    @Test
    fun delayFollowsTheClocks() {
        var elapsed = 1_000_000L
        var wall = 5_000_000L
        var boot = 3L
        val policy = AttemptPolicy(context, object : AttemptPolicy.Clock {
            override fun elapsedMs() = elapsed
            override fun wallMs() = wall
            override fun bootCount() = boot
        })
        policy.reset()
        repeat(5) { policy.recordAttempt() }
        assertEquals(30_000L, policy.remainingMs())
        elapsed += 10_000; wall += 10_000
        assertEquals(20_000L, policy.remainingMs())
        wall += 3_600_000  // clock set forward
        assertEquals(20_000L, policy.remainingMs())
        wall -= 7_200_000  // clock set back
        assertEquals(30_000L, policy.remainingMs())
        wall += 3_600_000  // net ten seconds after the attempt by the wall clock
        elapsed += 20_000  // thirty seconds by the elapsed clock
        assertEquals(20_000L, policy.remainingMs())  // the larger estimate
        wall += 20_000
        assertEquals(0L, policy.remainingMs())
        policy.recordAttempt()
        assertEquals(60_000L, policy.remainingMs())
        boot += 1
        elapsed = 100
        wall += 10_000_000
        assertEquals(60_000L, policy.remainingMs())
        policy.reset()
        assertEquals(0L, policy.remainingMs())
    }

    // The attempt is on disk before the stretch starts, so a process killed
    // during the stretch has already counted; a success resets it.
    @Test
    fun attemptIsRecordedBeforeTheStretch() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        lock.lock("test")
        var seen = -1
        lock.testHooks.beforeStretch = {
            seen = AttemptPolicy(context).failures
            throw RuntimeException("killed during the stretch")
        }
        try {
            lock.unlock(pin)
            throw AssertionError("hook did not fire")
        } catch (e: RuntimeException) {
            assertEquals("killed during the stretch", e.message)
        }
        assertEquals(1, seen)
        assertEquals(1, lock.attempts.failures)
        assertEquals(LockState.LOCKED, lock.state)
        lock.testHooks.beforeStretch = null
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        assertEquals(0, lock.attempts.failures)
    }

    @Test
    fun changePinRotatesTheKey() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        val id = app.vaultSession.vault!!.encryptSample(TestKeys.SEED, 10_000L)
        val oldAlias = lock.currentAlias!!
        val before = File(lock.dir, Vault.KEY_FILE).readBytes()
        val r = lock.changePin(wrong, other)
        assertTrue(r is LockController.Result.WrongPin)
        assertEquals(1, lock.attempts.failures)
        assertTrue(lock.changePin(pin, other) is LockController.Result.Ok)
        assertEquals(0, lock.attempts.failures)
        val newAlias = lock.currentAlias!!
        assertFalse(oldAlias == newAlias)
        assertFalse(lock.hmac.exists(oldAlias))
        assertTrue(lock.hmac.exists(newAlias))
        assertFalse(before.contentEquals(File(lock.dir, Vault.KEY_FILE).readBytes()))
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
        lock.lock("test")
        assertTrue(lock.unlock(pin) is LockController.Result.WrongPin)
        assertTrue(lock.unlock(other) is LockController.Result.Ok)
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
    }

    // Builds a provider 2 vault in the app directory, as Phases 1 and 2 left it.
    private fun makeProvider2Vault(): ByteArray {
        val provider = app.vaultSession.provider
        provider.deleteKey()
        val v = Vault.create(context.filesDir, provider)
        val id = v.encryptSample(TestKeys.SEED, 20_000L)
        v.close()
        lock.start()
        assertEquals(LockState.LOCKED, lock.state)
        assertEquals(LockController.VaultKind.PROVIDER2, lock.vaultKind())
        assertTrue(provider.keyExists())
        return id
    }

    @Test
    fun migrationFromProvider2() {
        val id = makeProvider2Vault()
        val provider = app.vaultSession.provider
        assertTrue(lock.unlock(pin) is LockController.Result.Unopenable)  // needs setup first
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        assertEquals(LockState.UNLOCKED, lock.state)
        assertEquals(LockController.VaultKind.PROVIDER3, lock.vaultKind())
        assertFalse(provider.keyExists())
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
        lock.lock("test")
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
    }

    // Interruption at each rewrap step: afterwards either the old PIN (provider
    // 2: the setup flow) or the new PIN opens the vault, never neither, and
    // the next successful unlock removes leftover keys.
    @Test
    fun migrationInterruptedAtEveryStep() {
        for (point in listOf("after_key_created", "before_write", "after_write", "before_old_key_delete")) {
            lock.erase()
            val id = makeProvider2Vault()
            val provider = app.vaultSession.provider
            lock.testHooks.failAt = point
            val r = lock.setup(pin)
            assertTrue("$point: $r", r is LockController.Result.Failed)
            assertEquals(point, LockState.LOCKED, lock.state)
            assertNull(app.vaultSession.vault)
            lock.testHooks.failAt = null
            val kind = lock.vaultKind()
            Log.i("PrivateVaultTest", "migration interrupted $point: vault is $kind, provider 2 key ${provider.keyExists()}, pin aliases ${lock.hmac.aliases().size}")
            if (point == "after_key_created" || point == "before_write") {
                assertEquals(point, LockController.VaultKind.PROVIDER2, kind)
                assertTrue(provider.keyExists())
                assertEquals(1, lock.hmac.aliases().size)  // the unused new key
                assertTrue(lock.setup(pin) is LockController.Result.Ok)  // setup again completes it
            } else {
                assertEquals(point, LockController.VaultKind.PROVIDER3, kind)
                assertTrue(provider.keyExists())  // deleted only after the commit
                assertTrue(lock.unlock(pin) is LockController.Result.Ok)
                assertFalse(provider.keyExists())  // the sweep removed it
            }
            assertEquals(1, lock.hmac.aliases().size)
            assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
            lock.lock("test")
        }
    }

    @Test
    fun pinChangeInterruptedAtEveryStep() {
        for (point in listOf("after_key_created", "before_write", "after_write", "before_old_key_delete")) {
            lock.erase()
            assertTrue(lock.setup(pin) is LockController.Result.Ok)
            val id = app.vaultSession.vault!!.encryptSample(TestKeys.SEED, 1000L)
            val oldAlias = lock.currentAlias!!
            lock.testHooks.failAt = point
            assertTrue(lock.changePin(pin, other) is LockController.Result.Failed)
            lock.testHooks.failAt = null
            lock.lock("test")
            val newWorks = point == "after_write" || point == "before_old_key_delete"
            val r = lock.unlock(if (newWorks) other else pin)
            assertTrue("$point: ${r.describe()}", r is LockController.Result.Ok)
            assertEquals(NativeBridge.OK, app.vaultSession.vault!!.decryptVerify(id).first)
            assertEquals(point, 1, lock.hmac.aliases().size)
            if (newWorks) assertFalse(lock.hmac.exists(oldAlias)) else assertTrue(lock.hmac.exists(oldAlias))
            lock.lock("test")
            val wrongOne = lock.unlock(if (newWorks) pin else other)
            assertTrue(point, wrongOne is LockController.Result.WrongPin)
            lock.attempts.reset()
        }
    }

    @Test
    fun missingKeystoreKeyIsUnopenable() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        lock.lock("test")
        lock.hmac.delete(lock.currentAlias!!)
        val r = lock.unlock(pin)
        assertTrue(r is LockController.Result.Unopenable)
        assertEquals("KEY_MISSING", (r as LockController.Result.Unopenable).reason)
        assertEquals(0, lock.attempts.failures)  // not a guess
        // Erase from the locked state is the way out.
        assertEquals(NativeBridge.OK, lock.erase())
        assertEquals(LockState.NO_VAULT, lock.state)
        assertFalse(lock.dir.exists())
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
    }

    @Test
    fun captureIsRefusedWhileLockedAndLockCancelsAnEncrypt() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        val session = app.captureSession
        val owner = TestLifecycleOwner()
        val camera = TestCamera(owner)
        camera.bind(CameraSelector.LENS_FACING_BACK, highest = false)
        try {
            // A slowed encrypt (500 ms per read) is cancelled by the lock and leaves nothing.
            NativeBridge.nativeSetTestHooks(500, 0)
            assertTrue(session.reserve())
            val c = camera.take(session.executor)
            val proxy = c.proxy ?: throw AssertionError("capture error: ${c.error?.message}")
            val latch = CountDownLatch(1)
            var result: CaptureSession.Result? = null
            session.submit(proxy, System.nanoTime()) { r -> result = r; latch.countDown() }
            Thread.sleep(300)
            val t0 = System.nanoTime()
            lock.lock("test")
            val lockMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            Log.i("PrivateVaultTest", "lock during encrypt: ${result!!.line()} (lock took $lockMs ms)")
            assertEquals("CANCELLED", result!!.status)
            assertEquals(LockState.LOCKED, lock.state)
            assertTrue(lockMs < LockController.DRAIN_MS + 1000)
            NativeBridge.nativeSetTestHooks(0, 0)
            // Locked: no slot can be reserved and the session refuses work.
            assertFalse(session.reserve())
            assertNull(app.vaultSession.vault)
            // Unlock: nothing was left behind and captures work again.
            assertTrue(lock.unlock(pin) is LockController.Result.Ok)
            assertEquals(0, partials().size)
            assertEquals(0, objectsDir().listFiles()!!.count { it.name.endsWith(".pvo") })
            assertTrue(session.reserve())
            val c2 = camera.take(session.executor)
            val latch2 = CountDownLatch(1)
            var r2: CaptureSession.Result? = null
            session.submit(c2.proxy!!, System.nanoTime()) { r -> r2 = r; latch2.countDown() }
            assertTrue(latch2.await(60, TimeUnit.SECONDS))
            assertTrue(r2!!.line(), r2!!.ok)
        } finally {
            NativeBridge.nativeSetTestHooks(0, 0)
            camera.unbind()
        }
    }

    @Test
    fun eraseFromUnlockedAndInterruptedEraseFinishes() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        app.vaultSession.vault!!.encryptSample(TestKeys.SEED, 5000L)
        val alias = lock.currentAlias!!
        assertEquals(NativeBridge.OK, lock.erase())
        assertEquals(LockState.NO_VAULT, lock.state)
        assertFalse(lock.dir.exists())
        assertFalse(lock.hmac.exists(alias))
        assertNull(lock.currentAlias)
        assertFalse(lock.erasePending)

        // An erase that got as far as the key deletion and then died: the
        // pending flag makes the next start remove the files.
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        app.vaultSession.vault!!.encryptSample(TestKeys.SEED, 5000L)
        lock.lock("test")
        context.getSharedPreferences(LockController.PREFS, 0).edit().putBoolean("erase_pending", true).commit()
        lock.hmac.delete(lock.currentAlias!!)
        assertTrue(lock.dir.exists())
        lock.start()
        assertFalse(lock.dir.exists())
        assertEquals(LockState.NO_VAULT, lock.state)
        assertFalse(lock.erasePending)
        assertEquals(0, lock.hmac.aliases().size)
    }

    // A vault made without the override carries the approved defaults.
    @Test
    fun defaultsAreRecorded() {
        lock.testHooks.kdfOverride = null
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        val info = PinEnvelope.parse(File(lock.dir, Vault.KEY_FILE).readBytes())
        assertEquals(2L, info.opslimit)
        assertEquals(256L shl 20, info.memlimit)
        assertEquals(NativeBridge.PIN_OPSLIMIT_DEFAULT, info.opslimit)
        assertEquals(NativeBridge.PIN_MEMLIMIT_DEFAULT, info.memlimit)
        lock.lock("test")
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        Log.i("PrivateVaultTest", "unlock with the defaults took ${lock.lastUnlockMs} ms (debug build)")
    }

    // An allocation failure in the stretch is not a guess: the pre-recorded
    // attempt is rolled back, including an active delay.
    @Test
    fun allocationFailureIsNotAGuess() {
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        lock.lock("test")
        assertTrue(lock.unlock(wrong) is LockController.Result.WrongPin)
        assertEquals(1, lock.attempts.failures)
        val before = lock.attempts.current()
        NativeBridge.nativeSetPinStretchFailure(true)
        val r = lock.unlock(pin)
        assertTrue(r.describe(), r is LockController.Result.NoMemory)
        assertEquals(1, lock.attempts.failures)
        val after = lock.attempts.current()
        assertEquals(before.delayMs, after.delayMs)
        assertEquals(before.failElapsedMs, after.failElapsedMs)
        assertEquals(before.failWallMs, after.failWallMs)
        assertEquals(LockState.LOCKED, lock.state)
        NativeBridge.nativeSetPinStretchFailure(false)
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        assertEquals(0, lock.attempts.failures)
        // The same during a PIN change: the counter is untouched and the vault stays unlocked.
        NativeBridge.nativeSetPinStretchFailure(true)
        assertTrue(lock.changePin(pin, other) is LockController.Result.NoMemory)
        assertEquals(0, lock.attempts.failures)
        assertEquals(LockState.UNLOCKED, lock.state)
        NativeBridge.nativeSetPinStretchFailure(false)
        assertTrue(lock.changePin(pin, other) is LockController.Result.Ok)
    }

    @Test
    fun pinBufferHoldsDigitsAndZeroes() {
        val b = PinBuffer()
        for (d in listOf(1, 2, 3, 4, 5, 6)) assertTrue(b.push(d))
        assertEquals(6, b.length)
        val c = b.copy()
        assertTrue("123456".toByteArray().contentEquals(c))
        val b2 = PinBuffer()
        for (d in listOf(1, 2, 3, 4, 5, 6)) b2.push(d)
        assertTrue(b.sameAs(b2))
        b2.pop()
        assertFalse(b.sameAs(b2))
        assertFalse(b.push(10))
        for (i in 7..12) assertTrue(b.push(i % 10))
        assertFalse(b.push(0))  // 12 is the limit
        b.clear()
        assertTrue(b.isZeroed())
        assertNotNull(c)
    }
}
