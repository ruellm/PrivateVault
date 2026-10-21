package com.privatevault.app

import java.nio.ByteBuffer

// One video segment assembled by the core writer inside guarded native memory
// (docs/PHASE3E_DESIGN.md Section 4.4). Encoder output buffers go in by
// reference; the finished file image is encrypted from the same buffer. The
// buffer is wiped on close, which happens exactly once on every path.
class SegmentWriter private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        fun open(width: Int, height: Int, rotation: Int, sps: ByteBuffer, pps: ByteBuffer, cap: Long): SegmentWriter {
            val out = LongArray(1)
            val rc = NativeBridge.nativeSegmentOpen(width, height, rotation, bytes(sps), bytes(pps), cap, out)
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
