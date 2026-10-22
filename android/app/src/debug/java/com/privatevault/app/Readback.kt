package com.privatevault.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.nio.ByteBuffer

// Debug only, Stage 3F: the compatibility gate as a scripted action
// (readback=<index hex | latest>). Every segment of the clip is decrypted
// and read by the framework MediaExtractor and by media3
// FragmentedMp4Extractor; per track the sample count, the sync count, and
// the sync times are set against the index. Sample bytes are read and
// dropped; the lines carry counts, times, and object IDs only.
@androidx.annotation.OptIn(UnstableApi::class)
object Readback {
    const val DECRYPT_CAP = 9 shl 20

    class Track(val kind: String, val mime: String?, val samples: Int, val sync: Int, val firstUs: Long, val lastUs: Long, val syncTimesUs: List<Long>, val detail: String) {
        fun line(): String = "$kind mime=$mime samples=$samples sync=$sync first_us=$firstUs last_us=$lastUs$detail"
    }

    // The newest object that decodes as an index, or the given one.
    fun findIndex(vault: Vault, selector: String): Pair<String, VideoIndex>? {
        if (selector != "latest") {
            val d = VideoIndex.read(vault, NativeBridge.fromHex(selector))
            return if (d.index != null) selector to d.index else null
        }
        for (e in vault.listObjects()) {
            if (e.size > VideoIndex.OBJECT_CAP + 4096) continue
            val d = VideoIndex.read(vault, e.id)
            if (d.status == NativeBridge.OK && d.index != null) return e.hex to d.index
        }
        return null
    }

