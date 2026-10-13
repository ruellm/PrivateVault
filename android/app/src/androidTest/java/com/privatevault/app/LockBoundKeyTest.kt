package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// T2, carry-forward from Phase 1 (brief Section 9.1): on an emulator where the
// host script has set a PIN, a fresh key must be created lock-bound and the
// vault must create, encrypt, and verify while the device is unlocked. The
// locked-screen check runs from the host over adb with the app's scripted
// actions; it cannot run inside an instrumentation that needs the screen on.
// The PIN is set and cleared by the host, never by this test.
@RunWith(AndroidJUnit4::class)
class LockBoundKeyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = KeystoreKeyProvider(context)

    @Test
    fun lockBoundKeyWorksWhileUnlocked() {
        assumeTrue("needs a secure lock screen (set a PIN on the emulator first)", provider.deviceIsSecure())
        provider.deleteKey()
        provider.getOrCreateKey()
        assertEquals(KeystoreKeyProvider.MODE_LOCK_BOUND, provider.keyMode())
        Log.i("PrivateVaultTest", provider.describe())

        val files = File(context.filesDir, "lockbound_test")
        files.deleteRecursively()
        files.mkdirs()
        val created = Vault.create(files, provider)
        val id = created.encryptSample(TestKeys.SEED, 20_000L)
        created.close()
        val opened = Vault.open(files, provider)
        val (rc, v) = opened.decryptVerify(id)
        assertEquals(NativeBridge.OK, rc)
        assertEquals(20_000L, v!!.length)
        opened.close()
        files.deleteRecursively()
        assertTrue(provider.keyExists())
    }
}
