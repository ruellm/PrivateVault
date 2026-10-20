package com.privatevault.app

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

// Video clips in the gallery and the vault (docs/PHASE3D_DESIGN.md Sections
// 4.4 to 4.6): the kind of tile an object gets, the checks on the segments
// of a clip, the first-frame thumbnail, delete in index-first order, and
// the sweep that removes segment objects no index names. Lines carry object
// IDs, counts, status names, and times only.
object VideoItems {

    const val TAG = "PrivateVault"

    // Held by a recording from its first segment to its index write, and by
    // the sweep for its run, so neither sees the other half done.
    val recordingGate = ReentrantLock()

    sealed class Kind {
        object Photo : Kind()
        class Video(val index: VideoIndex) : Kind()
        // video is set when the object is an index whose clip cannot play.
        class Damaged(val reason: String, val video: Boolean = false) : Kind()
        class Unknown(val reason: String) : Kind()
        object Large : Kind()
        object Segment : Kind()
        object NoMemory : Kind()
        object Cancelled : Kind()

        // Tile text; carries no content.
        fun label(): String = when (this) {
            Photo -> "photo"
            is Video -> "video"
            is Damaged -> "damaged: $reason"
            is Unknown -> "unknown item"
            Large -> "large item"
            Segment -> "segment"
            NoMemory -> "out of memory"
            Cancelled -> "cancelled"
        }
    }

    // Tells the kind of one object from its size and its item header, read
    // from the first chunk. An index object is decoded and its segments are
    // checked by length; nothing else is decrypted here.
    fun classify(vault: Vault, id: ByteArray): Kind {
        val size = try {
            vault.plaintextSize(id)
        } catch (e: Vault.CoreException) {
            return if (!vault.isOpen) Kind.Cancelled else Kind.Damaged(NativeBridge.nativeErrorName(e.code))
        }
        if (size > PhotoLoader.SIZE_CAP) return Kind.Large
        if (size == 0L) return Kind.Unknown("empty")
        val peek = vault.itemHeader(id)
        when (peek.status) {
            NativeBridge.OK -> {}
            NativeBridge.ERR_NOT_ITEM, NativeBridge.ERR_ITEM_MALFORMED, NativeBridge.ERR_ITEM_UNSUPPORTED ->
                return Kind.Unknown(NativeBridge.nativeErrorName(peek.status))
            NativeBridge.ERR_CANCELLED -> return Kind.Cancelled
            NativeBridge.ERR_OUT_OF_MEMORY -> return Kind.NoMemory
            else -> return Kind.Damaged(NativeBridge.nativeErrorName(peek.status))
        }
        val item = peek.item ?: return Kind.Unknown("no header")
        return when (item.mediaType) {
            NativeBridge.MEDIA_JPEG -> Kind.Photo
            NativeBridge.MEDIA_VIDEO_SEGMENT -> Kind.Segment
            NativeBridge.MEDIA_VIDEO_INDEX -> indexKind(vault, id)
            else -> Kind.Unknown(item.typeName())
        }
    }

    private fun indexKind(vault: Vault, id: ByteArray): Kind {
        val d = VideoIndex.read(vault, id)
        val idx = d.index
        if (d.status != NativeBridge.OK || idx == null) {
            return when (d.status) {
                NativeBridge.ERR_NOT_INDEX -> Kind.Unknown("not an index")
                NativeBridge.ERR_CANCELLED -> Kind.Cancelled
                NativeBridge.ERR_OUT_OF_MEMORY -> Kind.NoMemory
                else -> Kind.Damaged(NativeBridge.nativeErrorName(d.status), video = true)
            }
        }
        val reason = checkSegments(vault, idx)
        return if (reason == null) Kind.Video(idx) else Kind.Damaged(reason, video = true)
    }

    // Every segment the index names exists and has the length the index
    // records. Lengths come from the object headers; nothing is decrypted.
    fun checkSegments(vault: Vault, index: VideoIndex): String? {
        for ((n, s) in index.segments.withIndex()) {
            val size = try {
                vault.plaintextSize(s.id)
            } catch (e: Vault.CoreException) {
                return "segment $n ${NativeBridge.nativeErrorName(e.code)}"
            }
            if (size != NativeBridge.ITEM_HEADER_BYTES + s.byteLen) return "segment $n length"
        }
        return null
    }

    class Thumb(val bitmap: Bitmap?, val detail: String)

    // The first key frame of the first segment as a thumbnail, turned by the
    // index rotation; bitmap is null when the segment or the decode fails.
    // The segment buffer is freed once the sample sits in the codec, before
    // the decoder is waited on.
    fun thumbnail(vault: Vault, index: VideoIndex): Thumb {
        if (index.header.videoCodec != NativeBridge.VIDEO_CODEC_H264) return Thumb(null, "codec ${index.header.videoCodec}")
        val seg = index.segments[0]
        val t0 = System.nanoTime()
        val load = vault.viewerLoad(seg.id, PhotoLoader.SIZE_CAP)
        if (load.status != NativeBridge.OK) return Thumb(null, "segment 0 ${NativeBridge.errorName(load.status)}")
        val item = load.item
        val payload = load.payload
        if (load.itemStatus != NativeBridge.OK || item == null || item.mediaType != NativeBridge.MEDIA_VIDEO_SEGMENT || payload == null) {
            load.close()
            return Thumb(null, "segment 0 not a video segment")
        }
        val started = VideoFirstFrame.start(payload)
        load.close()
        val t1 = System.nanoTime()
        val r = if (started is VideoFirstFrame.Session) started.finish() else started as VideoFirstFrame.Result
        val t2 = System.nanoTime()
        val detail = "codec=${r.codec} frame=${r.width}x${r.height} buffer_live_ms=${(t1 - t0) / 1_000_000} " +
            "decode_ms=${r.decodeMs} convert_ms=${r.convertMs} total_ms=${(t2 - t0) / 1_000_000}"
        val b = r.bitmap ?: return Thumb(null, "$detail error=${r.error}")
        return Thumb(PhotoLoader.turned(b, index.header.rotation), detail)
    }

