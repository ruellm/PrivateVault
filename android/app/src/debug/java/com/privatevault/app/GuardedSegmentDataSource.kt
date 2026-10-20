package com.privatevault.app

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

// Debug-only playback probe pieces (docs/PHASE3C_DESIGN.md Section 5.1).
// A media3 DataSource that serves one segment object of an index from the
// guarded buffer of a viewer load, a seek map built from the segment's own
// fragment table so the player can land on a key frame inside a segment,
// and an extractor wrapper that hands that seek map to the player in place
// of the unseekable one FragmentedMp4Extractor reports for a stream without
// a sidx box.

// The guarded buffers open at this moment, their byte total, and the peak,
// shared by every data source of one player. Tests and the probe read it.
class LiveBuffers(private val say: (String) -> Unit) {
    private val open = HashMap<Int, Int>()
    @Volatile var peakBytes = 0L
        private set
    @Volatile var peakCount = 0
        private set
    @Volatile var opens = 0
        private set
    @Volatile var closes = 0
        private set
    @Volatile var opening = 0
        private set
    @Volatile var lastOpenStatus = NativeBridge.OK
        private set

    val liveBytes: Long
        @Synchronized get() = open.values.sumOf { it.toLong() }
    val liveCount: Int
        @Synchronized get() = open.size

    @Synchronized fun beginOpen() {
        opening++
    }

    @Synchronized fun opened(segment: Int, bytes: Int, decryptMs: Long) {
        opening--
        opens++
        lastOpenStatus = NativeBridge.OK
        open[segment] = bytes
        val total = liveBytes
        peakBytes = maxOf(peakBytes, total)
        peakCount = maxOf(peakCount, open.size)
        say("video_play: segment $segment open decrypt_ms=$decryptMs bytes=$bytes live_buffers=${open.size} live_bytes=$total")
    }

    @Synchronized fun failed(segment: Int, status: Int) {
        opening--
        lastOpenStatus = status
        say("video_play: segment $segment open FAILED ${NativeBridge.errorName(status)}")
    }

    @Synchronized fun closed(segment: Int) {
        if (open.remove(segment) != null) closes++
        say("video_play: segment $segment closed live_buffers=${open.size} live_bytes=$liveBytes")
    }
}

class SegmentOpenException(val segment: Int, val status: Int) :
    IOException("segment $segment: ${NativeBridge.errorName(status)}")

@androidx.annotation.OptIn(UnstableApi::class)
class GuardedSegmentDataSource(
    private val vault: Vault,
    private val index: VideoIndex,
    private val live: LiveBuffers,
    private val seekMaps: ConcurrentHashMap<Int, ChunkIndex>
) : BaseDataSource(false) {

    companion object {
        const val SCHEME = "pv"
        const val SEGMENT_CAP = 32L shl 20

        fun uri(segment: Int): Uri = Uri.parse("$SCHEME://segment/$segment")

        fun segmentOf(uri: Uri): Int {
            require(uri.scheme == SCHEME && uri.host == "segment") { "not a segment uri" }
            return uri.lastPathSegment!!.toInt()
        }
    }

    class Factory(
        private val vault: Vault, private val index: VideoIndex, private val live: LiveBuffers,
        private val seekMaps: ConcurrentHashMap<Int, ChunkIndex>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GuardedSegmentDataSource(vault, index, live, seekMaps)
    }

    private var current: Uri? = null
    private var segment = -1
    private var load: Vault.ViewerLoad? = null
    private var payload: ByteBuffer? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val n = segmentOf(dataSpec.uri)
        if (n < 0 || n >= index.segments.size) throw SegmentOpenException(n, NativeBridge.ERR_NOT_FOUND)
        transferInitializing(dataSpec)
        live.beginOpen()
        val t0 = System.nanoTime()
        val l = vault.viewerLoad(index.segments[n].id, SEGMENT_CAP)
        if (l.status != NativeBridge.OK) {
            live.failed(n, l.status)
            throw SegmentOpenException(n, l.status)
        }
        val item = l.item
        if (l.itemStatus != NativeBridge.OK || item == null || item.mediaType != NativeBridge.MEDIA_VIDEO_SEGMENT) {
            l.close()
            val status = if (l.itemStatus != NativeBridge.OK) l.itemStatus else NativeBridge.ERR_ITEM_UNSUPPORTED
            live.failed(n, status)
            throw SegmentOpenException(n, status)
        }
        val p = l.payload
        if (p == null) {
            l.close()
            live.failed(n, NativeBridge.ERR_INTERNAL)
            throw SegmentOpenException(n, NativeBridge.ERR_INTERNAL)
        }
        if (dataSpec.position > p.remaining()) {
            l.close()
            live.failed(n, NativeBridge.ERR_INVALID_ARGUMENT)
            throw SegmentOpenException(n, NativeBridge.ERR_INVALID_ARGUMENT)
        }
        seekMaps.getOrPut(n) { SegmentSeekMap.build(p, index.segments[n].durationUs) }
        p.position(dataSpec.position.toInt())
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, p.remaining().toLong()) else p.remaining().toLong()
        load = l
        payload = p
        current = dataSpec.uri
        segment = n
        live.opened(n, p.capacity(), (System.nanoTime() - t0) / 1_000_000)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val p = payload ?: return C.RESULT_END_OF_INPUT
        val n = minOf(length.toLong(), remaining).toInt()
        p.get(buffer, offset, n)
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = current

    override fun close() {
        val l = load ?: return
        load = null
        payload = null
        current = null
        l.close()
        live.closed(segment)
        transferEnded()
    }
}

