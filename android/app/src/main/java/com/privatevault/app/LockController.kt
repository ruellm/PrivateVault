package com.privatevault.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

enum class LockState { NO_VAULT, LOCKED, UNLOCKED, LOCKING }

// Process-scoped lock state machine (docs/PHASE3A_DESIGN.md Sections 4, 5,
// 8, 10). Owns the unlock, setup, PIN change, migration, lock, and erase
// flows. The PIN bytes given to it are the caller's; this class zeroes only
// what it makes (k_pin, kek). Nothing here logs a PIN, a key, or a PIN length.
class LockController(
    private val context: Context,
    private val vaults: VaultSession,
    private val captures: CaptureSession,
    private val recorder: VideoRecorder? = null
) {
    companion object {
        const val TAG = "PrivateVault"
        const val PREFS = "privatevault_lock"
        private const val K_ALIAS = "current_alias"
        private const val K_ERASE_PENDING = "erase_pending"
        const val IDLE_MS_DEFAULT = 5L * 60L * 1000L
        const val DRAIN_MS = 5000L
        const val PIN_MIN = 6
        const val PIN_MAX = 12
    }

    enum class VaultKind { NONE, PROVIDER2, PROVIDER3, UNKNOWN }

    sealed class Result {
        object Ok : Result()
        class WrongPin(val remainingMs: Long) : Result()
        class Delayed(val remainingMs: Long) : Result()
        class Unopenable(val reason: String) : Result()
        class Failed(val message: String) : Result()
        // Argon2id could not get its memory. Not a guess: the recorded attempt is rolled back.
        object NoMemory : Result()

        fun describe(): String = when (this) {
            Ok -> "ok"
            NoMemory -> "not enough memory, try again"
            is WrongPin -> "wrong PIN" + (if (remainingMs > 0) ", wait ${(remainingMs + 999) / 1000} s" else "")
            is Delayed -> "wait ${(remainingMs + 999) / 1000} s"
            is Unopenable -> "vault cannot be opened: $reason"
            is Failed -> "failed: $message"
        }
    }

    interface Listener {
        fun onLockStateChanged(state: LockState)
    }

    // Test hooks. failAt names a point in the rewrap (after_key_created,
    // before_write, after_write, before_old_key_delete) at which the flow
    // throws, standing in for a process kill. beforeStretch runs between the
    // attempt record and the stretch.
    class TestHooks {
        @Volatile var failAt: String? = null
        @Volatile var beforeStretch: (() -> Unit)? = null
        // Argon2id parameters for new envelopes instead of the defaults; tests
        // use INTERACTIVE values so the suites stay short.
        @Volatile var kdfOverride: Pair<Long, Long>? = null
    }

    private val kdfOps: Long get() = testHooks.kdfOverride?.first ?: NativeBridge.PIN_OPSLIMIT_DEFAULT
    private val kdfMem: Long get() = testHooks.kdfOverride?.second ?: NativeBridge.PIN_MEMLIMIT_DEFAULT

    class HookAbort(point: String) : RuntimeException("test hook $point")

    val testHooks = TestHooks()
    val attempts = AttemptPolicy(context)
    val hmac = KeystoreHmacProvider(context)
    private val legacy: KeystoreKeyProvider get() = vaults.provider
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val lockExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-lock") }
    private val listeners = ArrayList<Listener>()
    private val gate = Any()
    @Volatile private var current = LockState.LOCKED
    @Volatile var idleTimeoutMs: Long = IDLE_MS_DEFAULT
    @Volatile var lastUnlockMs: Long = 0
        private set
    private val idleRunnable = Runnable { requestLock("idle") }

    val state: LockState get() = current
    val dir: File get() = Vault.dirFor(vaults.filesDir)
    val currentAlias: String? get() = prefs.getString(K_ALIAS, null)
    val erasePending: Boolean get() = prefs.getBoolean(K_ERASE_PENDING, false)

    // Called once at process start: finishes an interrupted erase, removes a
    // vault directory that never got its envelope, and settles the state.
    fun start() {
        if (erasePending) {
            Log.i(TAG, "lock: finishing an interrupted erase")
            eraseFiles()
        }
        if (dir.isDirectory && !File(dir, Vault.KEY_FILE).exists()) {
            Log.i(TAG, "lock: removing a vault directory without an envelope")
            NativeBridge.nativeVaultDestroyFiles(dir.absolutePath)
        }
        setState(if (vaultKind() == VaultKind.NONE) LockState.NO_VAULT else LockState.LOCKED)
    }

    fun vaultKind(): VaultKind {
        val env = PinEnvelope.read(dir) ?: return VaultKind.NONE
        return when (PinEnvelope.providerOf(env)) {
            2 -> VaultKind.PROVIDER2
            NativeBridge.ENVELOPE_PROVIDER_PIN -> VaultKind.PROVIDER3
            else -> VaultKind.UNKNOWN
        }
    }

    @Synchronized
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun setState(s: LockState) {
        current = s
        val copy: List<Listener>
        synchronized(this) { copy = ArrayList(listeners) }
        main.post { for (l in copy) l.onLockStateChanged(s) }
    }

    private fun pinOk(pin: ByteArray): Boolean =
        pin.size in PIN_MIN..PIN_MAX && pin.all { it in '0'.code.toByte()..'9'.code.toByte() }

    // Unlock with the PIN. Runs the stretch, the Keystore step, and the core
    // open; see the design, Section 4.1, for the order and why the attempt is
    // recorded first.
    fun unlock(pin: ByteArray): Result {
        synchronized(gate) {
            if (current != LockState.LOCKED) return Result.Failed("state $current")
            if (!pinOk(pin)) return Result.Failed("PIN length")
            val remaining = attempts.remainingMs()
            if (remaining > 0) return Result.Delayed(remaining)
            val env = PinEnvelope.read(dir) ?: return Result.Unopenable("NO_ENVELOPE")
            if (PinEnvelope.providerOf(env) != NativeBridge.ENVELOPE_PROVIDER_PIN) {
                return Result.Unopenable("PROVIDER_${PinEnvelope.providerOf(env)}")
            }
            val info = try {
                PinEnvelope.parse(env)
            } catch (e: PinEnvelope.ParseException) {
                return Result.Unopenable(NativeBridge.nativeErrorName(e.code))
            }
            if (info.mechanism != hmac.mechanism) return Result.Unopenable("MECHANISM_${info.mechanism}")
            if (!hmac.exists(info.alias)) return Result.Unopenable("KEY_MISSING")
            val before = attempts.current()
            attempts.recordAttempt()
            testHooks.beforeStretch?.invoke()
            val t0 = System.nanoTime()
            val handleOut = LongArray(1)
            val rc = deriveAndOpen(pin, info, handleOut)
            when (rc) {
                is Result.Ok -> {}
                is Result.NoMemory -> {
                    attempts.restore(before)
                    return rc
                }
                else -> return rc
            }
            val vault = Vault.fromHandle(dir, handleOut[0])
            attempts.reset()
            sweepAliases(info.alias)
            prefs.edit().putString(K_ALIAS, info.alias).apply()
            captures.resume()
            vaults.adopt(vault)
            lastUnlockMs = (System.nanoTime() - t0) / 1_000_000
            setState(LockState.UNLOCKED)
            touch()
            Log.i(TAG, "lock: unlocked in $lastUnlockMs ms")
            scheduleSweep()
            return Result.Ok
        }
    }

    // Result of the last orphan-segment sweep (tests read and reset it).
    @Volatile var lastSweep: VideoItems.SweepResult? = null

    // The sweep of docs/PHASE3D_DESIGN.md Section 4.6 runs on the lock thread
    // right after an unlock of an existing vault; the unlock itself does not
    // wait for it. A lock queued behind it runs once it is done.
    private fun scheduleSweep() {
        lockExecutor.execute {
            val v = vaults.vault ?: return@execute
            if (current != LockState.UNLOCKED) return@execute
            val r = VideoItems.sweep(v)
            lastSweep = r
            Log.i(TAG, "sweep: ${r.line()}")
        }
    }

    // Stretch, derive, open. Returns Ok with the handle in handleOut, or the
    // failure. k_pin and kek are zeroed before return.
    private fun deriveAndOpen(pin: ByteArray, info: PinEnvelope.Info, handleOut: LongArray): Result {
        val kPin = ByteArray(NativeBridge.KEK_BYTES)
        var kek: ByteArray? = null
        try {
            val src = NativeBridge.nativePinStretch(pin, info.salt, info.opslimit, info.memlimit, kPin)
            if (src == NativeBridge.ERR_OUT_OF_MEMORY) return Result.NoMemory
            if (src != NativeBridge.OK) return Result.Failed("stretch ${NativeBridge.errorName(src)}")
            val derived = try {
                hmac.derive(info.alias, kPin)
            } catch (e: Exception) {
                return Result.Unopenable("keystore ${e.javaClass.simpleName}")
            }
            if (derived == null) return Result.Unopenable("KEY_MISSING")
            kek = derived
            val rc = NativeBridge.nativeVaultOpenEnvelope3(dir.absolutePath, kek, handleOut)
            return when (rc) {
                NativeBridge.OK -> Result.Ok
                NativeBridge.ERR_ENVELOPE_UNWRAP_FAILED -> Result.WrongPin(attempts.remainingMs())
                else -> Result.Failed("open ${NativeBridge.errorName(rc)}")
            }
        } finally {
            kPin.fill(0)
            kek?.fill(0)
        }
    }

    // First PIN: creates a new vault, or migrates the provider 2 vault in place.
    fun setup(pin: ByteArray): Result {
        synchronized(gate) {
            if (current != LockState.NO_VAULT && current != LockState.LOCKED) return Result.Failed("state $current")
            if (!pinOk(pin)) return Result.Failed("PIN length")
            return when (vaultKind()) {
                VaultKind.NONE -> createNew(pin)
                VaultKind.PROVIDER2 -> migrate(pin)
                VaultKind.PROVIDER3 -> Result.Failed("a PIN is already set")
                VaultKind.UNKNOWN -> Result.Unopenable("UNKNOWN_ENVELOPE")
            }
        }
    }

    private fun createNew(pin: ByteArray): Result {
        val alias = KeystoreHmacProvider.newAlias()
        hmac.create(alias)
        val salt = ByteArray(NativeBridge.PIN_SALT_BYTES)
        NativeBridge.nativePinSaltGenerate(salt)
        val kPin = ByteArray(NativeBridge.KEK_BYTES)
        var kek: ByteArray? = null
        try {
            val src = NativeBridge.nativePinStretch(pin, salt, kdfOps, kdfMem, kPin)
            if (src == NativeBridge.ERR_OUT_OF_MEMORY) {
                hmac.delete(alias)
                return Result.NoMemory
            }
            if (src != NativeBridge.OK) return Result.Failed("stretch ${NativeBridge.errorName(src)}")
            kek = hmac.derive(alias, kPin) ?: return Result.Failed("KEY_MISSING")
            val handleOut = LongArray(1)
            val rc = NativeBridge.nativeVaultCreateEnvelope3(
                dir.absolutePath, hmac.mechanism, alias, kdfOps, kdfMem, salt, kek, handleOut
            )
            if (rc != NativeBridge.OK) {
                hmac.delete(alias)
                return Result.Failed("create ${NativeBridge.errorName(rc)}")
            }
            prefs.edit().putString(K_ALIAS, alias).apply()
            attempts.reset()
            sweepAliases(alias)
            captures.resume()
            vaults.adopt(Vault.fromHandle(dir, handleOut[0]))
            setState(LockState.UNLOCKED)
            touch()
            Log.i(TAG, "lock: vault created, ${hmac.lastMode}, strongbox=${hmac.lastStrongBox}")
            return Result.Ok
        } finally {
            kPin.fill(0)
            kek?.fill(0)
        }
    }

    // Provider 2 to provider 3 in place (design Section 4.4). The VMK crosses
    // Kotlin once here, for the last use of the provider 2 envelope.
    private fun migrate(pin: ByteArray): Result {
        val env = PinEnvelope.read(dir) ?: return Result.Unopenable("NO_ENVELOPE")
        val vmk = try {
            legacy.open(env)
        } catch (e: KeystoreKeyProvider.EnvelopeException) {
            return Result.Unopenable("provider 2 ${NativeBridge.nativeErrorName(e.code)}")
        } catch (e: Exception) {
            return Result.Unopenable("provider 2 keystore ${e.javaClass.simpleName}")
        }
        val vault = try {
            Vault.openWithKey(dir, vmk)
        } catch (e: Vault.CoreException) {
            return Result.Failed(e.message ?: "open")
        } finally {
            vmk.fill(0)
        }
        val r = try {
            rewrap(vault, pin, previousAlias = KeystoreKeyProvider.ALIAS, previousIsLegacy = true)
        } catch (e: HookAbort) {
            vault.close()
            return Result.Failed(e.message ?: "hook")
        }
        if (r !is Result.Ok) {
            vault.close()
            return r
        }
        attempts.reset()
        sweepAliases(currentAlias ?: "")
        captures.resume()
        vaults.adopt(vault)
        setState(LockState.UNLOCKED)
        touch()
        Log.i(TAG, "lock: vault migrated to provider 3, ${hmac.lastMode}, strongbox=${hmac.lastStrongBox}")
        scheduleSweep()
        return Result.Ok
    }

    // New key, new salt, new envelope over the same VMK; the previous key is
    // deleted only after the rename (design Section 4.3).
    private fun rewrap(vault: Vault, pin: ByteArray, previousAlias: String?, previousIsLegacy: Boolean): Result {
        val alias = KeystoreHmacProvider.newAlias()
        hmac.create(alias)
        hook("after_key_created")
        val salt = ByteArray(NativeBridge.PIN_SALT_BYTES)
        NativeBridge.nativePinSaltGenerate(salt)
        val kPin = ByteArray(NativeBridge.KEK_BYTES)
        var kek: ByteArray? = null
        try {
            val src = NativeBridge.nativePinStretch(pin, salt, kdfOps, kdfMem, kPin)
            if (src == NativeBridge.ERR_OUT_OF_MEMORY) {
                hmac.delete(alias)
                return Result.NoMemory
            }
            if (src != NativeBridge.OK) return Result.Failed("stretch ${NativeBridge.errorName(src)}")
            kek = hmac.derive(alias, kPin) ?: return Result.Failed("KEY_MISSING")
            hook("before_write")
            val rc = NativeBridge.nativeVaultWriteEnvelope3(
                vault.rawHandle, hmac.mechanism, alias, kdfOps, kdfMem, salt, kek
            )
            if (rc != NativeBridge.OK) {
                hmac.delete(alias)
                return Result.Failed("write ${NativeBridge.errorName(rc)}")
            }
        } finally {
            kPin.fill(0)
            kek?.fill(0)
        }
        hook("after_write")
        prefs.edit().putString(K_ALIAS, alias).apply()
        hook("before_old_key_delete")
        if (previousAlias != null) {
            if (previousIsLegacy) legacy.deleteKey() else hmac.delete(previousAlias)
        }
        return Result.Ok
    }

    private fun hook(point: String) {
        if (testHooks.failAt == point) throw HookAbort(point)
    }

    // Deletes PIN aliases the current envelope does not name, and the
    // provider 2 key once the envelope is provider 3. Runs only after a
    // successful unwrap.
    private fun sweepAliases(current: String) {
        for (a in hmac.aliases()) {
            if (a != current) {
                Log.i(TAG, "lock: removing unused key alias")
                hmac.delete(a)
            }
        }
        if (legacy.keyExists()) {
            Log.i(TAG, "lock: removing the provider 2 key after migration")
            legacy.deleteKey()
        }
    }

    // Verifies the current PIN against the envelope (a full unlock into a
    // second handle, which also applies the delay), then rewraps.
    fun changePin(current: ByteArray, new: ByteArray): Result {
        synchronized(gate) {
            if (this.current != LockState.UNLOCKED) return Result.Failed("state ${this.current}")
            if (!pinOk(new)) return Result.Failed("PIN length")
            val vault = vaults.vault ?: return Result.Failed("vault not open")
            val remaining = attempts.remainingMs()
            if (remaining > 0) return Result.Delayed(remaining)
            val env = PinEnvelope.read(dir) ?: return Result.Unopenable("NO_ENVELOPE")
            val info = try {
                PinEnvelope.parse(env)
            } catch (e: PinEnvelope.ParseException) {
                return Result.Unopenable(NativeBridge.nativeErrorName(e.code))
            }
            val before = attempts.current()
            attempts.recordAttempt()
            testHooks.beforeStretch?.invoke()
            val handleOut = LongArray(1)
            val check = deriveAndOpen(current, info, handleOut)
            if (check is Result.NoMemory) attempts.restore(before)
            if (check !is Result.Ok) return check
            NativeBridge.nativeVaultClose(handleOut[0])
            attempts.reset()
            return try {
                rewrap(vault, new, previousAlias = info.alias, previousIsLegacy = false)
            } catch (e: HookAbort) {
                Result.Failed(e.message ?: "hook")
            }
        }
    }

    // Asks for a lock from any thread. The work runs on the lock thread.
    fun requestLock(reason: String) {
        if (current != LockState.UNLOCKED) return
        lockExecutor.execute { lock(reason) }
    }

    // Locks now. Not for the main thread: it waits for the encrypt queue.
    fun lock(reason: String) {
        synchronized(gate) {
            if (current != LockState.UNLOCKED) return
            main.removeCallbacks(idleRunnable)
            setState(LockState.LOCKING)
            Log.i(TAG, "lock: locking ($reason)")
            // A recording ends first: its open segment is encrypted and the
            // index written within the bound, or the open segment is dropped
            // (docs/PHASE3E_DESIGN.md Section 4.7).
            if (recorder != null && recorder.isRecording) {
                val t0 = System.nanoTime()
                val finished = recorder.stopAndWait("lock", VideoRecorder.STOP_WAIT_MS)
                Log.i(TAG, "lock: recording ${if (finished) "finished" else "abandoned"} after ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            val idle = captures.drain(DRAIN_MS)
            if (!idle) Log.i(TAG, "lock: encrypt still running after ${DRAIN_MS} ms, waiting")
            captures.awaitIdle()
            vaults.close()
            setState(LockState.LOCKED)
            Log.i(TAG, "lock: locked")
        }
    }

    // User interaction: restarts the idle timer.
    fun touch() {
        if (current != LockState.UNLOCKED) return
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, idleTimeoutMs)
    }

    // Erase in the order of design Section 10. Returns the core status of the
    // file deletion.
    fun erase(): Int {
        prefs.edit().putBoolean(K_ERASE_PENDING, true).commit()
        if (current == LockState.UNLOCKED) lock("erase")
        synchronized(gate) {
            val rc = eraseFiles()
            setState(LockState.NO_VAULT)
            Log.i(TAG, "lock: vault erased, ${NativeBridge.errorName(rc)}")
            return rc
        }
    }

    private fun eraseFiles(): Int {
        if (legacy.keyExists()) legacy.deleteKey()
        for (a in hmac.aliases()) hmac.delete(a)
        val rc = NativeBridge.nativeVaultDestroyFiles(dir.absolutePath)
        attempts.reset()
        prefs.edit().remove(K_ALIAS).putBoolean(K_ERASE_PENDING, rc != NativeBridge.OK).commit()
        return rc
    }

    // One line for the status views: level and mode of the current key.
    fun describeKey(): String {
        val alias = currentAlias ?: return "no PIN key"
        return "Keystore HMAC level ${hmac.securityLevel(alias)}, secure lock screen ${hmac.deviceIsSecure()}"
    }
}
