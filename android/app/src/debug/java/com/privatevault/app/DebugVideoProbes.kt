package com.privatevault.app

import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
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

// Video prototype, debug variant only (docs/PHASE2_DESIGN.md Section 8,
// docs/PHASE3C_DESIGN.md Sections 3 and 5). Installed into VideoProbes by
// reflection from PvApp. video_probe records a clip at the approved Stage 3C
// settings and writes index v1; video_play opens the playback probe on an
// index object. The Phase 2 V2 probe and the V3a attempt needed camera-video,
// which Stage 3B removed; their results stay in
// docs/VIDEO_CAPTURE_FEASIBILITY.md.
@Suppress("unused")
@androidx.annotation.OptIn(UnstableApi::class)
class DebugVideoProbes : VideoProbeRunner {
    init {
        VideoProbes.install(this)
        VideoProbes.playback = { context, arg ->
            context.startActivity(
                Intent(context, VideoProbeActivity::class.java)
                    .putExtra(VideoProbeActivity.EXTRA_ARG, arg)
                    .putExtra(VideoProbeActivity.EXTRA_AUTO, true)
            )
        }
    }

    override fun run(
        name: String, activity: CaptureActivity, imageCapture: ImageCapture?, camera: Camera?,
        provider: ProcessCameraProvider?, lensFacing: Int, vaults: VaultSession, say: (String) -> Unit
    ) {
        val p = provider ?: throw IllegalStateException("camera provider not ready")
        when {
            // video_probe or video_probe=<clip ms>[:<fragment ms>[:<I-frame backstop s>]]
            name == "video_probe" || name.startsWith("video_probe=") -> {
                val parts = name.substringAfter('=', "").split(':').filter { it.isNotEmpty() }
                val clipMs = parts.getOrNull(0)?.toLongOrNull() ?: 12_000L
                val fragmentMs = parts.getOrNull(1)?.toIntOrNull() ?: V3Probe.FRAGMENT_MS
                val backstop = parts.getOrNull(2)?.toIntOrNull() ?: V3Probe.IFRAME_BACKSTOP_S
                V3Probe(activity, activity, p, lensFacing, vaults, say, clipMs, fragmentMs, backstop).run()
            }
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

// V3b at the Stage 3C settings (docs/PHASE3C_DESIGN.md Section 3): the
// Preview use case feeds a MediaCodec input surface, so there is no on-screen
// viewfinder while recording. The camera frame rate is measured first over
// an ImageReader; the encoder gets that rate, a 10 s I-frame backstop, and a
// sync request each second of PTS. A segment closes on the encoder's actual
// key-frame output, when the key frame after its third GOP arrives. Sample
// times inside a segment start at zero; the index carries the clip offsets.
@androidx.annotation.OptIn(UnstableApi::class)
class V3Probe(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val provider: ProcessCameraProvider,
    private val lensFacing: Int,
    private val vaults: VaultSession,
    private val say: (String) -> Unit,
    private val clipMs: Long = 12_000L,
    private val fragmentMs: Int = FRAGMENT_MS,
    private val iframeBackstopS: Int = IFRAME_BACKSTOP_S
) {
    companion object {
        const val KEYS_PER_SEGMENT = 3
        const val GOP_US = 1_000_000L
        const val FRAGMENT_MS = 1000
        const val IFRAME_BACKSTOP_S = 10
        const val SEGMENT_CAP = 8 shl 20
        const val MAX_PENDING_SEGMENTS = 2
        const val BITRATE = 4_000_000
        const val DEFAULT_FRAME_RATE = 30
        const val MEASURE_MS = 1500L
        const val MIME = "video/avc"
    }

    private class Segment(
        val index: Int, val buffer: SegmentBuffer, val muxer: FragmentedMp4Muxer, val startUs: Long,
        var endUs: Long, var samples: Int, val keyPtsUs: ArrayList<Long>, var firstSampleKey: Boolean
    )

    // One finished segment: what was recorded and what the index records.
    class Done(
        val index: Int, val id: ByteArray, val startUs: Long, val durationUs: Long, val bytes: Int,
        val samples: Int, val keyPtsUs: List<Long>, val firstSampleKey: Boolean, val encryptMs: Long
    ) {
        val hex: String get() = NativeBridge.hex(id)
        fun keyOffsetsUs(): IntArray = IntArray(keyPtsUs.size) { (keyPtsUs[it] - startUs).toInt() }
    }

    // Per segment, the key-frame counts from the four readers and the index.
    class Counts(
        val segment: Int, val encoderKeys: Int, val containerSyncs: Int, val frameworkSyncs: Int,
        val media3Syncs: Int, val indexKeys: Int, val containerFirstSync: Boolean?, val verifyLine: String,
        val containerSyncTimesUs: List<Long>
    )

    class Outcome(
        val frameRate: Int, val frameRateMeasured: Boolean, val width: Int, val height: Int, val rotation: Int, val frames: Int,
        val stopReason: String, val segments: List<Done>, val indexId: ByteArray?, val index: VideoIndex?,
        val counts: List<Counts>, val leakReport: LeakScan.Report, val newFiles: List<String>
    )

    private val main = Handler(Looper.getMainLooper())
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderFormat: MediaFormat? = null
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var frameRate = DEFAULT_FRAME_RATE
    private var frameRateMeasured = false
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
    private var clipStartMs = 0L

    // Frame-rate measurement: the Preview feeds an ImageReader for a moment
    // and each delivered image is counted and closed at once.
    private inner class RateMeter {
        val thread = HandlerThread("pv-rate").also { it.start() }
        val handler = Handler(thread.looper)
        var reader: ImageReader? = null
        val count = AtomicInteger(0)
        @Volatile var firstNs = 0L
        @Volatile var lastNs = 0L
        val ready = CountDownLatch(1)
        val released = CountDownLatch(1)

        fun onSurfaceRequested(request: SurfaceRequest) {
            val res = request.resolution
            // A GPU-sampled usage: the Xiaomi HAL delivered nothing to a
            // private reader with the default usage (Milestone E).
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageReader.newInstance(res.width, res.height, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            } else {
                ImageReader.newInstance(res.width, res.height, ImageFormat.PRIVATE, 4)
            }
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                img.close()
                val now = System.nanoTime()
                if (count.getAndIncrement() == 0) firstNs = now
                lastNs = now
            }, handler)
            reader = r
            request.provideSurface(r.surface, { it.run() }) { released.countDown() }
            ready.countDown()
        }

        fun close() {
            released.await(3, TimeUnit.SECONDS)
            reader?.close()
            thread.quitSafely()
        }

        // Frames per second over the measured span, rounded; 0 if too few frames.
        fun fps(): Int {
            val n = count.get()
            if (n < 5 || lastNs <= firstNs) return 0
            return Math.round((n - 1) * 1_000_000_000.0 / (lastNs - firstNs)).toInt()
        }
    }

    // Creates the encoder at the requested resolution and provides its input surface.
    private fun onSurfaceRequested(request: SurfaceRequest) {
        try {
            val res = request.resolution
            width = res.width
            height = res.height
            say("video_probe: surface requested ${width}x$height, dynamic range ${request.dynamicRange}")
            request.setTransformationInfoListener({ it.run() }) { info ->
                rotation = info.rotationDegrees
                say("video_probe: transformation rotation=${info.rotationDegrees} crop=${info.cropRect}")
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeBackstopS)
            }
            say("video_probe: settings frame_rate=$frameRate gop_us=$GOP_US keys_per_segment=$KEYS_PER_SEGMENT fragment_ms=$fragmentMs iframe_backstop_s=$iframeBackstopS clip_ms=$clipMs bitrate=$BITRATE")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = surface
            request.provideSurface(surface, { it.run() }) { result ->
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

    fun run(): Outcome {
        val vault = vaults.vault ?: throw IllegalStateException("vault not open")
        val before = LeakScan.run(context)
        val cpu0 = cpuTicks()
        val wall0 = System.nanoTime()
        val sampler = Sampler()
        sampler.start()
        var frameCount = 0
        try {
            measureFrameRate()
            if (stopReason == null) {
                bind()
                if (!surfaceLatch.await(5, TimeUnit.SECONDS) || encoder == null) {
                    say("video_probe: FAIL no encoder surface (${stopReason ?: "no request"})")
                    stopReason = stopReason ?: "NO_SURFACE"
                } else {
                    frameCount = drain(vault)
                }
            }
        } finally {
            sampler.finish()
            unbind()
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
        val segments = synchronized(done) { done.sortedBy { it.index } }
        for (d in segments) {
            say("video_probe: segment ${d.index} ${d.hex} start=${d.startUs / 1000}ms duration=${d.durationUs / 1000}ms bytes=${d.bytes} samples=${d.samples} keys=${d.keyPtsUs.size} first_sample_key=${d.firstSampleKey} encrypt=${d.encryptMs}ms")
            payloadTotal += d.bytes
            encryptTotal += d.encryptMs
        }
        if (encryptTotal > 0) say("video_probe: encrypt throughput ${payloadTotal * 1000 / encryptTotal / 1024} KiB/s over ${segments.size} segments")
        val (indexId, index) = writeIndex(vault, segments, frameCount)
        say("video_probe: index object ${if (indexId != null) NativeBridge.hex(indexId) else "none"}")
        val counts = verify(vault, segments, indexId)
        val after = LeakScan.run(context)
        for (l in after.lines()) say("video_probe: $l")
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") }
        say("video_probe: new non-object files after probe: ${fresh.size}${if (fresh.isNotEmpty()) " " + fresh.map { it.path } else ""}")
        return Outcome(frameRate, frameRateMeasured, width, height, rotation, frameCount, stopReason ?: "clip end", segments, indexId, index,
            counts, after, fresh.map { it.path })
    }

    // Binds the Preview to an ImageReader for MEASURE_MS after the first frame.
    private fun measureFrameRate() {
        val meter = RateMeter()
        val error = bindPreview { request -> meter.onSurfaceRequested(request) }
        if (error != null) {
            say("video_probe: rate bind failed: $error")
            stopReason = "BIND_FAILED"
            meter.close()
            return
        }
        if (!meter.ready.await(5, TimeUnit.SECONDS)) {
            say("video_probe: rate measurement got no surface request")
        } else {
            val deadline = System.nanoTime() + 6_000_000_000L
            while (meter.count.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
            Thread.sleep(MEASURE_MS)
        }
        unbind()
        meter.close()
        val fps = meter.fps()
        frameRateMeasured = fps > 0
        frameRate = if (fps > 0) fps else DEFAULT_FRAME_RATE
        say("video_probe: camera frame rate measured ${meter.count.get()} frames in ${(meter.lastNs - meter.firstNs) / 1_000_000}ms: $fps fps, encoder gets $frameRate")
    }

    private fun bind() {
        val error = bindPreview { request -> onSurfaceRequested(request) }
        if (error != null) {
            say("video_probe: bind failed: $error")
            stopReason = "BIND_FAILED"
            surfaceLatch.countDown()
        }
    }

    // Binds one Preview use case on the main thread. Returns the error text or null.
    private fun bindPreview(onRequest: (SurfaceRequest) -> Unit): String? {
        val latch = CountDownLatch(1)
        var error: String? = null
        main.post {
            try {
                provider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val preview = Preview.Builder().build()
                preview.surfaceProvider = Preview.SurfaceProvider { request -> onRequest(request) }
                provider.bindToLifecycle(owner, selector, preview)
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return "bind timed out"
        return error
    }

    private fun unbind() {
        val latch = CountDownLatch(1)
        main.post {
            try {
                provider.unbindAll()
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    // Pulls encoded frames from the encoder for clipMs. A sync frame is asked
    // for one GOP after the last key frame; segments are cut when a key frame
    // arrives and the current segment already holds KEYS_PER_SEGMENT.
    private fun drain(vault: Vault): Int {
        val codec = encoder ?: return 0
        val info = MediaCodec.BufferInfo()
        val clipStart = System.nanoTime()
        clipStartMs = System.currentTimeMillis()
        var requestedSync = false
        var lastKeyPts = -1L
        var count = 0
        while (true) {
            val elapsedMs = (System.nanoTime() - clipStart) / 1_000_000
            if (elapsedMs >= clipMs && stopReason == null) {
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
                if (stopReason != null && elapsedMs > clipMs + 2000) break
                continue
            }
            val buf = codec.getOutputBuffer(idx)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                say("enc cfg pts=${info.presentationTimeUs} size=${info.size}")
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
                if (sync) {
                    lastKeyPts = pts
                    requestedSync = false
                } else if (!requestedSync && lastKeyPts >= 0 && pts - lastKeyPts >= GOP_US) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    requestedSync = true
                    say("enc sync_request at pts=$pts")
                }
                val seg = current
                val cut = seg != null && sync && seg.keyPtsUs.size >= KEYS_PER_SEGMENT
                if (seg == null || cut) {
                    if (seg != null) finishSegment(vault, seg)
                    if (!sync) {
                        // The very first frame should be a sync frame; keep waiting.
                        say("enc #$count pts=$pts size=${info.size} key=0 dropped=before_first_sync")
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
                    current = startSegment(pts)
                }
                val s = current!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val muxFlags = if (sync) C.BUFFER_FLAG_KEY_FRAME else 0
                // One line per encoder buffer: clip PTS, what the encoder flagged, and what the muxer is given.
                say("enc #$count seg=${s.index} pts=$pts size=${info.size} key=${if (sync) 1 else 0} enc_flags=${info.flags} mux_flags=$muxFlags")
                try {
                    s.muxer.writeSampleData(trackId, buf, BufferInfo(pts - s.startUs, info.size, muxFlags))
                } catch (e: Exception) {
                    say("video_probe: muxer write failed: ${e.javaClass.simpleName}: ${e.message}")
                    stopReason = "MUXER_WRITE_FAILED"
                    codec.releaseOutputBuffer(idx, false)
                    codec.signalEndOfInputStream()
                    current = null
                    break
                }
                if (s.samples == 0) s.firstSampleKey = sync
                s.samples++
                if (sync) s.keyPtsUs.add(pts)
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
            .setFrameRate(frameRate.toFloat())
            .setInitializationData(csd)
            .build()
        val buffer = SegmentBuffer(SEGMENT_CAP)
        val muxer = FragmentedMp4Muxer.Builder(buffer).setFragmentDurationMs(fragmentMs.toLong()).build()
        trackId = muxer.addTrack(format)
        return Segment(done.size + pendingSegments.get(), buffer, muxer, startUs, startUs, 0, ArrayList(), false)
    }

    private fun framePeriodUs(): Long = 1_000_000L / frameRate

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
        val durationUs = seg.endUs - seg.startUs + framePeriodUs()
        val keys = ArrayList(seg.keyPtsUs)
        encryptExecutor.execute {
            try {
                val t0 = System.nanoTime()
                val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_SEGMENT, rotation, width, height, System.currentTimeMillis())
                val stored = vault.encryptItemArray(bytes, 0, bytes.size, meta)
                val ms = (System.nanoTime() - t0) / 1_000_000
                synchronized(done) {
                    done.add(Done(seg.index, stored.id, seg.startUs, durationUs, bytes.size, seg.samples, keys, seg.firstSampleKey, ms))
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

    // Frames per second the encoder output showed over the clip, rounded.
    private fun deliveredRate(frames: Int): Int {
        val span = lastPtsUs - firstPtsUs
        if (frames < 5 || span <= 0) return 0
        return Math.round((frames - 1) * 1_000_000.0 / span).toInt()
    }

    // Index v1 (media_type 3) over the finished segments, through the core.
    // The index records the delivered rate: the meter's when it measured
    // one, otherwise the rate of the encoder output over the clip.
    private fun writeIndex(vault: Vault, segments: List<Done>, frames: Int): Pair<ByteArray?, VideoIndex?> {
        if (segments.isEmpty()) return null to null
        val delivered = deliveredRate(frames)
        val indexRate = if (frameRateMeasured) frameRate else if (delivered > 0) delivered else frameRate
        say("video_probe: delivered rate ${delivered} fps over the clip, encoder configured $frameRate, index records $indexRate")
        val index = VideoIndex(
            VideoIndex.Header(
                NativeBridge.VIDEO_CODEC_H264, rotation, width, height, indexRate * 1000,
                segments.last().endUs(), clipStartMs
            ),
            segments.map { VideoIndex.Segment(it.id, it.startUs, it.durationUs, it.bytes.toLong(), it.keyOffsetsUs()) }
        )
        val bytes = try {
            index.encode()
        } catch (e: Vault.CoreException) {
            say("video_probe: index encode failed: ${e.message}")
            return null to null
        }
        for (s in index.segments) {
            say("video_probe: index segment ${s.hex} start_us=${s.startUs} duration_us=${s.durationUs} bytes=${s.byteLen} key_offsets_us=${s.keyOffsetsUs.joinToString(",")}")
        }
        val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_INDEX, rotation, width, height, clipStartMs)
        return try {
            val id = vault.encryptItemArray(bytes, 0, bytes.size, meta).id
            say("video_probe: index bytes=${bytes.size} segments=${index.segments.size} keys=${index.keysTotal} total_duration_us=${index.header.totalDurationUs} frame_rate_millihz=${index.header.frameRateMillihz}")
            id to index
        } catch (e: Exception) {
            say("video_probe: index encrypt failed: ${e.message}")
            null to null
        }
    }

    private fun Done.endUs(): Long = startUs + durationUs

    // Decrypts each segment into memory, parses it with MediaExtractor over a
    // MediaDataSource, decodes its first frame, and sets the key-frame counts
    // of the encoder, the container, both extractors, and the index object
    // read back through the core side by side. Debug-only readback.
    private fun verify(vault: Vault, segments: List<Done>, indexId: ByteArray?): List<Counts> {
        val out = ArrayList<Counts>()
        val back = if (indexId != null) VideoIndex.read(vault, indexId) else null
        if (back != null) say("video_probe: index read back: ${NativeBridge.errorName(back.status)} segments=${back.index?.segments?.size ?: 0} keys=${back.index?.keysTotal ?: 0}")
        for (d in segments) {
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
                val boxes = Mp4Boxes.walk(ByteBuffer.wrap(payload))
                say("video_probe: segment ${d.index} ${boxes.line()} container_sync=${boxes.containerSyncCount()} first_sample_sync=${boxes.firstSampleSync()}")
                val media3 = Media3Sync.count(payload)
                say("video_probe: segment ${d.index} media3 default: $media3")
                say("video_probe: segment ${d.index} encoder_key_offsets_us=${d.keyOffsetsUs().joinToString(",")} container_first_sample_times_us=${media3.sampleTimesUs.joinToString(",")}")
                val indexSeg = back?.index?.segments?.getOrNull(d.index)
                val indexKeys = indexSeg?.keyOffsetsUs?.size ?: -1
                val indexMatch = indexSeg != null && indexSeg.startUs == d.startUs &&
                    indexSeg.keyOffsetsUs.contentEquals(d.keyOffsetsUs())
                val framework = r.substringAfter("sync=", "-1").substringBefore(" ").toIntOrNull() ?: -1
                say("video_probe: segment ${d.index} counts: encoder_key=${d.keyPtsUs.size} container=${boxes.containerSyncCount()} framework_extractor=$framework media3=${media3.syncSamples} index=$indexKeys index_times_match=$indexMatch first_sample_sync=${boxes.firstSampleSync()} first_sample_key=${d.firstSampleKey}")
                out.add(Counts(d.index, d.keyPtsUs.size, boxes.containerSyncCount(), framework, media3.syncSamples, indexKeys, boxes.firstSampleSync(), r, media3.syncTimesUs))
                payload.fill(0)
            } finally {
                plain.fill(0)
            }
        }
        return out
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

    // Mean of every 97th byte of the luma plane (the first w*h bytes of a
    // planar or semi-planar YUV output), 0 to 255; -1 when there is none.
    private fun meanLuma(buf: ByteBuffer?, lumaBytes: Int): Long {
        if (buf == null) return -1
        val n = minOf(buf.remaining(), lumaBytes)
        if (n <= 0) return -1
        var sum = 0L
        var count = 0L
        var i = buf.position()
        val end = buf.position() + n
        while (i < end) {
            sum += buf.get(i).toInt() and 0xff
            count++
            i += 97
        }
        return if (count > 0) sum / count else -1
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
            var decodedLuma = -1L
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
                                decodedLuma = meanLuma(decoder.getOutputBuffer(outIdx), w * h)
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
                            if (info.size > 0) { decodedFirst = "yes"; decodedBytes = info.size; decodedLuma = meanLuma(decoder.getOutputBuffer(outIdx), w * h) }
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
            return "ftyp=$ftyp mime=$mime ${w}x$h duration_ms=${if (duration >= 0) duration / 1000 else "n/a"} samples=$samples sync=$syncs span_ms=$spanMs first_frame_decoded=$decodedFirst (${decodedBytes} bytes) first_frame_mean_luma=$decodedLuma"
        } catch (e: Exception) {
            return "ftyp=$ftyp extractor failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            extractor.release()
        }
    }
}