// Builds a media3 ChunkIndex (a seekable SeekMap) from the fragment table of
// a fragmented MP4 held in memory: one entry per moof, with its byte offset,
// its size up to the next moof, and its start time. The media3 muxer writes
// no tfdt, so a fragment's start time is the sum of the sample durations of
// the fragments before it (the way FragmentedMp4Extractor reconstructs it),
// in the track timescale from mdhd. Box types, sizes, and durations only;
// no sample data.
@androidx.annotation.OptIn(UnstableApi::class)
object SegmentSeekMap {
    private const val TAG = "PrivateVault"

    fun build(data: ByteBuffer, segmentDurationUs: Long): ChunkIndex {
        val b = data.duplicate().order(ByteOrder.BIG_ENDIAN)
        val base = b.position()
        val end = b.limit()
        var timescale = 0L
        val offsets = ArrayList<Long>()
        val times = ArrayList<Long>()
        var cumulative = 0L
        var pos = base
        while (pos + 8 <= end) {
            b.position(pos)
            var size = b.int.toLong() and 0xffffffffL
            val type = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                size = b.long
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) break
            when (type) {
                "moov" -> timescale = findTimescale(b, pos + header, (pos + size).toInt())
                "moof" -> {
                    offsets.add((pos - base).toLong())
                    val tfdt = findTfdt(b, pos + header, (pos + size).toInt())
                    if (tfdt != null) cumulative = tfdt
                    times.add(cumulative)
                    cumulative += fragmentDuration(b, pos + header, (pos + size).toInt())
                }
            }
            pos = (pos + size).toInt()
        }
        if (timescale <= 0L || offsets.isEmpty()) {
            Log.i(TAG, "video_play: seek map: no fragment table (timescale=$timescale moofs=${offsets.size})")
            return ChunkIndex(intArrayOf(end - base), longArrayOf(0), longArrayOf(segmentDurationUs), longArrayOf(0))
        }
        val n = offsets.size
        val sizes = IntArray(n)
        val offs = LongArray(n)
        val durations = LongArray(n)
        val timesUs = LongArray(n)
        for (i in 0 until n) {
            offs[i] = offsets[i]
            val next = if (i + 1 < n) offsets[i + 1] else (end - base).toLong()
            sizes[i] = (next - offsets[i]).toInt()
            timesUs[i] = times[i] * 1_000_000L / timescale
        }
        for (i in 0 until n) {
            durations[i] = (if (i + 1 < n) timesUs[i + 1] else segmentDurationUs) - timesUs[i]
            if (durations[i] < 0) durations[i] = 0
        }
        return ChunkIndex(sizes, offs, durations, timesUs)
    }

    // moov/trak/mdia/mdhd timescale.
    private fun findTimescale(b: ByteBuffer, start: Int, end: Int): Long {
        val mdia = find(b, start, end, listOf("trak", "mdia")) ?: return 0
        val mdhd = findBox(b, mdia.first, mdia.second, "mdhd") ?: return 0
        b.position(mdhd.first)
        val version = b.get().toInt() and 0xff
        b.position(mdhd.first + 4 + (if (version == 1) 16 else 8))
        return b.int.toLong() and 0xffffffffL
    }

    // moof/traf/tfdt baseMediaDecodeTime, or null when the box is absent.
    private fun findTfdt(b: ByteBuffer, start: Int, end: Int): Long? {
        val traf = findBox(b, start, end, "traf") ?: return null
        val tfdt = findBox(b, traf.first, traf.second, "tfdt") ?: return null
        b.position(tfdt.first)
        val version = b.get().toInt() and 0xff
        b.position(tfdt.first + 4)
        return if (version == 1) b.long else (b.int.toLong() and 0xffffffffL)
    }

    // Sum of the sample durations of the first trun of moof/traf, or 0 when
    // the trun carries no per-sample durations.
    private fun fragmentDuration(b: ByteBuffer, start: Int, end: Int): Long {
        val traf = findBox(b, start, end, "traf") ?: return 0
        val trun = findBox(b, traf.first, traf.second, "trun") ?: return 0
        b.position(trun.first)
        val flags = b.int and 0xffffff
        val count = b.int
        if (flags and 0x1 != 0) b.int
        if (flags and 0x4 != 0) b.int
        if (flags and 0x100 == 0) return 0
        var sum = 0L
        for (i in 0 until count) {
            sum += b.int.toLong() and 0xffffffffL
            if (flags and 0x200 != 0) b.int
            if (flags and 0x400 != 0) b.int
            if (flags and 0x800 != 0) b.int
        }
        return sum
    }

    private fun find(b: ByteBuffer, start: Int, end: Int, path: List<String>): Pair<Int, Int>? {
        var range = start to end
        for (name in path) range = findBox(b, range.first, range.second, name) ?: return null
        return range
    }

    // Body range of the first child box of the given type.
    private fun findBox(b: ByteBuffer, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            b.position(pos)
            var size = b.int.toLong() and 0xffffffffL
            val t = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                size = b.long
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) return null
            if (t == type) return (pos + header) to (pos + size).toInt()
            pos = (pos + size).toInt()
        }
        return null
    }
}

