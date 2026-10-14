package com.privatevault.app

import java.io.File

// Kotlin view of a provider 3 key envelope (docs/PHASE3A_DESIGN.md Section 2).
// The core parses; this only carries the fields the unlock needs and tells
// the envelope providers apart so the app can route a provider 2 vault to
// migration.
object PinEnvelope {

    class Info(
        val slotCount: Int,
        val pinSlotCount: Int,
        val unknownSlotCount: Int,
        val mechanism: Int,
        val alias: String,
        val opslimit: Long,
        val memlimit: Long,
        val salt: ByteArray
    )

    class ParseException(val code: Int) : Exception(NativeBridge.errorName(code))

    // Provider id at offset 10 of any PVKEYENV file, or -1 if the magic is absent.
    fun providerOf(envelope: ByteArray): Int {
        if (envelope.size < 12) return -1
        if (String(envelope, 0, 8, Charsets.US_ASCII) != "PVKEYENV") return -1
        return (envelope[10].toInt() and 0xff) or ((envelope[11].toInt() and 0xff) shl 8)
    }

    fun read(dir: File): ByteArray? =
        NativeBridge.nativeFileReadAll(File(dir, Vault.KEY_FILE).absolutePath)

    fun parse(envelope: ByteArray): Info {
        val info = LongArray(9)
        val salt = ByteArray(NativeBridge.PIN_SALT_BYTES)
        val alias = arrayOfNulls<String>(1)
        val rc = NativeBridge.nativeEnvelope3Parse(envelope, info, salt, alias)
        if (rc != NativeBridge.OK) throw ParseException(rc)
        return Info(
            info[2].toInt(), info[3].toInt(), info[4].toInt(), info[6].toInt(), alias[0] ?: "",
            info[7], info[8], salt
        )
    }
}
