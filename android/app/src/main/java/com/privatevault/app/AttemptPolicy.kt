package com.privatevault.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

// Wrong-PIN attempt counter and delay (docs/PHASE3A_DESIGN.md Section 8). The
// arithmetic is in the core; this keeps the counter and the clocks in
// app-private preferences. An attempt is recorded before the stretch runs
// and only a successful unwrap resets it, so a process killed during an
// unlock has already counted. Clocks are injectable for tests.
class AttemptPolicy(context: Context, private val clock: Clock = SystemClocks(context)) {

    interface Clock {
        fun elapsedMs(): Long
        fun wallMs(): Long
        fun bootCount(): Long
    }

    class SystemClocks(private val context: Context) : Clock {
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun bootCount(): Long = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
        } catch (e: Settings.SettingNotFoundException) {
            0L
        }
    }

    companion object {
        const val PREFS = "privatevault_attempts"
        private const val K_FAILURES = "failures"
        private const val K_DELAY = "delay_ms"
        private const val K_FAIL_ELAPSED = "fail_elapsed_ms"
        private const val K_FAIL_WALL = "fail_wall_ms"
        private const val K_BOOT = "boot_count"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val failures: Int get() = prefs.getInt(K_FAILURES, 0)

    // The stored record, so a caller can put it back after an attempt that
    // turned out not to be a guess (the stretch could not get its memory).
    class Record(val failures: Int, val delayMs: Long, val failElapsedMs: Long, val failWallMs: Long, val bootCount: Long)

    fun current(): Record = Record(
        failures, prefs.getLong(K_DELAY, 0L), prefs.getLong(K_FAIL_ELAPSED, 0L),
        prefs.getLong(K_FAIL_WALL, 0L), prefs.getLong(K_BOOT, -1L)
    )

    fun restore(r: Record) {
        prefs.edit()
            .putInt(K_FAILURES, r.failures)
            .putLong(K_DELAY, r.delayMs)
            .putLong(K_FAIL_ELAPSED, r.failElapsedMs)
            .putLong(K_FAIL_WALL, r.failWallMs)
            .putLong(K_BOOT, r.bootCount)
            .commit()
    }

    // Milliseconds still to wait before the next attempt may start.
    fun remainingMs(): Long {
        val delay = prefs.getLong(K_DELAY, 0L)
        if (delay == 0L) return 0L
        val bootChanged = prefs.getLong(K_BOOT, -1L) != clock.bootCount()
        return NativeBridge.nativePinRemainingMs(
            delay, prefs.getLong(K_FAIL_ELAPSED, 0L), clock.elapsedMs(),
            prefs.getLong(K_FAIL_WALL, 0L), clock.wallMs(), bootChanged
        )
    }

    // Counts an attempt as failed and starts its delay. Written synchronously
    // so the record is on disk before the caller continues.
    fun recordAttempt() {
        val n = failures + 1
        prefs.edit()
            .putInt(K_FAILURES, n)
            .putLong(K_DELAY, NativeBridge.nativePinDelayMs(n))
            .putLong(K_FAIL_ELAPSED, clock.elapsedMs())
            .putLong(K_FAIL_WALL, clock.wallMs())
            .putLong(K_BOOT, clock.bootCount())
            .commit()
    }

    fun reset() {
        prefs.edit().clear().commit()
    }
}
