package com.privatevault.app

import java.nio.ByteBuffer

// Video index v1 of one clip (docs/PHASE3C_DESIGN.md Section 4): the header,
// one record per segment object, and the key-frame offsets of each segment.
// The bytes are made and checked by the core; this class holds the fields
// and shapes the arrays that cross the bridge. It carries times, sizes, and
// object IDs only, never sample data.
class VideoIndex(val header: Header, val segments: List<Segment>) {

    data class Header(
        val videoCodec: Int,
        val rotation: Int,
        val width: Int,
        val height: Int,
        val frameRateMillihz: Int,
        val totalDurationUs: Long,
        val createdMs: Long,
        val audioCodec: Int = NativeBridge.AUDIO_CODEC_NONE,
        val audioChannels: Int = 0,
        val audioSampleRate: Int = 0
    )

    class Segment(
        val id: ByteArray,
        val startUs: Long,
        val durationUs: Long,
        val byteLen: Long,
        val keyOffsetsUs: IntArray,
        val flags: Int = 0
    ) {
        val endUs: Long get() = startUs + durationUs
        val hex: String get() = NativeBridge.hex(id)
    }

    // Result of the seek rule (Section 4.3): the segment, the key frame in
    // it, and that key frame's clip time.
    class Location(val segment: Int, val key: Int, val keyTimeUs: Long)

    class Decoded(val status: Int, val index: VideoIndex?)

    val keysTotal: Int get() = segments.sumOf { it.keyOffsetsUs.size }

    private fun headerArray(): LongArray = longArrayOf(
        NativeBridge.VIDEO_INDEX_VERSION.toLong(), NativeBridge.VIDEO_INDEX_HEADER_BYTES.toLong(),
        header.videoCodec.toLong(), header.rotation.toLong(), header.width.toLong(), header.height.toLong(),
        header.frameRateMillihz.toLong(), segments.size.toLong(), header.totalDurationUs, header.createdMs,
        header.audioCodec.toLong(), header.audioChannels.toLong(), header.audioSampleRate.toLong()
    )

    private fun idArray(): ByteArray {
        val out = ByteArray(segments.size * NativeBridge.OBJECT_ID_BYTES)
        for ((i, s) in segments.withIndex()) {
            require(s.id.size == NativeBridge.OBJECT_ID_BYTES)
            System.arraycopy(s.id, 0, out, i * NativeBridge.OBJECT_ID_BYTES, NativeBridge.OBJECT_ID_BYTES)
        }
        return out
    }

    private fun fieldArray(): LongArray {
        val out = LongArray(segments.size * 5)
        for ((i, s) in segments.withIndex()) {
            out[i * 5] = s.startUs
            out[i * 5 + 1] = s.durationUs
            out[i * 5 + 2] = s.byteLen
            out[i * 5 + 3] = s.keyOffsetsUs.size.toLong()
            out[i * 5 + 4] = s.flags.toLong()
        }
        return out
    }

    private fun keyArray(): IntArray {
        val out = IntArray(keysTotal)
        var n = 0
        for (s in segments) {
            System.arraycopy(s.keyOffsetsUs, 0, out, n, s.keyOffsetsUs.size)
            n += s.keyOffsetsUs.size
        }
        return out
    }

    // The index bytes. A rule violation comes back as a CoreException with
    // the core status.
    fun encode(): ByteArray {
        val status = IntArray(1)
        val bytes = NativeBridge.nativeVideoIndexEncode(headerArray(), idArray(), fieldArray(), keyArray(), status)
        if (bytes == null || status[0] != NativeBridge.OK) throw Vault.CoreException(status[0], "video index encode")
        return bytes
    }

    fun locate(timeUs: Long): Location {
        val out = LongArray(3)
        val rc = NativeBridge.nativeVideoIndexLocate(headerArray(), fieldArray(), keyArray(), timeUs, out)
        if (rc != NativeBridge.OK) throw Vault.CoreException(rc, "video index locate")
        return Location(out[0].toInt(), out[1].toInt(), out[2])
    }

    companion object {
        // Plaintext cap for an index object: the index bound plus the item header.
        const val OBJECT_CAP = (NativeBridge.VIDEO_INDEX_MAX_BYTES + NativeBridge.ITEM_HEADER_BYTES).toLong()

        fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Decoded {
            val h = LongArray(13)
            val keysTotal = LongArray(1)
            val rc = NativeBridge.nativeVideoIndexDecodeHeader(bytes, offset, length, h, keysTotal)
            if (rc != NativeBridge.OK) return Decoded(rc, null)
            val count = h[7].toInt()
            val ids = ByteArray(count * NativeBridge.OBJECT_ID_BYTES)
            val fields = LongArray(count * 5)
            val keys = IntArray(keysTotal[0].toInt())
            val counts = LongArray(2)
            val rc2 = NativeBridge.nativeVideoIndexDecode(bytes, offset, length, h, ids, fields, keys, counts)
            if (rc2 != NativeBridge.OK) return Decoded(rc2, null)
            val segments = ArrayList<Segment>(count)
            var k = 0
            for (i in 0 until counts[0].toInt()) {
                val keyCount = fields[i * 5 + 3].toInt()
                segments.add(
                    Segment(
                        ids.copyOfRange(i * NativeBridge.OBJECT_ID_BYTES, (i + 1) * NativeBridge.OBJECT_ID_BYTES),
                        fields[i * 5], fields[i * 5 + 1], fields[i * 5 + 2],
                        keys.copyOfRange(k, k + keyCount), fields[i * 5 + 4].toInt()
                    )
                )
                k += keyCount
            }
            val header = Header(
                h[2].toInt(), h[3].toInt(), h[4].toInt(), h[5].toInt(), h[6].toInt(), h[8], h[9],
                h[10].toInt(), h[11].toInt(), h[12].toInt()
            )
            return Decoded(NativeBridge.OK, VideoIndex(header, segments))
        }

        // Decodes from a payload view of a viewer load. The bytes are copied
        // for the bridge call and wiped after it.
        fun decode(payload: ByteBuffer): Decoded {
            val copy = ByteArray(payload.remaining())
            payload.duplicate().get(copy)
            try {
                return decode(copy)
            } finally {
                copy.fill(0)
            }
        }

        // Loads an index object through the viewer pipeline. The status is the
        // decrypt status, the item status, or the index status, whichever
        // failed first; ERR_NOT_INDEX for an item of another media type.
        fun read(vault: Vault, id: ByteArray): Decoded {
            val load = vault.viewerLoad(id, OBJECT_CAP)
            if (load.status != NativeBridge.OK) return Decoded(load.status, null)
            load.use { l ->
                if (l.itemStatus != NativeBridge.OK) return Decoded(l.itemStatus, null)
                val item = l.item ?: return Decoded(NativeBridge.ERR_NOT_ITEM, null)
                if (item.mediaType != NativeBridge.MEDIA_VIDEO_INDEX) return Decoded(NativeBridge.ERR_NOT_INDEX, null)
                val payload = l.payload ?: return Decoded(NativeBridge.ERR_INTERNAL, null)
                return decode(payload)
            }
        }
    }
}
