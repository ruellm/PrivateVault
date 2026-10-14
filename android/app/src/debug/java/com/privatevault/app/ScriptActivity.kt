package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.Executors

// Debug-only entry point for scripted runs (D-009, D-019), exported by the
// debug manifest only. The action list must reach the vault through
// unlock_pin=<pin> or setup_pin=<pin>; before that only hook settings and
// the vault-free measurements (kdf_bench, hmac_probe) are accepted. The
// rest of the list goes to the vault screen as before:
//   adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=123456,verify_all
// Test PINs are throwaway values and are never logged.
class ScriptActivity : Activity() {

    companion object {
        const val TAG = "PrivateVault"
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val list = intent?.getStringExtra("actions")
        val names = list?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (names.isEmpty()) {
            Log.i(TAG, "script refused: no actions")
            finish()
            return
        }
        val lock = PvApp.of(this).lock
        worker.execute {
            var i = 0
            var entered = false
            while (i < names.size) {
                val n = names[i]
                when {
                    // Unlocked: everything but a PIN change goes to the vault screen.
                    entered && !n.startsWith("change_pin=") -> break
                    n.startsWith("set_idle_ms=") -> {
                        lock.idleTimeoutMs = n.substringAfter('=').toLong()
                        Log.i(TAG, "script: idle timeout ${lock.idleTimeoutMs} ms")
                    }
                    // Measurements that need no vault may run before the unlock.
                    n == "kdf_bench" -> Diagnostics.kdfBench { Log.i(TAG, it) }
                    n == "hmac_probe" -> Diagnostics.hmacProbe(this) { Log.i(TAG, it) }
                    n.startsWith("unlock_delay_ms=") -> {
                        val ms = n.substringAfter('=').toLong()
                        lock.testHooks.beforeStretch = { Thread.sleep(ms) }
                        Log.i(TAG, "script: unlock delayed by $ms ms before the stretch")
                    }
                    // change_pin=<current>:<new> after an unlock_pin; rewraps the vault.
                    n.startsWith("change_pin=") && entered -> {
                        val parts = n.substringAfter('=').split(':')
                        val old = parts[0].toByteArray(Charsets.US_ASCII)
                        val new = parts.getOrElse(1) { parts[0] }.toByteArray(Charsets.US_ASCII)
                        val r = try {
                            lock.changePin(old, new)
                        } finally {
                            old.fill(0)
                            new.fill(0)
                        }
                        Log.i(TAG, "script: change_pin ${r.describe()}; lock state ${lock.state}, attempts ${lock.attempts.failures}")
                        if (r !is LockController.Result.Ok) {
                            Log.i(TAG, "[scripted] all actions finished")
                            runOnUiThread { finish() }
                            return@execute
                        }
                    }
                    n.startsWith("unlock_pin=") || n.startsWith("setup_pin=") -> {
                        val pin = n.substringAfter('=').toByteArray(Charsets.US_ASCII)
                        val r = try {
                            if (n.startsWith("unlock_pin=")) lock.unlock(pin) else lock.setup(pin)
                        } finally {
                            pin.fill(0)
                        }
                        Log.i(TAG, "script: ${n.substringBefore('=')} ${r.describe()}; lock state ${lock.state}, attempts ${lock.attempts.failures}")
                        lock.testHooks.beforeStretch = null
                        entered = r is LockController.Result.Ok
                        if (!entered) {
                            Log.i(TAG, "[scripted] all actions finished")
                            runOnUiThread { finish() }
                            return@execute
                        }
                    }
                    else -> {
                        Log.i(TAG, "script refused: first action must be unlock_pin or setup_pin (got $n)")
                        Log.i(TAG, "[scripted] all actions finished")
                        runOnUiThread { finish() }
                        return@execute
                    }
                }
                i++
            }
            val rest = names.subList(i, names.size)
            runOnUiThread {
                if (entered) {
                    val next = Intent(this, MainActivity::class.java)
                    if (rest.isNotEmpty()) next.putExtra("actions", rest.joinToString(","))
                    startActivity(next)
                } else {
                    Log.i(TAG, "[scripted] all actions finished")
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