    // m:ss, rounded to the second.
    fun durationLabel(us: Long): String {
        val s = (us + 500_000) / 1_000_000
        return "%d:%02d".format(s / 60, s % 60)
    }

    class DeleteResult(val indexStatus: Int, val segmentStatuses: IntArray) {
        private fun fine(rc: Int) = rc == NativeBridge.OK || rc == NativeBridge.ERR_NOT_FOUND
        val ok: Boolean get() = fine(indexStatus) && segmentStatuses.all { fine(it) }

        fun line(): String {
            val okCount = segmentStatuses.count { it == NativeBridge.OK }
            val missing = segmentStatuses.count { it == NativeBridge.ERR_NOT_FOUND }
            val failed = segmentStatuses.size - okCount - missing
            return "index ${NativeBridge.errorName(indexStatus)}, segments ${segmentStatuses.size}: $okCount deleted, $missing missing, $failed failed"
        }
    }

    // Deletes the index object first, then every segment it names. A
    // missing segment is not a failure. If the index itself cannot be
    // removed, no segment is touched.
    fun delete(vault: Vault, indexId: ByteArray, index: VideoIndex?): DeleteResult {
        val rc = vault.deleteObject(indexId)
        Log.i(TAG, "delete_video ${NativeBridge.hex(indexId)}: index ${NativeBridge.errorName(rc)}")
        if (rc != NativeBridge.OK && rc != NativeBridge.ERR_NOT_FOUND) return DeleteResult(rc, IntArray(0))
        val segs = index?.segments ?: emptyList()
        val statuses = IntArray(segs.size)
        for ((n, s) in segs.withIndex()) {
            statuses[n] = vault.deleteObject(s.id)
            Log.i(TAG, "delete_video ${NativeBridge.hex(indexId)}: segment $n ${s.hex} ${NativeBridge.errorName(statuses[n])}")
        }
        return DeleteResult(rc, statuses)
    }

    class SweepResult(
        val skipped: String?, val objects: Int, val indexes: Int, val segments: Int,
        val deleted: Int, val failed: Int, val ms: Long
    ) {
        fun line(): String =
            if (skipped != null) "skipped ($skipped) after ${ms}ms"
            else "$objects objects, $indexes indexes, $segments segments, $deleted orphans deleted, $failed failed, ${ms}ms"
    }

    // Removes segment objects that no index names. Works over the object
    // list taken at the start; skips while a recording holds the gate, and
    // while any object cannot be read far enough to tell what it is or any
    // index fails to decode (an index with a damaged first chunk looks like
    // any other damaged object, and a damaged index keeps its segments until
    // it is deleted). Photos, unknown items, and indexes are never touched.
    fun sweep(vault: Vault): SweepResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (!recordingGate.tryLock()) return SweepResult("recording active", 0, 0, 0, 0, 0, ms())
        try {
            val entries = vault.listObjects()
            val named = HashSet<String>()
            val segments = ArrayList<Vault.ObjectEntry>()
            var indexes = 0
            for (e in entries) {
                if (!vault.isOpen) return SweepResult("vault locked", entries.size, indexes, segments.size, 0, 0, ms())
                val peek = vault.itemHeader(e.id)
                when (peek.status) {
                    NativeBridge.OK -> {}
                    NativeBridge.ERR_CANCELLED -> return SweepResult("vault locked", entries.size, indexes, segments.size, 0, 0, ms())
                    NativeBridge.ERR_NOT_ITEM, NativeBridge.ERR_ITEM_MALFORMED, NativeBridge.ERR_ITEM_UNSUPPORTED -> continue
                    else -> return SweepResult("object ${e.hex} ${NativeBridge.errorName(peek.status)}", entries.size, indexes, segments.size, 0, 0, ms())
                }
                when (peek.item?.mediaType) {
                    NativeBridge.MEDIA_VIDEO_SEGMENT -> segments.add(e)
                    NativeBridge.MEDIA_VIDEO_INDEX -> {
                        val d = VideoIndex.read(vault, e.id)
                        val idx = d.index
                        if (d.status == NativeBridge.ERR_NOT_INDEX) continue
                        if (d.status != NativeBridge.OK || idx == null) {
                            return SweepResult("index ${e.hex} ${NativeBridge.errorName(d.status)}", entries.size, indexes, segments.size, 0, 0, ms())
                        }
                        indexes++
                        for (s in idx.segments) named.add(s.hex)
                    }
                    else -> {}
                }
            }
            var deleted = 0
            var failed = 0
            for (e in segments) {
                if (e.hex in named) continue
                val rc = vault.deleteObject(e.id)
                Log.i(TAG, "sweep: orphan segment ${e.hex} ${NativeBridge.errorName(rc)}")
                if (rc == NativeBridge.OK || rc == NativeBridge.ERR_NOT_FOUND) deleted++ else failed++
            }
            return SweepResult(null, entries.size, indexes, segments.size, deleted, failed, ms())
        } finally {
            recordingGate.unlock()
        }
    }
}
