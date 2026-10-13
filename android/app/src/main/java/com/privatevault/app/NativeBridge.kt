package com.privatevault.app

// Kotlin side of the JNI bridge. Every call goes to android/app/src/main/cpp/jni_bridge.cpp
// and from there to the core C ABI. Status codes are the PV_* codes from pv/pv.h; the
// constants below mirror that header one to one.
object NativeBridge {
    const val OK = 0
    const val ERR_INIT = 1
    const val ERR_INVALID_ARGUMENT = 2
    const val ERR_IO = 3
    const val ERR_DEST_EXISTS = 4
    const val ERR_MALFORMED_HEADER = 5
    const val ERR_UNSUPPORTED_VERSION = 6
    const val ERR_UNSUPPORTED_SUITE = 7
    const val ERR_CHUNK_SIZE_OUT_OF_RANGE = 8
    const val ERR_WRONG_VMK = 9
    const val ERR_AUTH_FAILED = 10
    const val ERR_TRUNCATED = 11
    const val ERR_TRAILING_DATA = 12
    const val ERR_MALFORMED_CHUNK = 13
    const val ERR_ENVELOPE_MALFORMED = 14
    const val ERR_ENVELOPE_UNSUPPORTED = 15
    const val ERR_ENVELOPE_KDF_PARAMS = 16
    const val ERR_ENVELOPE_UNWRAP_FAILED = 17
    const val ERR_OUT_OF_MEMORY = 18
    const val ERR_INTERNAL = 19
    const val ERR_NOT_FOUND = 20

    const val VMK_BYTES = 32
    const val OBJECT_ID_BYTES = 16
    const val HASH_BYTES = 32
    const val SEED_BYTES = 32

    init {
        System.loadLibrary("privatevault_jni")
    }

    external fun nativeInit(): Int
    external fun nativeVersion(): String
    external fun nativeSodiumVersion(): String
    external fun nativeErrorName(code: Int): String
    // errno (or GetLastError) of the last failed file call on this thread, 0 if none.
    external fun nativeLastOsError(): Int

    external fun nativeVmkGenerate(out: ByteArray): Int
    external fun nativeVaultCreate(dir: String): Int
    // Returns a status; on OK handleOut[0] is the vault handle. The handle is an opaque
    // 64-bit value whose sign means nothing (tagged pointers on arm64).
    external fun nativeVaultOpen(dir: String, vmk: ByteArray, handleOut: LongArray): Int
    external fun nativeVaultClose(handle: Long)

    external fun nativeEncryptSample(
        handle: Long, seed: ByteArray, length: Long, chunkSize: Int, idOut: ByteArray
    ): Int
    external fun nativeDecryptVerify(
        handle: Long, id: ByteArray, hashOut: ByteArray, lenOut: LongArray
    ): Int
    external fun nativeInspect(handle: Long, id: ByteArray, infoOut: IntArray): Int
    external fun nativeObjectPath(handle: Long, id: ByteArray): String?

    external fun nativeHashDeterministic(seed: ByteArray, length: Long, hashOut: ByteArray): Int
    external fun nativeDecryptFile(
        path: String, vmk: ByteArray, expectedId: ByteArray?, hashOut: ByteArray, lenOut: LongArray
    ): Int
    external fun nativeEncryptSampleFile(
        path: String, vmk: ByteArray, seed: ByteArray, length: Long, chunkSize: Int, idOut: ByteArray
    ): Int

    external fun nativeFileWriteAtomic(path: String, data: ByteArray): Int
    external fun nativeFileReadAll(path: String): ByteArray?
    external fun nativeFileDelete(path: String): Int
    external fun nativeProbeLink(dir: String): Int

    // Name plus the raw value, so an unexpected code is never hidden.
    fun errorName(code: Int): String = "${nativeErrorName(code)} ($code)"

    // Status codes for which the platform error is informative.
    fun isFileStatus(code: Int): Boolean =
        code == ERR_IO || code == ERR_NOT_FOUND || code == ERR_DEST_EXISTS

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
