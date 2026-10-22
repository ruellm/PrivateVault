package com.privatevault.app

import java.nio.ByteBuffer

// One video segment assembled by the core writer inside guarded native memory
// (docs/PHASE3E_DESIGN.md Section 4.4). Encoder output buffers go in by
// reference; the finished file image is encrypted from the same buffer. The
// buffer is wiped on close, which happens exactly once on every path.
class SegmentWriter private constructor(private var handle: Long) : AutoCloseable {

    // The AAC track of a segment: channels, sample rate, bitrate, and the
    // AudioSpecificConfig the encoder reports (csd-0). stageBytes 0 takes
    // the default stage.
    class AudioFormat(val channels: Int, val sampleRate: Int, val bitrate: Int, val config: ByteArray, val stageBytes: Long = 0)

    class AudioStats(val samples: Int, val firstTick: Long)

    companion object {
        fun open(width: Int, height: Int, rotation: Int, sps: ByteBuffer, pps: ByteBuffer, cap: Long): SegmentWriter =
            openAv(width, height, rotation, sps, pps, null, cap)

        fun openAv(width: Int, height: Int, rotation: Int, sps: ByteBuffer, pps: ByteBuffer, audio: AudioFormat?, cap: Long): SegmentWriter {
            val out = LongArray(1)
            val rc = NativeBridge.nativeSegmentOpenAv(
                width, height, rotation, bytes(sps), bytes(pps),
                audio?.channels ?: 0, audio?.sampleRate ?: 0, audio?.bitrate ?: 0, audio?.config, audio?.stageBytes ?: 0, cap, out
            )
            if (rc != NativeBridge.OK || out[0] == 0L) throw Vault.CoreException(rc, "segment open")
            return SegmentWriter(out[0])
        }

        private fun bytes(b: ByteBuffer): ByteArray {
            val d = b.duplicate()
            val a = ByteArray(d.remaining())
            d.get(a)
            return a
        }
    }

    class Stats(val bytes: Long, val samples: Int, val fragments: Int)

    val isOpen: Boolean get() = handle != 0L

    private fun require(): Long {
        val h = handle
        if (h == 0L) throw Vault.CoreException(NativeBridge.ERR_SEGMENT_STATE, "segment: closed")
        return h
    }

    // Appends the bytes between position and limit of a direct buffer with
    // their time from the segment start. Returns the core status.
    fun add(buffer: ByteBuffer, ptsUs: Long, key: Boolean): Int {
        if (!buffer.isDirect) return NativeBridge.ERR_INVALID_ARGUMENT
        return NativeBridge.nativeSegmentAdd(require(), buffer, buffer.position(), buffer.remaining(), ptsUs, key)
    }

    // Appends one AAC frame between position and limit of a direct buffer
    // with its time from the segment start. Returns the core status.
    fun addAudio(buffer: ByteBuffer, ptsUs: Long): Int {
        if (!buffer.isDirect) return NativeBridge.ERR_INVALID_ARGUMENT
        return NativeBridge.nativeSegmentAddAudio(require(), buffer, buffer.position(), buffer.remaining(), ptsUs)
    }

    fun audioStats(): AudioStats {
        val out = LongArray(2)
        val rc = NativeBridge.nativeSegmentAudioStats(require(), out)
        if (rc != NativeBridge.OK) throw Vault.CoreException(rc, "segment audio stats")
        return AudioStats(out[0].toInt(), out[1])
    }

    internal fun rawHandle(): Long = require()

    fun finish(endUs: Long): Int = NativeBridge.nativeSegmentFinish(require(), endUs)

    fun stats(): Stats {
        val out = LongArray(3)
        val rc = NativeBridge.nativeSegmentStats(require(), out)
        if (rc != NativeBridge.OK) throw Vault.CoreException(rc, "segment stats")
        return Stats(out[0], out[1].toInt(), out[2].toInt())
    }

    // Encrypts the finished segment as a video segment item. The vault guard
    // is held through the call, as for every other encrypt.
    fun encrypt(vault: Vault, meta: Vault.ItemMeta): Vault.Stored = vault.encryptSegment(require(), meta)

    // Debug variant only: a copy of the finished segment for the box checks.
    fun copy(): ByteArray? = if (BuildConfig.DEBUG) NativeBridge.nativeSegmentCopy(require()) else null

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeBridge.nativeSegmentClose(h)
    }
}
