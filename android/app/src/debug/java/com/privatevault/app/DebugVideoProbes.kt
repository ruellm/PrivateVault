package com.privatevault.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Video feasibility prototype, debug variant only (docs/PHASE2_DESIGN.md
// Section 8). Installed into VideoProbes by reflection from PvApp. One
// action: video_probe (V3b, encoder surface fed by CameraX through the
// Preview use case, fragmented MP4 segments encrypted into the vault). The
// Phase 2 V2 probe (Recorder over a proxy file descriptor) and the V3a
// attempt (custom VideoOutput) needed camera-video, which Stage 3B removed
// from every variant; their results stay in docs/VIDEO_CAPTURE_FEASIBILITY.md.
@Suppress("unused")
@androidx.annotation.OptIn(UnstableApi::class)
class DebugVideoProbes : VideoProbeRunner {
    init {
        VideoProbes.install(this)
    }

    override fun run(
        name: String, activity: CaptureActivity, imageCapture: ImageCapture?, camera: Camera?,
        provider: ProcessCameraProvider?, lensFacing: Int, vaults: VaultSession, say: (String) -> Unit
    ) {
        val p = provider ?: throw IllegalStateException("camera provider not ready")
        when (name) {
            "video_probe" -> V3Probe(activity, p, lensFacing, vaults, say).run()
            else -> say("$name: not available in this build")
        }
        activity.rebindAndWait()
    }
}

// Bounded in-memory sink for one fragmented MP4 segment. The muxer writes into
// it; when the segment closes the bytes go to the core through the memory
// source (one native copy into guarded memory). Capacity is fixed; a write
// past it fails and stops the recording.
class SegmentBuffer(private val capacity: Int) : WritableByteChannel {
    private val out = ByteArrayOutputStream(256 * 1024)
    @Volatile private var open = true
    val size: Int get() = out.size()

    override fun write(src: ByteBuffer): Int {
        val n = src.remaining()
        if (out.size() + n > capacity) throw java.io.IOException("segment buffer full (${capacity} bytes)")
        val tmp = ByteArray(n)
        src.get(tmp)
        out.write(tmp, 0, n)
        return n
    }

    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
    }

    fun bytes(): ByteArray = out.toByteArray()
}