    fun run(selector: String, vault: Vault, say: (String) -> Unit) {
        val found = findIndex(vault, selector)
        if (found == null) {
            say("readback: no index for $selector")
            return
        }
        val (hex, idx) = found
        val h = idx.header
        say("readback: index $hex segments=${idx.segments.size} keys=${idx.keysTotal} duration_ms=${h.totalDurationUs / 1000} ${h.width}x${h.height} rotation=${h.rotation} fps=${h.frameRateMillihz / 1000} audio_codec=${h.audioCodec} channels=${h.audioChannels} rate=${h.audioSampleRate}")
        var ok = 0
        var audioOk = 0
        for ((n, s) in idx.segments.withIndex()) {
            val status = IntArray(1)
            val plain = NativeBridge.nativeDecryptToArray(vault.rawHandle, s.id, DECRYPT_CAP, status)
            if (plain == null) {
                say("readback: segment $n ${s.hex} decrypt ${NativeBridge.errorName(status[0])}")
                continue
            }
            try {
                val payload = plain.copyOfRange(NativeBridge.ITEM_HEADER_BYTES, plain.size)
                try {
                    val boxes = Mp4Boxes.walk(ByteBuffer.wrap(payload))
                    say("readback: segment $n ${s.hex} bytes=${payload.size} index_bytes=${s.byteLen} index_keys=${s.keyOffsetsUs.joinToString(",")} duration_us=${s.durationUs}")
                    say("readback: segment $n ${boxes.line()}")
                    val fw = framework(payload)
                    for (t in fw) say("readback: segment $n framework ${t.line()}")
                    say("readback: segment $n framework first frame: ${SegmentVerify.inspect(payload)}")
                    val m3 = media3(payload)
                    for (t in m3) say("readback: segment $n media3 ${t.line()}")
                    val fwVideo = fw.firstOrNull { it.kind == "video" }
                    val m3Video = m3.firstOrNull { it.kind == "video" }
                    val fwAudio = fw.firstOrNull { it.kind == "audio" }
                    val m3Audio = m3.firstOrNull { it.kind == "audio" }
                    val keys = s.keyOffsetsUs.map { it.toLong() }
                    val videoOk = fwVideo != null && m3Video != null && fwVideo.sync == keys.size && m3Video.sync == keys.size &&
                        m3Video.syncTimesUs == keys && fwVideo.samples == m3Video.samples && boxes.error == null &&
                        boxes.sidxReferences == boxes.moofCount && boxes.tfdts.filterNotNull().isNotEmpty()
                    // The framework extractor times every track from 0 by the
                    // sum of its trun durations (it ignores tfdt, measured in
                    // Milestone A), so the two readers are compared on counts
                    // and spans; media3 gives the times.
                    // How far the audio runs past the last video frame is
                    // reported, not judged: the last segment keeps the audio
                    // recorded after its video (D-047), and a segment that took
                    // late audio runs late by the delay.
                    val wantAudio = h.audioCodec == NativeBridge.AUDIO_CODEC_AAC
                    val audioFine = if (!wantAudio) fwAudio == null && m3Audio == null
                    else fwAudio != null && m3Audio != null && fwAudio.samples == m3Audio.samples && fwAudio.samples > 0 &&
                        m3Audio.firstUs >= 0 &&
                        Math.abs((fwAudio.lastUs - fwAudio.firstUs) - (m3Audio.lastUs - m3Audio.firstUs)) <= 1
                    val pastVideo = if (m3Audio != null) m3Audio.lastUs + 21_333 - s.durationUs else 0L
                    say("readback: segment $n result video=${if (videoOk) "ok" else "MISMATCH"} audio=${if (audioFine) (if (wantAudio) "ok" else "none") else "MISMATCH"} framework_tracks=${fw.size} media3_tracks=${m3.size} audio_past_video_us=$pastVideo")
                    if (videoOk) ok++
                    if (audioFine) audioOk++
                } finally {
                    payload.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        }
        say("readback: done $hex segments=${idx.segments.size} video_ok=$ok audio_ok=$audioOk")
    }

    // Per track through the framework extractor: one extractor per track,
    // every sample read and dropped.
    fun framework(payload: ByteArray): List<Track> {
        val out = ArrayList<Track>()
        val probe = MediaExtractor()
        val count = try {
            probe.setDataSource(SegmentVerify.ArraySource(payload))
            probe.trackCount
        } catch (e: Exception) {
            return listOf(Track("error", null, 0, 0, -1, -1, emptyList(), " ${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            probe.release()
        }
        for (i in 0 until count) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(SegmentVerify.ArraySource(payload))
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                val kind = when {
                    mime?.startsWith("video/") == true -> "video"
                    mime?.startsWith("audio/") == true -> "audio"
                    else -> "other"
                }
                val detail = if (kind == "audio") {
                    " channels=${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} rate=${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}" +
                        (if (fmt.containsKey(MediaFormat.KEY_DURATION)) " duration_us=${fmt.getLong(MediaFormat.KEY_DURATION)}" else "")
                } else {
                    " ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}" +
                        (if (fmt.containsKey(MediaFormat.KEY_DURATION)) " duration_us=${fmt.getLong(MediaFormat.KEY_DURATION)}" else "")
                }
                ex.selectTrack(i)
                val buf = ByteBuffer.allocate(2 shl 20)
                var samples = 0
                var sync = 0
                var first = -1L
                var last = -1L
                val syncTimes = ArrayList<Long>()
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    samples++
                    val t = ex.sampleTime
                    if (first < 0) first = t
                    last = t
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        sync++
                        if (kind == "video") syncTimes.add(t)
                    }
                    ex.advance()
                }
                out.add(Track(kind, mime, samples, sync, first, last, syncTimes, detail))
            } catch (e: Exception) {
                out.add(Track("error", null, 0, 0, -1, -1, emptyList(), " track $i ${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                ex.release()
            }
        }
        return out
    }

    private class ArrayReader(private val data: ByteArray, private var pos: Int) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    private class CountingTrack : TrackOutput {
        var format: Format? = null
        var samples = 0
        var sync = 0
        var first = -1L
        var last = -1L
        val syncTimes = ArrayList<Long>()
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            var left = length
            var total = 0
            while (left > 0) {
                val n = input.read(scratch, 0, minOf(left, scratch.size))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && total == 0) return C.RESULT_END_OF_INPUT
                    throw IOException("segment ends inside a sample")
                }
                left -= n
                total += n
            }
            return total
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++
            if (first < 0) first = timeUs
            last = timeUs
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
                sync++
                syncTimes.add(timeUs)
            }
        }
    }

    // Per track through media3 FragmentedMp4Extractor over the whole segment.
    fun media3(payload: ByteArray): List<Track> {
        val extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        val tracks = ArrayList<CountingTrack>()
        var error: String? = null
        try {
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = CountingTrack().also { tracks.add(it) }
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {}
            })
            val holder = PositionHolder()
            var input = DefaultExtractorInput(ArrayReader(payload, 0), 0, payload.size.toLong())
            while (true) {
                val r = extractor.read(input, holder)
                if (r == Extractor.RESULT_END_OF_INPUT) break
                if (r == Extractor.RESULT_SEEK) input = DefaultExtractorInput(ArrayReader(payload, holder.position.toInt()), holder.position, payload.size.toLong())
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            extractor.release()
        }
        val out = ArrayList<Track>()
        for (t in tracks) {
            val f = t.format
            val mime = f?.sampleMimeType
            val kind = when {
                mime != null && MimeTypes.isVideo(mime) -> "video"
                mime != null && MimeTypes.isAudio(mime) -> "audio"
                else -> "other"
            }
            val detail = if (kind == "audio") " channels=${f?.channelCount} rate=${f?.sampleRate} codecs=${f?.codecs}" else " ${f?.width}x${f?.height}"
            out.add(Track(kind, mime, t.samples, t.sync, t.first, t.last, if (kind == "video") t.syncTimes else emptyList(), detail + (if (error != null) " error=$error" else "")))
        }
        if (tracks.isEmpty()) out.add(Track("error", null, 0, 0, -1, -1, emptyList(), " no tracks${if (error != null) " error=$error" else ""}"))
        return out
    }
}

