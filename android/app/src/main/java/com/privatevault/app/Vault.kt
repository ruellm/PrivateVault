package com.privatevault.app

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock

// One vault under the app's private files directory. Opening it means
// unwrapping the vault master key through the Keystore envelope and handing
// the key bytes to the core, which keeps them in guarded memory. Every core
// call holds the read side of a guard and close holds the write side, so a
// lock that closes the vault waits for the call in flight and every later
// call fails with "vault closed" instead of reaching a freed handle.
class Vault private constructor(val dir: File, private var handle: Long) {

    private val guard = ReentrantReadWriteLock()

    private inline fun <T> withHandle(step: String, block: (Long) -> T): T {
        guard.readLock().lock()
        try {
            val h = handle
            if (h == 0L) throw CoreException(NativeBridge.ERR_INVALID_ARGUMENT, "$step: vault closed")
            return block(h)
        } finally {
            guard.readLock().unlock()
        }
    }

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

        // Wraps a handle the core opened itself (provider 3 paths, where the
        // key never crossed into Kotlin).
        fun fromHandle(dir: File, handle: Long): Vault {
            if (handle == 0L) throw CoreException(NativeBridge.ERR_INTERNAL, "vault open: null handle")
            return Vault(dir, handle)
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

    // Raw handle for the debug-only native helpers.
    internal val rawHandle: Long get() = handle

    // Waits for any core call in flight, then wipes the key in the core.
    fun close() {
        guard.writeLock().lock()
        try {
            if (handle != 0L) {
                NativeBridge.nativeVaultClose(handle)
                handle = 0L
            }
        } finally {
            guard.writeLock().unlock()
        }
    }

    fun encryptSample(seed: ByteArray, length: Long, chunkSize: Int = 0): ByteArray {
        val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
        withHandle("encrypt") { h -> check(NativeBridge.nativeEncryptSample(h, seed, length, chunkSize, id), "encrypt") }
        return id
    }

    data class Verified(val length: Long, val hash: ByteArray)

    // Decrypts into a hash; returns the code instead of throwing so callers can
    // show expected failures.
    fun decryptVerify(id: ByteArray): Pair<Int, Verified?> {
        val hash = ByteArray(NativeBridge.HASH_BYTES)
        val len = LongArray(1)
        val rc = try {
            withHandle("decrypt") { h -> NativeBridge.nativeDecryptVerify(h, id, hash, len) }
        } catch (e: CoreException) {
            e.code
        }
        return if (rc == NativeBridge.OK) rc to Verified(len[0], hash) else rc to null
    }

    fun objectPath(id: ByteArray): File =
        withHandle("object path") { h -> File(NativeBridge.nativeObjectPath(h, id)!!) }

    fun listObjectIds(): List<ByteArray> {
        val objects = File(dir, "objects").listFiles() ?: return emptyList()
        return objects.filter { it.name.endsWith(".pvo") && it.name.length == 36 }
            .sortedBy { it.lastModified() }
            .map { NativeBridge.fromHex(it.name.removeSuffix(".pvo")) }
    }

    // Phase 2: media items.

    // Non-media fields of the item header, as captured.
    data class ItemMeta(
        val mediaType: Int, val rotation: Int, val width: Int, val height: Int, val createdMs: Long
    ) {
        fun fields(): LongArray =
            longArrayOf(mediaType.toLong(), rotation.toLong(), width.toLong(), height.toLong(), createdMs)
    }

    // Which path the bytes took into the core.
    enum class BufferPath { DIRECT, HEAP_ARRAY, HEAP_COPY }

    class Stored(val id: ByteArray, val plaintextLen: Long, val path: BufferPath)

    // Encrypts the bytes between position and limit of buffer as an item.
    // Direct buffers are read in place. A heap buffer with an accessible array
    // is handed over as that array (made by CameraX, not by this app); any
    // other buffer is copied once into a ByteArray, which the caller must
    // treat as a Kotlin copy of capture bytes. The buffer position is unchanged.
    fun encryptItem(buffer: ByteBuffer, meta: ItemMeta): Stored {
        val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
        val len = LongArray(1)
        val length = buffer.remaining()
        val path = when {
            buffer.isDirect -> BufferPath.DIRECT
            buffer.hasArray() -> BufferPath.HEAP_ARRAY
            else -> BufferPath.HEAP_COPY
        }
        val rc: Int = withHandle("encrypt item") { h ->
            if (path == BufferPath.DIRECT) {
                NativeBridge.nativeEncryptItem(h, buffer, buffer.position(), length, meta.fields(), id, len)
            } else if (path == BufferPath.HEAP_ARRAY) {
                NativeBridge.nativeEncryptItemArray(
                    h, buffer.array(), buffer.arrayOffset() + buffer.position(), length, meta.fields(), id, len
                )
            } else {
                val copy = ByteArray(length)
                buffer.duplicate().get(copy)
                try {
                    NativeBridge.nativeEncryptItemArray(h, copy, 0, length, meta.fields(), id, len)
                } finally {
                    copy.fill(0)
                }
            }
        }
        check(rc, "encrypt item")
        return Stored(id, len[0], path)
    }

    fun encryptItemArray(data: ByteArray, offset: Int, length: Int, meta: ItemMeta): Stored {
        val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
        val len = LongArray(1)
        withHandle("encrypt item") { h ->
            check(NativeBridge.nativeEncryptItemArray(h, data, offset, length, meta.fields(), id, len), "encrypt item")
        }
        return Stored(id, len[0], BufferPath.HEAP_ARRAY)
    }

    data class ItemInfo(
        val mediaType: Int, val rotation: Int, val width: Int, val height: Int,
        val createdMs: Long, val payloadLen: Long, val headerLen: Int
    ) {
        fun typeName(): String = when (mediaType) {
            NativeBridge.MEDIA_JPEG -> "jpeg"
            NativeBridge.MEDIA_VIDEO_SEGMENT -> "video_segment"
            NativeBridge.MEDIA_VIDEO_INDEX -> "video_index"
            else -> "type_$mediaType"
        }
    }

    // Result of an item verify: the object decrypted and authenticated (rc OK);
    // item is set when the plaintext carried a valid header, in which case
    // payloadHash is the BLAKE2b of the payload. itemStatus tells unknown
    // items (ERR_NOT_ITEM) from malformed headers.
    class ItemVerified(
        val plaintextLen: Long, val itemStatus: Int, val item: ItemInfo?, val payloadHash: ByteArray?
    )

    fun decryptVerifyItem(id: ByteArray): Pair<Int, ItemVerified?> {
        val hash = ByteArray(NativeBridge.HASH_BYTES)
        val info = LongArray(8)
        val status = IntArray(1)
        val rc = try {
            withHandle("decrypt item") { h -> NativeBridge.nativeDecryptVerifyItem(h, id, hash, info, status) }
        } catch (e: CoreException) {
            e.code
        }
        if (rc != NativeBridge.OK) return rc to null
        val item = if (status[0] == NativeBridge.OK) ItemInfo(
            info[0].toInt(), info[1].toInt(), info[2].toInt(), info[3].toInt(), info[4], info[5], info[6].toInt()
        ) else null
        return rc to ItemVerified(info[7], status[0], item, if (item != null) hash else null)
    }
}