// FragmentedMp4Extractor with the seek map of the segment put in place of
// the unseekable one it reports, so seeks inside a segment start the read at
// the fragment holding the key frame. After a seek to a fragment offset the
// extractor, finding no tfdt, numbers that fragment's samples from zero;
// the wrapper adds the fragment's start time back to every sample time.
@androidx.annotation.OptIn(UnstableApi::class)
class SeekableSegmentExtractor(private val seekMap: ChunkIndex?) : Extractor {
    private val inner = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
    @Volatile private var timeOffsetUs = 0L

    private inner class ShiftedTrack(private val out: TrackOutput) : TrackOutput {
        override fun format(format: Format) = out.format(format)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            out.sampleData(data, length, sampleDataPart)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            out.sampleMetadata(timeUs + timeOffsetUs, flags, size, offset, cryptoData)
    }

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = ShiftedTrack(output.track(id, type))
            override fun endTracks() = output.endTracks()
            override fun seekMap(map: SeekMap) = output.seekMap(seekMap ?: map)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        var offset = 0L
        val map = seekMap
        if (map != null && position > 0) {
            for (i in map.offsets.indices) if (map.offsets[i] == position) offset = map.timesUs[i]
        }
        timeOffsetUs = offset
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    class Factory(private val seekMaps: ConcurrentHashMap<Int, ChunkIndex>) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = arrayOf(SeekableSegmentExtractor(null))

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            val map = try {
                seekMaps[GuardedSegmentDataSource.segmentOf(uri)]
            } catch (e: IllegalArgumentException) {
                null
            }
            return arrayOf(SeekableSegmentExtractor(map))
        }
    }
}