// V3b: the Preview use case hands CameraX a MediaCodec input surface through
// its SurfaceProvider, so there is no on-screen viewfinder while recording.
@androidx.annotation.OptIn(UnstableApi::class)
class V3Probe(
    private val activity: CaptureActivity,
    private val provider: ProcessCameraProvider,
    private val lensFacing: Int,
    private val vaults: VaultSession,
    private val say: (String) -> Unit
) {
    companion object {
        const val CLIP_MS = 10_000L
        const val SEGMENT_US = 3_000_000L
        const val SEGMENT_CAP = 8 shl 20
        const val MAX_PENDING_SEGMENTS = 2
        const val BITRATE = 4_000_000
        const val FRAME_RATE = 30
        const val IFRAME_SECONDS = 1
        const val MIME = "video/avc"
    }

    private class Segment(val index: Int, val buffer: SegmentBuffer, val muxer: FragmentedMp4Muxer,
                          val startUs: Long, var endUs: Long, var samples: Int, var syncFrames: Int)

    private class Done(val index: Int, val id: ByteArray, val startUs: Long, val durationUs: Long,
                       val bytes: Int, val samples: Int, val syncFrames: Int, val encryptMs: Long)

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderFormat: MediaFormat? = null
    private var width = 0
    private var height = 0
    private var rotation = 0
    private val frames = AtomicInteger(0)
    private var bytesEncoded = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = 0L
    private var current: Segment? = null
    private var trackId = -1
    private val done = ArrayList<Done>()
    private val pendingSegments = AtomicInteger(0)
    private var maxPending = 0
    private var maxSegmentBytes = 0
    private var stopReason: String? = null
    private val encryptExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-video-encrypt") }
    private val encryptJobs = ArrayList<CountDownLatch>()
    private val surfaceLatch = CountDownLatch(1)
    private val variant = "V3b Preview with encoder SurfaceProvider, no on-screen preview"
    private var pssPeak = 0
    private var nativePeak = 0L

    // Creates the encoder at the requested resolution and provides its input surface.
    private fun onSurfaceRequested(request: SurfaceRequest) {
        try {
            val res = request.resolution
            width = res.width
            height = res.height
            // getExpectedFrameRate is @RestrictTo (lint RestrictedApi); not used.
            say("video_probe: surface requested ${width}x$height, dynamic range ${request.dynamicRange}")
            request.setTransformationInfoListener(activity.mainExecutor) { info ->
                rotation = info.rotationDegrees
                say("video_probe: transformation rotation=${info.rotationDegrees} crop=${info.cropRect}")
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SECONDS)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = surface
            request.provideSurface(surface, activity.mainExecutor) { result ->
                say("video_probe: surface result ${result.resultCode}")
            }
            say("video_probe: encoder ${codec.name} started")
            surfaceLatch.countDown()
        } catch (e: Exception) {
            say("video_probe: encoder setup failed: ${e.javaClass.simpleName}: ${e.message}")
            request.willNotProvideSurface()
            stopReason = "ENCODER_SETUP_FAILED"
            surfaceLatch.countDown()
        }
    }

    fun run() {
        val vault = vaults.vault ?: throw IllegalStateException("vault not open")
        val before = LeakScan.run(activity)
        val cpu0 = cpuTicks()
        val wall0 = System.nanoTime()
        val sampler = Sampler()
        sampler.start()
        var frameCount = 0
        try {
            bind()
            if (!surfaceLatch.await(5, TimeUnit.SECONDS) || encoder == null) {
                say("video_probe: FAIL no encoder surface (${stopReason ?: "no request"})")
                return
            }
            frameCount = drain(vault)
        } finally {
            sampler.finish()
            try {
                activity.runOnUiThread { provider.unbindAll() }
            } catch (e: Exception) {
            }
            releaseEncoder()
        }
        for (l in encryptJobs) l.await(60, TimeUnit.SECONDS)
        encryptExecutor.shutdown()
        val wallMs = (System.nanoTime() - wall0) / 1_000_000
        val cpuMs = (cpuTicks() - cpu0) * 10
        val recordedUs = if (firstPtsUs >= 0) lastPtsUs - firstPtsUs else 0
        say("video_probe: variant=$variant")
        say("video_probe: frames=$frameCount bytes=$bytesEncoded recorded=${recordedUs / 1000}ms " +
            "bitrate=${if (recordedUs > 0) bytesEncoded * 8_000_000 / recordedUs else 0}bps " +
            "segments=${done.size} max_pending=$maxPending max_segment_bytes=$maxSegmentBytes stop=${stopReason ?: "clip end"}")
        say("video_probe: cpu=${cpuMs}ms over ${wallMs}ms wall (${if (wallMs > 0) cpuMs * 100 / wallMs else 0}%) pss_peak=${pssPeak}KiB native_heap_peak=${nativePeak shr 10}KiB")
        var payloadTotal = 0L
        var encryptTotal = 0L
        for (d in done) {
            say("video_probe: segment ${d.index} ${NativeBridge.hex(d.id)} start=${d.startUs / 1000}ms duration=${d.durationUs / 1000}ms bytes=${d.bytes} samples=${d.samples} sync=${d.syncFrames} encrypt=${d.encryptMs}ms")
            payloadTotal += d.bytes
            encryptTotal += d.encryptMs
        }
        if (encryptTotal > 0) say("video_probe: encrypt throughput ${payloadTotal * 1000 / encryptTotal / 1024} KiB/s over ${done.size} segments")
        val indexId = writeIndex(vault)
        say("video_probe: index object ${if (indexId != null) NativeBridge.hex(indexId) else "none"}")
        verify(vault)
        val after = LeakScan.run(activity)
        for (l in after.lines()) say("video_probe: $l")
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") }
        say("video_probe: new non-object files after probe: ${fresh.size}${if (fresh.isNotEmpty()) " " + fresh.map { it.path } else ""}")
    }

    private fun bind() {
        val latch = CountDownLatch(1)
        var error: String? = null
        activity.runOnUiThread {
            try {
                provider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val preview = Preview.Builder().build()
                preview.surfaceProvider = Preview.SurfaceProvider { request -> onSurfaceRequested(request) }
                provider.bindToLifecycle(activity, selector, preview)
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        if (error != null) {
            say("video_probe: bind failed: $error")
            stopReason = "BIND_FAILED"
            surfaceLatch.countDown()
        }
    }

    // Pulls encoded frames from the encoder for CLIP_MS, splitting them into
    // segments at sync frames once a segment is SEGMENT_US long.
    private fun drain(vault: Vault): Int {
        val codec = encoder ?: return 0
        val info = MediaCodec.BufferInfo()
        val clipStart = System.nanoTime()
        var requestedSync = false
        var count = 0
        while (true) {
            val elapsedMs = (System.nanoTime() - clipStart) / 1_000_000
            if (elapsedMs >= CLIP_MS && stopReason == null) {
                stopReason = "clip end"
                codec.signalEndOfInputStream()
            }
            val idx = codec.dequeueOutputBuffer(info, 20_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                encoderFormat = codec.outputFormat
                say("video_probe: encoder format ${encoderFormat}")
                continue
            }
            if (idx < 0) {
                if (stopReason != null && elapsedMs > CLIP_MS + 2000) break
                continue
            }
            val buf = codec.getOutputBuffer(idx)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codec.releaseOutputBuffer(idx, false)
                continue
            }
            if (info.size > 0) {
                val sync = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                val pts = info.presentationTimeUs - firstPtsUs
                lastPtsUs = info.presentationTimeUs
                bytesEncoded += info.size
                count++
                val seg = current
                val boundary = seg != null && pts - seg.startUs >= SEGMENT_US
                if (boundary && !requestedSync) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    requestedSync = true
                }
                if ((seg == null || (boundary && sync))) {
                    if (seg != null) finishSegment(vault, seg)
                    if (!sync) {
                        // The very first frame should be a sync frame; keep waiting.
                        codec.releaseOutputBuffer(idx, false)
                        continue
                    }
                    if (lowSpace()) {
                        stopReason = "LOW_STORAGE"
                        say("video_probe: stopping at segment boundary, free ${freeBytes() shr 20} MiB below threshold ${lowSpaceThreshold() shr 20} MiB")
                        codec.releaseOutputBuffer(idx, false)
                        codec.signalEndOfInputStream()
                        current = null
                        break
                    }
                    if (pendingSegments.get() >= MAX_PENDING_SEGMENTS) {
                        stopReason = "QUEUE_FULL"
                        say("video_probe: stopping, $MAX_PENDING_SEGMENTS segments already waiting for encryption")
                        codec.releaseOutputBuffer(idx, false)
                        codec.signalEndOfInputStream()
                        current = null
                        break
                    }
                    requestedSync = false
                    current = startSegment(pts)
                }
                val s = current!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                try {
                    s.muxer.writeSampleData(
                        trackId, buf,
                        BufferInfo(pts, info.size, if (sync) C.BUFFER_FLAG_KEY_FRAME else 0)
                    )
                } catch (e: Exception) {
                    say("video_probe: muxer write failed: ${e.javaClass.simpleName}: ${e.message}")
                    stopReason = "MUXER_WRITE_FAILED"
                    codec.releaseOutputBuffer(idx, false)
                    codec.signalEndOfInputStream()
                    current = null
                    break
                }
                s.samples++
                if (sync) s.syncFrames++
                s.endUs = pts
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(idx, false)
            if (eos) break
        }
        current?.let { finishSegment(vault, it) }
        current = null
        return count
    }

    private fun startSegment(startUs: Long): Segment {
        val fmt = encoderFormat ?: throw IllegalStateException("encoder format unknown")
        val csd = ArrayList<ByteArray>()
        for (key in listOf("csd-0", "csd-1")) {
            val b = fmt.getByteBuffer(key) ?: continue
            val arr = ByteArray(b.remaining())
            b.duplicate().get(arr)
            csd.add(arr)
        }
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(width)
            .setHeight(height)
            .setFrameRate(FRAME_RATE.toFloat())
            .setInitializationData(csd)
            .build()
        val buffer = SegmentBuffer(SEGMENT_CAP)
        val muxer = FragmentedMp4Muxer.Builder(buffer).setFragmentDurationMs(1000).build()
        trackId = muxer.addTrack(format)
        return Segment(done.size + pendingSegments.get(), buffer, muxer, startUs, startUs, 0, 0)
    }

    // Closes the muxer (which flushes the last fragment into the buffer) and
    // hands the bytes to the encrypt thread as one item object.
    private fun finishSegment(vault: Vault, seg: Segment) {
        try {
            seg.muxer.close()
        } catch (e: Exception) {
            say("video_probe: muxer close failed: ${e.message}")
            return
        }
        seg.buffer.close()
        val bytes = seg.buffer.bytes()
        maxSegmentBytes = maxOf(maxSegmentBytes, bytes.size)
        val pending = pendingSegments.incrementAndGet()
        maxPending = maxOf(maxPending, pending)
        val latch = CountDownLatch(1)
        encryptJobs.add(latch)
        val durationUs = seg.endUs - seg.startUs + 1_000_000L / FRAME_RATE
        encryptExecutor.execute {
            try {
                val t0 = System.nanoTime()
                val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_SEGMENT, rotation, width, height, System.currentTimeMillis())
                val stored = vault.encryptItemArray(bytes, 0, bytes.size, meta)
                val ms = (System.nanoTime() - t0) / 1_000_000
                synchronized(done) {
                    done.add(Done(seg.index, stored.id, seg.startUs, durationUs, bytes.size, seg.samples, seg.syncFrames, ms))
                }
            } catch (e: Exception) {
                say("video_probe: segment ${seg.index} encrypt failed: ${e.message}")
                stopReason = "ENCRYPT_FAILED"
            } finally {
                bytes.fill(0)
                pendingSegments.decrementAndGet()
                latch.countDown()
            }
        }
    }

    // Index object (media_type 3), prototype layout from the design doc.
    private fun writeIndex(vault: Vault): ByteArray? {
        if (done.isEmpty()) return null
        val segs = done.sortedBy { it.index }
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun u32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xff).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xff).toInt()) }
        out.write("PVVIDX01".toByteArray(Charsets.US_ASCII))
        u16(1)
        u16(segs.size)
        val codec = ByteArray(16)
        val m = MIME.toByteArray(Charsets.US_ASCII)
        System.arraycopy(m, 0, codec, 0, minOf(16, m.size))
        out.write(codec)
        u32(width.toLong()); u32(height.toLong()); u32(FRAME_RATE.toLong())
        for (d in segs) {
            out.write(d.id)
            u64(d.startUs); u64(d.durationUs); u32(d.syncFrames.toLong()); u32(0)
        }
        val bytes = out.toByteArray()
        val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_INDEX, rotation, width, height, System.currentTimeMillis())
        return try {
            vault.encryptItemArray(bytes, 0, bytes.size, meta).id
        } catch (e: Exception) {
            say("video_probe: index encrypt failed: ${e.message}")
            null
        }
    }

    // Decrypts each segment into memory, parses it with MediaExtractor over a
    // MediaDataSource, and decodes its first frame. Debug-only readback.
    private fun verify(vault: Vault) {
        for (d in done.sortedBy { it.index }) {
            val status = IntArray(1)
            val plain = NativeBridge.nativeDecryptToArray(vault.rawHandle, d.id, SEGMENT_CAP + 64, status)
            if (plain == null) {
                say("video_probe: verify segment ${d.index}: decrypt ${NativeBridge.errorName(status[0])}")
                continue
            }
            try {
                val payload = plain.copyOfRange(NativeBridge.ITEM_HEADER_BYTES, plain.size)
                val r = SegmentVerify.inspect(payload)
                say("video_probe: verify segment ${d.index}: payload=${payload.size} ${r}")
                payload.fill(0)
            } finally {
                plain.fill(0)
            }
        }
    }

    private fun releaseEncoder() {
        try {
            encoder?.stop()
        } catch (e: Exception) {
        }
        try {
            encoder?.release()
        } catch (e: Exception) {
        }
        encoderSurface?.release()
        encoder = null
        encoderSurface = null
    }

    private fun freeBytes(): Long = StatFs(vaults.filesDir.path).availableBytes
    private fun lowSpaceThreshold(): Long = CaptureSession.LOW_SPACE_BYTES + 4L * maxSegmentBytes
    private fun lowSpace(): Boolean = freeBytes() < lowSpaceThreshold()

    private fun cpuTicks(): Long {
        return try {
            val stat = File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            fields[11].toLong() + fields[12].toLong()   // utime + stime, in clock ticks (10 ms)
        } catch (e: Exception) {
            0
        }
    }

    private inner class Sampler : Thread("pv-video-sample") {
        @Volatile private var running = true
        override fun run() {
            while (running) {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                pssPeak = maxOf(pssPeak, mi.totalPss)
                nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                try {
                    sleep(200)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
        fun finish() {
            running = false
            interrupt()
        }
    }
}

// Parses a fragmented MP4 held in memory and decodes its first frame.
object SegmentVerify {
    class ArraySource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    fun inspect(payload: ByteArray): String {
        val ftyp = payload.size >= 8 && String(payload, 4, 4, Charsets.US_ASCII) == "ftyp"
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ArraySource(payload))
            if (extractor.trackCount < 1) return "ftyp=$ftyp tracks=0"
            val fmt = extractor.getTrackFormat(0)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val duration = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1
            extractor.selectTrack(0)
            var samples = 0
            var syncs = 0
            var firstUs = -1L
            var lastUs = 0L
            val sampleBuf = ByteBuffer.allocate(2 shl 20)
            val decoder = MediaCodec.createDecoderByType(mime!!)
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            var decodedFirst = "no"
            var decodedBytes = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val n = extractor.readSampleData(sampleBuf, 0)
                        if (n < 0) {
                            val inIdx = decoder.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        } else {
                            samples++
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs++
                            if (firstUs < 0) firstUs = extractor.sampleTime
                            lastUs = extractor.sampleTime
                            if (decodedFirst == "no") {
                                val inIdx = decoder.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    val ib = decoder.getInputBuffer(inIdx)!!
                                    ib.clear()
                                    ib.put(sampleBuf.duplicate().apply { limit(n) })
                                    decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                }
                            }
                            extractor.advance()
                        }
                    }
                    if (decodedFirst == "no") {
                        val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                        if (outIdx >= 0) {
                            if (info.size > 0) {
                                decodedFirst = "yes"
                                decodedBytes = info.size
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                    } else if (inputDone) {
                        break
                    }
                    if (inputDone && samples > 0 && decodedFirst == "no") {
                        // Keep draining output a little longer after EOS.
                        val outIdx = decoder.dequeueOutputBuffer(info, 200_000)
                        if (outIdx >= 0) {
                            if (info.size > 0) { decodedFirst = "yes"; decodedBytes = info.size }
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    }
                }
            } finally {
                decoder.stop()
                decoder.release()
            }
            val spanMs = if (firstUs >= 0) (lastUs - firstUs) / 1000 else -1
            return "ftyp=$ftyp mime=$mime ${w}x$h duration_ms=${if (duration >= 0) duration / 1000 else "n/a"} samples=$samples sync=$syncs span_ms=$spanMs first_frame_decoded=$decodedFirst (${decodedBytes} bytes)"
        } catch (e: Exception) {
            return "ftyp=$ftyp extractor failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            extractor.release()
        }
    }
}
