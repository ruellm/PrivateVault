package com.privatevault.app

// Kotlin side of the JNI bridge. Every call goes to android/app/src/main/cpp/jni_bridge.cpp
// and from there to the core C ABI. Status codes are the PV_* codes from pv/pv.h.
object NativeBridge {
    const val OK = 0
    const val ERR_DEST_EXISTS = 4
    const val ERR_WRONG_VMK = 9
    const val ERR_AUTH_FAILED = 10
    const val ERR_TRUNCATED = 11
    const val ERR_TRAILING_DATA = 12
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

    external fun nativeVmkGenerate(out: ByteArray): Int
    external fun nativeVaultCreate(dir: String): Int
    // Returns a handle greater than zero, or the negated status code.
    external fun nativeVaultOpen(dir: String, vmk: ByteArray): Long
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

    fun errorName(code: Int): String = nativeErrorName(code)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