// Debug only, Stage 3F: the A/V offset of a clip from a clap
// (av_sync=<index hex | latest>). Every video frame is decoded and the
// motion between consecutive frames measured on a luma grid; the audio is
// decoded to PCM and its loudest moment found. The offset is the audio
// onset against the frame of most motion. Decoded frames and samples are
// reduced to numbers at once and never kept.
object AvSync {
    class Frame(val clipUs: Long, val motion: Double)

    fun run(selector: String, vault: Vault, say: (String) -> Unit) {
        val found = Readback.findIndex(vault, selector)
        if (found == null) {
            say("av_sync: no index for $selector")
            return
        }
        val (hex, idx) = found
        val frames = ArrayList<Frame>()
        val envelope = ArrayList<Pair<Long, Int>>()
        var prevGrid: ByteArray? = null
        var decoderName = "none"
        var audioDecoderName = "none"
        var audioFrames = 0
        for ((n, s) in idx.segments.withIndex()) {
            val status = IntArray(1)
            val plain = NativeBridge.nativeDecryptToArray(vault.rawHandle, s.id, Readback.DECRYPT_CAP, status)
            if (plain == null) {
                say("av_sync: segment $n decrypt ${NativeBridge.errorName(status[0])}")
                return
            }
            try {
                val payload = plain.copyOfRange(NativeBridge.ITEM_HEADER_BYTES, plain.size)
                try {
                    val before = frames.size
                    val r = decodeVideo(payload, s.startUs, frames, prevGrid)
                    prevGrid = r.second
                    decoderName = r.first
                    val a = decodeAudio(payload, s.startUs, envelope)
                    audioDecoderName = a.first
                    audioFrames += a.second
                    say("av_sync: segment $n video_frames=${frames.size - before} audio_frames=${a.second} envelope_bins=${envelope.size}")
                } finally {
                    payload.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        }
        if (frames.size < 3) {
            say("av_sync: too few frames (${frames.size})")
            return
        }
        val meanMotion = frames.sumOf { it.motion } / frames.size
        say("av_sync: video decoder=$decoderName frames=${frames.size} mean_motion=${"%.2f".format(meanMotion)}")
        if (envelope.isEmpty()) {
            say("av_sync: no audio decoded (decoder=$audioDecoderName)")
            return
        }
        // Envelope per 10 ms, then the quiet level as the median of it.
        val coarse = ArrayList<Pair<Long, Int>>()
        var binStart = envelope[0].first
        var binPeak = 0
        for (e in envelope) {
            if (e.first - binStart >= 10) {
                coarse.add(binStart to binPeak)
                binStart = e.first
                binPeak = 0
            }
            if (e.second > binPeak) binPeak = e.second
        }
        coarse.add(binStart to binPeak)
        val sorted = coarse.map { it.second }.sorted()
        val quiet = sorted[sorted.size / 2]
        val audioPeaks = peaks(coarse.map { it.first to it.second.toDouble() }, 400, 5)
        val motionPeaks = peaks(frames.map { it.clipUs / 1000 to it.motion }, 400, 5)
        say("av_sync: audio decoder=$audioDecoderName aac_frames=$audioFrames quiet_median=$quiet audio_peaks=" + audioPeaks.joinToString(" ") { "${it.first}ms:${it.second.toInt()}" })
        say("av_sync: motion_peaks=" + motionPeaks.joinToString(" ") { "${it.first}ms:${"%.1f".format(it.second)}" })
        for (ap in audioPeaks) {
            if (ap.second < quiet * 2) continue
            // Onset: the first 10 ms bin in the 200 ms before the peak above half the peak over quiet.
            var onset = ap.first
            for (c in coarse) {
                if (c.first >= ap.first - 200 && c.first <= ap.first && c.second - quiet >= (ap.second - quiet) / 2) {
                    onset = c.first
                    break
                }
            }
            val around = coarse.filter { Math.abs(it.first - ap.first) <= 300 }.joinToString(" ") { "${it.first}:${it.second}" }
            val motionNear = frames.filter { Math.abs(it.clipUs / 1000 - ap.first) <= 600 }
            // The hands come together before the sound: the largest motion in
            // the 300 ms before the onset, then the first frame after it where
            // motion has fallen to half (the hands met).
            val approach = frames.filter { it.clipUs / 1000 >= onset - 300 && it.clipUs / 1000 <= onset + 50 }
            val mp = approach.maxByOrNull { it.motion }
            say("av_sync: audio peak ${ap.first} ms value=${ap.second.toInt()} onset_ms=$onset envelope_10ms=[$around]")
            say("av_sync: motion near ${ap.first} ms: " + motionNear.joinToString(" ") { "${it.clipUs / 1000}:${"%.1f".format(it.motion)}" })
            if (mp != null) {
                var met: Frame = mp
                val i0 = frames.indexOf(mp)
                for (i in i0 + 1 until frames.size) {
                    if (frames[i].motion < mp.motion / 2) {
                        met = frames[i]
                        break
                    }
                    if (frames[i].clipUs - mp.clipUs > 300_000) break
                }
                say("av_sync: pairing audio_onset_ms=$onset approach_peak_ms=${mp.clipUs / 1000} hands_met_ms=${met.clipUs / 1000} offset_ms=${onset - met.clipUs / 1000} (positive: audio later than the hands meeting; one frame is 33 ms)")
            }
        }
        say("av_sync: done $hex")
    }

    // The n largest values at least gapMs apart.
    private fun peaks(series: List<Pair<Long, Double>>, gapMs: Long, n: Int): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        for (p in series.sortedByDescending { it.second }) {
            if (out.size >= n) break
            if (out.none { Math.abs(it.first - p.first) < gapMs }) out.add(p)
        }
        return out.sortedBy { it.first }
    }

    // Decodes the video track; appends one Frame per output with the mean
    // absolute luma difference to the previous frame on a 1 in 8 grid.
    private fun decodeVideo(payload: ByteArray, segmentStartUs: Long, out: ArrayList<Frame>, prevIn: ByteArray?): Pair<String, ByteArray?> {
        var prev = prevIn
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var name = "none"
        try {
            ex.setDataSource(SegmentVerify.ArraySource(payload))
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return name to prev
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            decoder = d
            name = d.name
            d.configure(fmt, null, null, 0)
            d.start()
            val info = MediaCodec.BufferInfo()
            val buf = ByteBuffer.allocate(2 shl 20)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = d.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ib = d.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(buf.duplicate().apply { limit(n) })
                            d.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val grid = lumaGrid(d, o)
                        if (grid != null) {
                            val motion = if (prev != null && prev.size == grid.size) meanAbsDiff(prev, grid) else 0.0
                            out.add(Frame(segmentStartUs + info.presentationTimeUs, motion))
                            prev = grid
                        }
                    }
                    d.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: Exception) {
            name = "$name failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
            }
            decoder?.release()
            ex.release()
        }
        return name to prev
    }

    // Every 8th row and column of the luma plane of an output image.
    private fun lumaGrid(d: MediaCodec, index: Int): ByteArray? {
        val img = d.getOutputImage(index) ?: return null
        try {
            val plane = img.planes[0]
            val b = plane.buffer
            val w = img.width
            val h = img.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val cols = w / 8
            val rows = h / 8
            val grid = ByteArray(cols * rows)
            var k = 0
            for (y in 0 until rows) {
                val rowBase = y * 8 * rs
                for (x in 0 until cols) {
                    val pos = rowBase + x * 8 * ps
                    grid[k++] = if (pos < b.limit()) b.get(pos) else 0
                }
            }
            return grid
        } finally {
            img.close()
        }
    }

    private fun meanAbsDiff(a: ByteArray, b: ByteArray): Double {
        var sum = 0L
        for (i in a.indices) sum += Math.abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
        return sum.toDouble() / a.size
    }

    // Decodes the audio track to PCM and appends the peak absolute sample
    // of every millisecond as (clip ms, peak).
    private fun decodeAudio(payload: ByteArray, segmentStartUs: Long, out: ArrayList<Pair<Long, Int>>): Pair<String, Int> {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var name = "none"
        var aacFrames = 0
        try {
            ex.setDataSource(SegmentVerify.ArraySource(payload))
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return name to 0
            val fmt = ex.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ex.selectTrack(track)
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            decoder = d
            name = d.name
            d.configure(fmt, null, null, 0)
            d.start()
            val info = MediaCodec.BufferInfo()
            val buf = ByteBuffer.allocate(64 shl 10)
            var inputDone = false
            var outputDone = false
            var outRate = rate
            var outChannels = channels
            while (!outputDone) {
                if (!inputDone) {
                    val idx = d.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            aacFrames++
                            val ib = d.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(buf.duplicate().apply { limit(n) })
                            d.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = d.outputFormat
                    outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (o >= 0) {
                    if (info.size > 0) {
                        val ob = d.getOutputBuffer(o)!!
                        val shorts = ob.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.slice().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = shorts.remaining() / outChannels
                        val perMs = outRate / 1000
                        var f = 0
                        while (f < frames) {
                            var peak = 0
                            val end = minOf(frames, f + perMs)
                            for (i in f * outChannels until end * outChannels) {
                                val v = Math.abs(shorts.get(i).toInt())
                                if (v > peak) peak = v
                            }
                            val ms = (segmentStartUs + info.presentationTimeUs) / 1000 + f.toLong() * 1000 / outRate
                            out.add(ms to peak)
                            f = end
                        }
                    }
                    d.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: Exception) {
            name = "$name failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
            }
            decoder?.release()
            ex.release()
        }
        return name to aacFrames
    }
}
