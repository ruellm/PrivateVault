package com.privatevault.app

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The first key frame of a video segment as a small bitmap, through the
// platform decoder (docs/PHASE3D_DESIGN.md Section 4.3). The segment is a
// fragmented MP4 in memory: the parameter sets come from the avcC box of
// its sample description, the first sample from its first fragment. The
// sample bytes go from the segment buffer straight into the codec input
// buffer, with start codes in place of the length prefixes; the decoder
// writes a flexible YUV frame, which is sampled into a mutable software
// bitmap. start() returns once the sample is queued, so the caller can free
// the segment buffer before finish() waits for the frame. No copy of the
// sample or the frame is kept.
object VideoFirstFrame {

    const val TARGET_PX = 256
    // The whole wait for the decoded frame, over every dequeue.
    const val DECODE_TIMEOUT_MS = 5000L

    class Result(
        val bitmap: Bitmap?, val error: String?, val codec: String,
        val width: Int, val height: Int, val sampleBytes: Int,
        val parseMs: Long, val codecMs: Long, val decodeMs: Long, val convertMs: Long
    )

    private class Config(val sps: List<ByteArray>, val pps: List<ByteArray>, val nalLengthSize: Int, val width: Int, val height: Int)
    private class Sample(val offset: Int, val size: Int)

    class ParseException(message: String) : Exception(message)

    // Test hook: start() reports a failure in place of the decode.
    class TestHooks {
        @Volatile var fail = false
    }

    val testHooks = TestHooks()

