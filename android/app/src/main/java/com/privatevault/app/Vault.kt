package com.privatevault.app

import java.io.File

// One vault under the app's private files directory. Opening it means
// unwrapping the vault master key through the Keystore envelope and handing
// the key bytes to the core, which keeps them in guarded memory.
class Vault private constructor(val dir: File, private var handle: Long) {

    class CoreException(val code: Int) : Exception(NativeBridge.errorName(code))

    companion object {
        const val DIR_NAME = "vault"
        const val KEY_FILE = "vault.key"

        fun dirFor(filesDir: File): File = File(filesDir, DIR_NAME)

        fun exists(filesDir: File): Boolean = File(dirFor(filesDir), KEY_FILE).exists()

        // Creates the directory layout, a fresh VMK, and the envelope.
        fun create(filesDir: File, provider: KeystoreKeyProvider): Vault {
            val dir = dirFor(filesDir)
            val keyFile = File(dir, KEY_FILE)
            if (keyFile.exists()) throw CoreException(NativeBridge.ERR_DEST_EXISTS)
            check(NativeBridge.nativeVaultCreate(dir.absolutePath))
            val vmk = ByteArray(NativeBridge.VMK_BYTES)
            try {
                check(NativeBridge.nativeVmkGenerate(vmk))
                val envelope = provider.seal(vmk)
                check(NativeBridge.nativeFileWriteAtomic(keyFile.absolutePath, envelope))
                return openWithKey(dir, vmk)
            } finally {
                vmk.fill(0)
            }
        }

        fun open(filesDir: File, provider: KeystoreKeyProvider): Vault {
            val dir = dirFor(filesDir)
            val envelope = NativeBridge.nativeFileReadAll(File(dir, KEY_FILE).absolutePath)
                ?: throw CoreException(NativeBridge.ERR_NOT_FOUND)
            val vmk = provider.open(envelope)
            try {
                return openWithKey(dir, vmk)
            } finally {
                vmk.fill(0)
            }
        }

        // Opens with raw key bytes. The caller wipes its copy afterwards.
        fun openWithKey(dir: File, vmk: ByteArray): Vault {
            val h = NativeBridge.nativeVaultOpen(dir.absolutePath, vmk)
            if (h <= 0) throw CoreException((-h).toInt())
            return Vault(dir, h)
        }

        private fun check(rc: Int) {
            if (rc != NativeBridge.OK) throw CoreException(rc)
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
        check(NativeBridge.nativeEncryptSample(handle, seed, length, chunkSize, id))
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
