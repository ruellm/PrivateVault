package com.privatevault.app

import java.io.File

// One vault under the app's private files directory. Opening it means
// unwrapping the vault master key through the Keystore envelope and handing
// the key bytes to the core, which keeps them in guarded memory.
class Vault private constructor(val dir: File, private var handle: Long) {

    // A failed core call: the step that failed, the raw status, and the
    // platform error when the status came from a file call.
    class CoreException(val code: Int, val step: String, val osError: Int = 0) :
        Exception(describe(code, step, osError)) {
        companion object {
            fun describe(code: Int, step: String, osError: Int): String {
                val base = "$step: ${NativeBridge.errorName(code)}"
                return if (osError != 0) "$base os_error=$osError" else base
            }
        }
    }

    companion object {
        const val DIR_NAME = "vault"
        const val KEY_FILE = "vault.key"

        fun dirFor(filesDir: File): File = File(filesDir, DIR_NAME)

        fun exists(filesDir: File): Boolean = File(dirFor(filesDir), KEY_FILE).exists()

        // Creates the directory layout, a fresh VMK, and the envelope.
        fun create(filesDir: File, provider: KeystoreKeyProvider): Vault {
            val dir = dirFor(filesDir)
            val keyFile = File(dir, KEY_FILE)
            if (keyFile.exists()) throw CoreException(NativeBridge.ERR_DEST_EXISTS, "envelope exists")
            check(NativeBridge.nativeVaultCreate(dir.absolutePath), "mkdir vault and objects")
            val vmk = ByteArray(NativeBridge.VMK_BYTES)
            try {
                check(NativeBridge.nativeVmkGenerate(vmk), "generate vmk")
                val envelope = provider.seal(vmk)
                check(
                    NativeBridge.nativeFileWriteAtomic(keyFile.absolutePath, envelope),
                    "envelope write and rename"
                )
                return openWithKey(dir, vmk)
            } finally {
                vmk.fill(0)
            }
        }

        fun open(filesDir: File, provider: KeystoreKeyProvider): Vault {
            val dir = dirFor(filesDir)
            val envelope = NativeBridge.nativeFileReadAll(File(dir, KEY_FILE).absolutePath)
                ?: throw CoreException(
                    NativeBridge.ERR_NOT_FOUND, "envelope read", NativeBridge.nativeLastOsError()
                )
            val vmk = provider.open(envelope)
            try {
                return openWithKey(dir, vmk)
            } finally {
                vmk.fill(0)
            }
        }

        // Opens with raw key bytes. The caller wipes its copy afterwards.
        fun openWithKey(dir: File, vmk: ByteArray): Vault {
            val handleOut = LongArray(1)
            check(NativeBridge.nativeVaultOpen(dir.absolutePath, vmk, handleOut), "vault open")
            if (handleOut[0] == 0L) throw CoreException(NativeBridge.ERR_INTERNAL, "vault open: null handle")
            return Vault(dir, handleOut[0])
        }

        private fun check(rc: Int, step: String) {
            if (rc != NativeBridge.OK) {
                val os = if (NativeBridge.isFileStatus(rc)) NativeBridge.nativeLastOsError() else 0
                throw CoreException(rc, step, os)
            }
        }
    }

    val isOpen: Boolean get() = handle != 0L

    fun close() {
        if (handle != 0L) {
            NativeBridge.nativeVaultClose(handle)
            handle = 0L
        }
    }

    fun encryptSample(seed: ByteArray, length: Long, chunkSize: Int = 0): ByteArray {
        val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
        check(NativeBridge.nativeEncryptSample(handle, seed, length, chunkSize, id), "encrypt")
        return id
    }

    data class Verified(val length: Long, val hash: ByteArray)

    // Decrypts into a hash; returns the code instead of throwing so callers can
    // show expected failures.
    fun decryptVerify(id: ByteArray): Pair<Int, Verified?> {
        val hash = ByteArray(NativeBridge.HASH_BYTES)
        val len = LongArray(1)
        val rc = NativeBridge.nativeDecryptVerify(handle, id, hash, len)
        return if (rc == NativeBridge.OK) rc to Verified(len[0], hash) else rc to null
    }

    fun objectPath(id: ByteArray): File = File(NativeBridge.nativeObjectPath(handle, id)!!)

    fun listObjectIds(): List<ByteArray> {
        val objects = File(dir, "objects").listFiles() ?: return emptyList()
        return objects.filter { it.name.endsWith(".pvo") && it.name.length == 36 }
            .map { NativeBridge.fromHex(it.name.removeSuffix(".pvo")) }
    }
}
