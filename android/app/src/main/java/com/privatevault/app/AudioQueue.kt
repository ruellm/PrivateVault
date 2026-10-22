package com.privatevault.app

import java.nio.ByteBuffer

// Encoded audio frames waiting in guarded native memory until the video
// has reached their time (docs/PHASE3F_DESIGN.md Section 4.2). Frames go in
// from the encoder output buffer by reference and come out into a segment
// writer timed from that segment's start. Not thread safe: the recorder
// holds its own lock around every call. The buffer is wiped on close.
class AudioQueue private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        fun open(cap: Long = NativeBridge.AUDIO_QUEUE_CAP): AudioQueue {
            val out = LongArray(1)
            val rc = NativeBridge.nativeAudioQueueOpen(cap, out)
            if (rc != NativeBridge.OK || out[0] == 0L) throw Vault.CoreException(rc, "audio queue open")
            return AudioQueue(out[0])
        }
    }

    class Moved(val status: Int, val moved: Int, val clamped: Int)

    class Stats(val bytes: Long, val frames: Int, val headPtsUs: Long)

    private fun require(): Long {
        val h = handle
        if (h == 0L) throw Vault.CoreException(NativeBridge.ERR_SEGMENT_STATE, "audio queue: closed")
        return h
    }

    // Pushes the bytes between position and limit of a direct buffer with
    // their time. Returns the core status.
    fun push(buffer: ByteBuffer, ptsUs: Long): Int {
        if (!buffer.isDirect) return NativeBridge.ERR_INVALID_ARGUMENT
        return NativeBridge.nativeAudioQueuePush(require(), buffer, buffer.position(), buffer.remaining(), ptsUs)
    }

    // Drops the frames below the time; returns how many.
    fun discardBefore(ptsUs: Long): Int {
        val out = LongArray(1)
        val rc = NativeBridge.nativeAudioQueueDiscardBefore(require(), ptsUs, out)
        if (rc != NativeBridge.OK) throw Vault.CoreException(rc, "audio queue discard")
        return out[0].toInt()
    }

    fun move(writer: SegmentWriter, baseUs: Long, untilUs: Long): Moved {
        val out = LongArray(2)
        val rc = NativeBridge.nativeAudioQueueMove(require(), writer.rawHandle(), baseUs, untilUs, out)
        return Moved(rc, out[0].toInt(), out[1].toInt())
    }

    fun stats(): Stats {
        val out = LongArray(3)
        val rc = NativeBridge.nativeAudioQueueStats(require(), out)
        if (rc != NativeBridge.OK) throw Vault.CoreException(rc, "audio queue stats")
        return Stats(out[0], out[1].toInt(), out[2])
    }

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeBridge.nativeAudioQueueClose(h)
    }
}
