package com.privatevault.app

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
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

// Debug-only: runs media3's FragmentedMp4Extractor over a segment held in
// memory and counts the samples it reports with the key-frame flag, so the
// container reading of media3 can be set beside the framework extractor and
// the box walk (docs/PHASE3C_DESIGN.md Section 2). Sample bytes are read
// and dropped; nothing is kept.
@androidx.annotation.OptIn(UnstableApi::class)
object Media3Sync {

    // syncTimesUs are the times of the samples flagged as key frames, in
    // order; sampleTimesUs the times of the first samples (up to FIRST_TIMES).
    class Result(
        val samples: Int, val syncSamples: Int, val tracks: Int, val error: String?,
        val syncTimesUs: List<Long>, val sampleTimesUs: List<Long>
    ) {
        override fun toString(): String =
            "samples=$samples sync=$syncSamples tracks=$tracks sync_times_us=${syncTimesUs.joinToString(",")}" + (if (error != null) " error=$error" else "")
    }

    const val FIRST_TIMES = 40

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
        var samples = 0
        var sync = 0
        val syncTimes = ArrayList<Long>()
        val times = ArrayList<Long>()
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {}

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
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
                sync++
                syncTimes.add(timeUs)
            }
            if (times.size < FIRST_TIMES) times.add(timeUs)
        }
    }

    private class Output : ExtractorOutput {
        val tracks = ArrayList<CountingTrack>()
        override fun track(id: Int, type: Int): TrackOutput {
            val t = CountingTrack()
            tracks.add(t)
            return t
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    fun count(payload: ByteArray, extractorFlags: Int = 0): Result {
        val extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, extractorFlags)
        val output = Output()
        var error: String? = null
        try {
            extractor.init(output)
            var position = 0L
            val holder = PositionHolder()
            var input = DefaultExtractorInput(ArrayReader(payload, 0), 0, payload.size.toLong())
            while (true) {
                val r = extractor.read(input, holder)
                if (r == Extractor.RESULT_END_OF_INPUT) break
                if (r == Extractor.RESULT_SEEK) {
                    position = holder.position
                    input = DefaultExtractorInput(ArrayReader(payload, position.toInt()), position, payload.size.toLong())
                }
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            extractor.release()
        }
        val samples = output.tracks.sumOf { it.samples }
        val sync = output.tracks.sumOf { it.sync }
        val video = output.tracks.firstOrNull()
        return Result(samples, sync, output.tracks.size, error, video?.syncTimes ?: emptyList(), video?.times ?: emptyList())
    }
}
