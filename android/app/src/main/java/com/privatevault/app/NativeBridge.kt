package com.privatevault.app

import java.nio.ByteBuffer

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
    const val ERR_ITEM_MALFORMED = 21
    const val ERR_ITEM_UNSUPPORTED = 22
    // Classification, not corruption: the plaintext has no item header.
    const val ERR_NOT_ITEM = 23
    // A provider 3 envelope with no slot this reader can use.
    const val ERR_ENVELOPE_NO_SLOT = 24
    // Viewer codes (Phase 3B).
    const val ERR_BUFFER_TOO_SMALL = 25
    const val ERR_CANCELLED = 26
    // Video index codes (Phase 3C). NOT_INDEX is a classification: no magic.
    const val ERR_INDEX_MALFORMED = 27
    const val ERR_INDEX_UNSUPPORTED = 28
    const val ERR_NOT_INDEX = 29

    const val VMK_BYTES = 32
    const val OBJECT_ID_BYTES = 16
    const val HASH_BYTES = 32
    const val SEED_BYTES = 32

    const val MEDIA_JPEG = 1
    const val MEDIA_VIDEO_SEGMENT = 2
    const val MEDIA_VIDEO_INDEX = 3
    const val ITEM_HEADER_BYTES = 48

    // Video index v1 (docs/PHASE3C_DESIGN.md Section 4.2).
    const val VIDEO_INDEX_VERSION = 1
    const val VIDEO_INDEX_HEADER_BYTES = 64
    const val VIDEO_INDEX_SEGMENT_BYTES = 48
    const val VIDEO_INDEX_MAX_SEGMENTS = 16384
    const val VIDEO_INDEX_MAX_KEYS = 4096
    const val VIDEO_INDEX_MAX_BYTES = 8 shl 20
    const val VIDEO_CODEC_H264 = 1
    const val VIDEO_CODEC_H265 = 2
    const val AUDIO_CODEC_NONE = 0
    const val AUDIO_CODEC_AAC = 1

    const val PIN_SALT_BYTES = 16
    const val KEK_BYTES = 32
    const val PIN_OPSLIMIT_MIN = 2L
    const val PIN_OPSLIMIT_MAX = 3L
    const val PIN_MEMLIMIT_MIN = 64L shl 20
    const val PIN_MEMLIMIT_MAX = 256L shl 20
    // Written into new envelopes (docs/PHASE3A_DESIGN.md Section 3.4, CP-2).
    const val PIN_OPSLIMIT_DEFAULT = 2L
    const val PIN_MEMLIMIT_DEFAULT = 256L shl 20
    const val ENVELOPE_PROVIDER_PIN = 3
    const val MECHANISM_ANDROID_KEYSTORE_HMAC = 1
    const val ENVELOPE_ALIAS_MAX = 64
    const val ENVELOPE3_MAX_BYTES = 4096

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
    external fun nativeEncryptItemSampleFile(
        path: String, vmk: ByteArray, seed: ByteArray, payloadLen: Long, fields: LongArray,
        idOut: ByteArray
    ): Int

    external fun nativeFileWriteAtomic(path: String, data: ByteArray): Int
    external fun nativeFileReadAll(path: String): ByteArray?
    external fun nativeFileDelete(path: String): Int
    external fun nativeProbeLink(dir: String): Int

    // Phase 3A. Argon2id over the PIN bytes into a 32-byte key. Parameters
    // outside the core range give ERR_ENVELOPE_KDF_PARAMS before any memory
    // is requested. The caller wipes pin and out.
    external fun nativePinStretch(
        pin: ByteArray, salt: ByteArray, opslimit: Long, memlimit: Long, out: ByteArray
    ): Int
    external fun nativePinSaltGenerate(out: ByteArray): Int
    // Wrong-PIN delay arithmetic (docs/PHASE3A_DESIGN.md Section 8).
    external fun nativePinDelayMs(failures: Int): Long
    external fun nativePinRemainingMs(
        delayMs: Long, failElapsedMs: Long, nowElapsedMs: Long, failWallMs: Long, nowWallMs: Long,
        bootChanged: Boolean
    ): Long
    // Envelope 3. infoOut = [version, provider, slot_count, pin_slot_count,
    // unknown_slot_count, pin_slot_index, mechanism, opslimit, memlimit].
    external fun nativeEnvelope3Parse(
        envelope: ByteArray, infoOut: LongArray, saltOut: ByteArray, aliasOut: Array<String?>
    ): Int
    // Vault forms: the VMK stays inside the core. Status returned, handle in handleOut.
    external fun nativeVaultCreateEnvelope3(
        dir: String, mechanism: Int, alias: String, opslimit: Long, memlimit: Long,
        salt: ByteArray, kek: ByteArray, handleOut: LongArray
    ): Int
    external fun nativeVaultOpenEnvelope3(dir: String, kek: ByteArray, handleOut: LongArray): Int
    external fun nativeVaultWriteEnvelope3(
        handle: Long, mechanism: Int, alias: String, opslimit: Long, memlimit: Long,
        salt: ByteArray, kek: ByteArray
    ): Int
    external fun nativeVaultDestroyFiles(dir: String): Int
    // Makes every encrypt in flight fail at its next read while set.
    external fun nativeSetEncryptCancel(cancel: Boolean)

    // Phase 3B viewer (docs/PHASE3B_DESIGN.md Section 8). Status returned.
    external fun nativeObjectPlaintextSize(handle: Long, id: ByteArray, out: LongArray): Int
    // infoOut = [item_status, media_type, rotation, width, height, created_unix_ms,
    // payload_len, header_len, plaintext_len]; bufferOut[0] is a direct buffer over the
    // whole plaintext for an item of a known media type (JPEG, video segment, video
    // index; Phase 3C widened it from JPEG), to be given back to nativeViewerFree.
    external fun nativeViewerLoad(
        handle: Long, id: ByteArray, cap: Long, infoOut: LongArray, bufferOut: Array<ByteBuffer?>
    ): Int
    external fun nativeViewerFree(buffer: ByteBuffer)
    // The flag pv_vault_decrypt_to_buffer polls; Vault.close sets and clears it.
    external fun nativeSetViewerCancel(cancel: Boolean)
    external fun nativeDeleteObject(handle: Long, id: ByteArray): Int

    // Phase 3C video index (docs/PHASE3C_DESIGN.md Section 4.4). The header is a
    // LongArray of 13: [version, header_len, video_codec, rotation, width, height,
    // frame_rate_millihz, segment_count, total_duration_us, created_unix_ms,
    // audio_codec, audio_channels, audio_sample_rate]. Segments cross as a flat
    // ByteArray of IDs and a LongArray of 5 per segment: [start_us, duration_us,
    // byte_len, key_count, flags]; key offsets as one IntArray in segment order.
    // Encode returns the bytes or null, with the status in statusOut[0].
    external fun nativeVideoIndexEncode(
        header: LongArray, ids: ByteArray, segFields: LongArray, keys: IntArray, statusOut: IntArray
    ): ByteArray?
    external fun nativeVideoIndexDecodeHeader(
        buf: ByteArray, offset: Int, length: Int, headerOut: LongArray, keysTotalOut: LongArray
    ): Int
    // countsOut = [segments, keys]. Output arrays are sized from a decode_header.
    external fun nativeVideoIndexDecode(
        buf: ByteArray, offset: Int, length: Int, headerOut: LongArray, idsOut: ByteArray,
        segFieldsOut: LongArray, keysOut: IntArray, countsOut: LongArray
    ): Int
    // out = [segment, key, key_time_us].
    external fun nativeVideoIndexLocate(
        header: LongArray, segFields: LongArray, keys: IntArray, timeUs: Long, out: LongArray
    ): Int

    // Phase 2. fields = [media_type, rotation_degrees, width, height, created_unix_ms].
    // Direct buffer: the native side reads the bytes in place.
    external fun nativeEncryptItem(
        handle: Long, buffer: ByteBuffer, offset: Int, length: Int, fields: LongArray,
        idOut: ByteArray, lenOut: LongArray
    ): Int
    // Heap array: one native copy into guarded memory, then the same path.
    external fun nativeEncryptItemArray(
        handle: Long, data: ByteArray, offset: Int, length: Int, fields: LongArray,
        idOut: ByteArray, lenOut: LongArray
    ): Int
    // Returns the decrypt status. itemStatus[0] is the item verdict (OK, ERR_NOT_ITEM, ...).
    // infoOut = [media_type, rotation, width, height, created_unix_ms, payload_len,
    // header_len, plaintext_len]. hashOut is the BLAKE2b of the payload when the
    // verdict is OK.
    external fun nativeDecryptVerifyItem(
        handle: Long, id: ByteArray, hashOut: ByteArray, infoOut: LongArray, itemStatus: IntArray
    ): Int
    external fun nativeDecryptFileItem(
        path: String, vmk: ByteArray, expectedId: ByteArray?, hashOut: ByteArray,
        infoOut: LongArray, itemStatus: IntArray
    ): Int

    // Debug variant only (PV_DEBUG_HOOKS). Missing from the release library.
    external fun nativeDecryptToArray(handle: Long, id: ByteArray, maxLen: Int, statusOut: IntArray): ByteArray?
    external fun nativeHashDirectBuffer(buffer: ByteBuffer, offset: Int, length: Int, hashOut: ByteArray): Int
    external fun nativeSetTestHooks(slowReadMs: Int, failAfterBytes: Long)
    // Debug variant only: while set, nativePinStretch reports the allocation
    // failure code (ERR_OUT_OF_MEMORY) without running the KDF.
    external fun nativeSetPinStretchFailure(fail: Boolean)
    // Debug variant only: Stage 3B viewer probes. out = [alloc_ok, mlock_errno,
    // rlimit_cur, rlimit_max, alloc_ms, free_ms]. The direct buffer wraps
    // sodium_malloc memory holding the whole plaintext and must be given back
    // to nativeProbeFreeDirect, which wipes it.
    external fun nativeProbeSecureAlloc(size: Long, out: LongArray): Int
    external fun nativeProbeDecryptDirect(handle: Long, id: ByteArray, length: Long, statusOut: IntArray): ByteBuffer?
    external fun nativeProbeFreeDirect(buffer: ByteBuffer)
    // Debug variant only: viewer loads report the allocation failure code while
    // allocFail is set; each viewer decrypt starts slowMs late.
    external fun nativeSetViewerHooks(allocFail: Boolean, slowMs: Int)

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