    // A decoder with the first sample and the end of stream queued.
    class Session internal constructor(
        private val decoder: MediaCodec, val codec: String, val width: Int, val height: Int,
        val sampleBytes: Int, private val parseMs: Long, private val codecMs: Long
    ) {
        private var released = false

        fun finish(targetPx: Int = TARGET_PX): Result {
            val t0 = System.nanoTime()
            var decodeMs = 0L
            var convertMs = 0L
            var bitmap: Bitmap? = null
            var error: String? = null
            try {
                val info = MediaCodec.BufferInfo()
                val deadline = t0 + DECODE_TIMEOUT_MS * 1_000_000L
                var index = -1
                while (System.nanoTime() < deadline) {
                    val i = decoder.dequeueOutputBuffer(info, 100_000)
                    if (i >= 0) {
                        if (info.size > 0) {
                            index = i
                            break
                        }
                        decoder.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                decodeMs = (System.nanoTime() - t0) / 1_000_000
                if (index < 0) {
                    error = "no frame within ${DECODE_TIMEOUT_MS} ms"
                } else {
                    val t1 = System.nanoTime()
                    val image = decoder.getOutputImage(index)
                    if (image == null) {
                        error = "no output image"
                    } else {
                        try {
                            bitmap = sample(image, targetPx)
                        } finally {
                            image.close()
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    convertMs = (System.nanoTime() - t1) / 1_000_000
                }
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                release()
            }
            return Result(bitmap, error, codec, width, height, sampleBytes, parseMs, codecMs, decodeMs, convertMs)
        }

        fun release() {
            if (released) return
            released = true
            try {
                decoder.stop()
            } catch (e: Exception) {
            }
            decoder.release()
        }
    }

    // Parses the segment, makes the decoder, and queues the first sample and
    // the end of stream. A parse or codec failure comes back as a Result
    // with the error; the segment buffer is read only inside this call.
    fun start(segment: ByteBuffer): Any {
        val t0 = System.nanoTime()
        if (testHooks.fail) return Result(null, "test hook", "", 0, 0, 0, 0, 0, 0, 0)
        val cfg: Config
        val first: Sample
        try {
            val b = segment.duplicate().order(ByteOrder.BIG_ENDIAN)
            cfg = parseConfig(b)
            first = firstSample(b)
        } catch (e: Exception) {
            return Result(null, "parse: ${e.javaClass.simpleName}: ${e.message}", "", 0, 0, 0, (System.nanoTime() - t0) / 1_000_000, 0, 0, 0)
        }
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        var decoder: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
            format.setByteBuffer("csd-0", annexB(cfg.sps))
            format.setByteBuffer("csd-1", annexB(cfg.pps))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, first.size + 64)
            val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder = d
            d.configure(format, null, null, 0)
            d.start()
            val inIndex = d.dequeueInputBuffer(1_000_000)
            if (inIndex < 0) throw IllegalStateException("no input buffer")
            val input = d.getInputBuffer(inIndex) ?: throw IllegalStateException("null input buffer")
            input.clear()
            val n = copyWithStartCodes(segment, first, cfg.nalLengthSize, input)
            d.queueInputBuffer(inIndex, 0, n, 0, 0)
            val eos = d.dequeueInputBuffer(1_000_000)
            if (eos >= 0) d.queueInputBuffer(eos, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && d.codecInfo.isSoftwareOnly) "${d.name} (software)" else d.name
            return Session(d, name, cfg.width, cfg.height, n, parseMs, (System.nanoTime() - t1) / 1_000_000)
        } catch (e: Exception) {
            if (decoder != null) {
                try {
                    decoder.stop()
                } catch (e2: Exception) {
                }
                decoder.release()
            }
            return Result(null, "codec: ${e.javaClass.simpleName}: ${e.message}", "", cfg.width, cfg.height, first.size, parseMs, (System.nanoTime() - t1) / 1_000_000, 0, 0)
        }
    }

    // start() and finish() in one call.
    fun decode(segment: ByteBuffer, targetPx: Int = TARGET_PX): Result {
        val s = start(segment)
        return if (s is Session) s.finish(targetPx) else s as Result
    }

    // Parameter sets with 4-byte start codes, back to back.
    private fun annexB(sets: List<ByteArray>): ByteBuffer {
        val out = ByteBuffer.allocate(sets.sumOf { it.size + 4 })
        for (s in sets) {
            out.put(0).put(0).put(0).put(1)
            out.put(s)
        }
        out.flip()
        return out
    }

    // Copies the sample into the codec buffer, each NAL length prefix
    // replaced by a start code. Returns the bytes written.
    private fun copyWithStartCodes(segment: ByteBuffer, s: Sample, lengthSize: Int, out: ByteBuffer): Int {
        val src = segment.duplicate().order(ByteOrder.BIG_ENDIAN)
        var p = s.offset
        val end = s.offset + s.size
        var written = 0
        while (p + lengthSize <= end) {
            var len = 0
            for (i in 0 until lengthSize) len = (len shl 8) or (src.get(p + i).toInt() and 0xff)
            if (len <= 0 || p + lengthSize + len > end) throw ParseException("NAL length $len at $p")
            if (out.remaining() < 4 + len) throw ParseException("sample larger than the codec input buffer")
            out.put(0).put(0).put(0).put(1)
            val nal = src.duplicate()
            nal.position(p + lengthSize)
            nal.limit(p + lengthSize + len)
            out.put(nal)
            written += 4 + len
            p += lengthSize + len
        }
        if (p != end) throw ParseException("sample has ${end - p} trailing bytes")
        return written
    }

    // moov/trak/mdia/minf/stbl/stsd: the first avc1 entry and its avcC.
    private fun parseConfig(b: ByteBuffer): Config {
        val stsd = find(b, b.position(), b.limit(), listOf("moov", "trak", "mdia", "minf", "stbl", "stsd"))
            ?: throw ParseException("no stsd")
        val entry = firstBox(b, stsd.first + 8, stsd.second) ?: throw ParseException("no sample entry")
        if (entry.type != "avc1" && entry.type != "avc3") throw ParseException("sample entry ${entry.type}")
        b.position(entry.bodyStart + 24)
        val width = b.short.toInt() and 0xffff
        val height = b.short.toInt() and 0xffff
        val avcC = findBox(b, entry.bodyStart + 78, entry.bodyEnd, "avcC") ?: throw ParseException("no avcC")
        b.position(avcC.first)
        val version = b.get().toInt() and 0xff
        if (version != 1) throw ParseException("avcC version $version")
        b.get(); b.get(); b.get()
        val lengthSize = (b.get().toInt() and 0x3) + 1
        val spsCount = b.get().toInt() and 0x1f
        val sps = ArrayList<ByteArray>()
        for (i in 0 until spsCount) {
            val n = b.short.toInt() and 0xffff
            val a = ByteArray(n)
            b.get(a)
            sps.add(a)
        }
        val ppsCount = b.get().toInt() and 0xff
        val pps = ArrayList<ByteArray>()
        for (i in 0 until ppsCount) {
            val n = b.short.toInt() and 0xffff
            val a = ByteArray(n)
            b.get(a)
            pps.add(a)
        }
        if (sps.isEmpty() || pps.isEmpty()) throw ParseException("avcC without parameter sets")
        if (width <= 0 || height <= 0) throw ParseException("sample entry size ${width}x$height")
        return Config(sps, pps, lengthSize, width, height)
    }

    // The first sample of the first fragment: tfhd base offset, trun data
    // offset, and the first sample size.
    private fun firstSample(b: ByteBuffer): Sample {
        val base = b.position()
        val moof = findBoxFull(b, base, b.limit(), "moof") ?: throw ParseException("no moof")
        val traf = findBox(b, moof.bodyStart, moof.bodyEnd, "traf") ?: throw ParseException("no traf")
        val tfhd = findBox(b, traf.first, traf.second, "tfhd") ?: throw ParseException("no tfhd")
        b.position(tfhd.first)
        val tfFlags = b.int and 0xffffff
        b.int
        var dataBase = moof.start.toLong()
        if (tfFlags and 0x1 != 0) dataBase = b.long
        if (tfFlags and 0x2 != 0) b.int
        if (tfFlags and 0x8 != 0) b.int
        val defaultSize = if (tfFlags and 0x10 != 0) b.int else -1
        val trun = findBox(b, traf.first, traf.second, "trun") ?: throw ParseException("no trun")
        b.position(trun.first)
        val flags = b.int and 0xffffff
        val count = b.int
        if (count < 1) throw ParseException("empty trun")
        val dataOffset = if (flags and 0x1 != 0) b.int else 0
        if (flags and 0x4 != 0) b.int
        if (flags and 0x100 != 0) b.int
        val size = if (flags and 0x200 != 0) b.int else defaultSize
        if (size <= 0) throw ParseException("first sample size $size")
        val offset = dataBase + dataOffset
        if (offset < base || offset + size > b.limit()) throw ParseException("sample at $offset size $size outside the segment")
        return Sample(offset.toInt(), size)
    }

    private class Box(val type: String, val start: Int, val bodyStart: Int, val bodyEnd: Int)

    private fun firstBox(b: ByteBuffer, start: Int, end: Int): Box? {
        if (start + 8 > end) return null
        b.position(start)
        var size = b.int.toLong() and 0xffffffffL
        val type = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
        var header = 8
        if (size == 1L) {
            size = b.long
            header = 16
        } else if (size == 0L) {
            size = (end - start).toLong()
        }
        if (size < header || start + size > end) return null
        return Box(type, start, start + header, (start + size).toInt())
    }

    private fun findBoxFull(b: ByteBuffer, start: Int, end: Int, type: String): Box? {
        var pos = start
        while (pos + 8 <= end) {
            val box = firstBox(b, pos, end) ?: return null
            if (box.type == type) return box
            pos = box.bodyEnd
        }
        return null
    }

    // Body range of the first child box of the given type.
    private fun findBox(b: ByteBuffer, start: Int, end: Int, type: String): Pair<Int, Int>? {
        val box = findBoxFull(b, start, end, type) ?: return null
        return box.bodyStart to box.bodyEnd
    }

    private fun find(b: ByteBuffer, start: Int, end: Int, path: List<String>): Pair<Int, Int>? {
        var range = start to end
        for (name in path) range = findBox(b, range.first, range.second, name) ?: return null
        return range
    }

    // Nearest-neighbour sample of the cropped frame into a bitmap whose long
    // side is targetPx; BT.601 limited range.
    private fun sample(image: Image, targetPx: Int): Bitmap {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) throw IllegalStateException("image format ${image.format}")
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) throw IllegalStateException("crop ${cw}x$ch")
        val scale = maxOf(cw, ch).toDouble() / targetPx
        val ow = maxOf(1, Math.round(cw / scale).toInt())
        val oh = maxOf(1, Math.round(ch / scale).toInt())
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val row = IntArray(ow)
        for (oy in 0 until oh) {
            val sy = crop.top + minOf(ch - 1, (oy * scale).toInt())
            for (ox in 0 until ow) {
                val sx = crop.left + minOf(cw - 1, (ox * scale).toInt())
                val y = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xff) - 16
                val u = (uBuf.get((sy / 2) * uRow + (sx / 2) * uPix).toInt() and 0xff) - 128
                val v = (vBuf.get((sy / 2) * vRow + (sx / 2) * vPix).toInt() and 0xff) - 128
                val yy = 1192 * maxOf(0, y)
                val r = clamp((yy + 1634 * v) shr 10)
                val g = clamp((yy - 833 * v - 400 * u) shr 10)
                val bl = clamp((yy + 2066 * u) shr 10)
                row[ox] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
            }
            out.setPixels(row, 0, ow, 0, oy, ow, 1)
        }
        row.fill(0)
        return out
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
