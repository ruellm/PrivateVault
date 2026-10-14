package com.privatevault.app

import android.content.Context

// Measurements that need no vault: Argon2id time per parameter pair and the
// Keystore HMAC key probe. Reachable from the lock screen in every build so
// the benchmark variant can be measured on a phone without scripting. A
// fixed throwaway PIN and a zero salt are used; nothing derived is printed.
object Diagnostics {

    fun kdfBench(say: (String) -> Unit) {
        val pin = "000000".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(NativeBridge.PIN_SALT_BYTES)
        val out = ByteArray(NativeBridge.KEK_BYTES)
        val mems = listOf(64L shl 20, 128L shl 20, 256L shl 20)
        say("kdf_bench: Argon2id, ${Runtime.getRuntime().availableProcessors()} cpus, build ${BuildConfig.BUILD_TYPE}")
        try {
            for (ops in 2L..3L) {
                for (mem in mems) {
                    val times = ArrayList<Long>()
                    var rc = NativeBridge.OK
                    for (i in 1..3) {
                        val t0 = System.nanoTime()
                        rc = NativeBridge.nativePinStretch(pin, salt, ops, mem, out)
                        times.add((System.nanoTime() - t0) / 1_000_000)
                        if (rc != NativeBridge.OK) break
                    }
                    val label = "ops=$ops mem=${mem shr 20}MiB"
                    if (rc != NativeBridge.OK) say("kdf_bench $label: ${NativeBridge.errorName(rc)}")
                    else say("kdf_bench $label: ${times.joinToString(" ") { "${it}ms" }}")
                }
            }
            val rcLow = NativeBridge.nativePinStretch(pin, salt, 1L, 64L shl 20, out)
            val rcHigh = NativeBridge.nativePinStretch(pin, salt, 2L, 512L shl 20, out)
            say("kdf_bench bounds: ops=1 ${NativeBridge.errorName(rcLow)}, mem=512MiB ${NativeBridge.errorName(rcHigh)}")
        } finally {
            pin.fill(0)
            out.fill(0)
        }
    }

    // Creates a Keystore HMAC-SHA256 key under a probe alias, reports its mode
    // and security level, times five MACs, and deletes the alias again.
    fun hmacProbe(context: Context, say: (String) -> Unit) {
        val alias = "privatevault.pin.probe"
        val hmac = KeystoreHmacProvider(context)
        hmac.deleteKey(alias)
        val t0 = System.nanoTime()
        val (mode, strongBox) = hmac.createKey(alias)
        val createMs = (System.nanoTime() - t0) / 1_000_000
        try {
            say("hmac_probe: created in $createMs ms, mode $mode, strongbox=$strongBox, level ${hmac.securityLevel(alias)}")
            val input = ByteArray(NativeBridge.KEK_BYTES)
            val times = ArrayList<Long>()
            var len = 0
            for (i in 1..5) {
                val t1 = System.nanoTime()
                val m = hmac.mac(alias, input)
                times.add((System.nanoTime() - t1) / 1_000_000)
                len = m?.size ?: -1
            }
            say("hmac_probe: mac of 32 bytes -> $len bytes, ${times.joinToString(" ") { "${it}ms" }}")
        } finally {
            hmac.deleteKey(alias)
            say("hmac_probe: alias deleted, exists=${hmac.keyExists(alias)}")
        }
    }
}
