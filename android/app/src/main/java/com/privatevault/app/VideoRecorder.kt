package com.privatevault.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Records a clip into segment objects and an index (docs/PHASE3E_DESIGN.md
// Sections 4.2 to 4.7). One camera stream goes through a CameraEffect whose
// processor draws every frame to the viewfinder surface and to the encoder
// input surface. Encoder output buffers go straight into the core segment
// writer (guarded memory); a segment closes on the third key frame and is
// encrypted on the process-scoped encrypt thread from that buffer; the index
// is written after the last segment. Every way a recording ends goes through
// stop(reason). Process scoped, like CaptureSession; the capture screen
// starts and stops it and the lock stops it. Lines carry counts, times, and
// object IDs only.
class VideoRecorder(
    private val context: Context,
    private val vaults: VaultSession,
    private val captures: CaptureSession
) {
    companion object {
        const val TAG = "PrivateVault"
        const val KEYS_PER_SEGMENT = 3
        const val GOP_US = 1_000_000L
        const val IFRAME_BACKSTOP_S = 10
        const val SEGMENT_CAP = 8L shl 20
        const val MAX_PENDING_SEGMENTS = 2
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080
        const val TARGET_FPS = 30
        const val BITRATE_1080 = 8_000_000
        const val BITRATE_720 = 5_000_000
        // The lock waits this long for a recording to finish and index.
        const val STOP_WAIT_MS = 4000L
        // After the end of stream was signalled, the drain waits this long for it.
        const val EOS_WAIT_MS = 2000L
        const val SURFACE_WAIT_MS = 5000L
        const val MIME = "video/avc"
    }

    enum class State { IDLE, RECORDING, STOPPING }

    class Result(
        val reason: String, val durationUs: Long, val segments: Int, val indexHex: String?,
        val width: Int, val height: Int, val rotation: Int, val frames: Int, val bytes: Long,
        val deliveredFps: Int, val abandoned: Boolean, val error: String?
    ) {
        val ok: Boolean get() = indexHex != null
        fun line(): String =
            "record: done reason=$reason duration_ms=${durationUs / 1000} segments=$segments index=${indexHex ?: "none"} " +
                "${width}x$height rotation=$rotation frames=$frames bytes=$bytes fps=$deliveredFps abandoned=$abandoned" +
                (if (error != null) " error=$error" else "")
    }

    // Test hooks: a stop forced at the next segment start by its reason, and
    // an index left unwritten (a process kill stand-in).
    class TestHooks {
        @Volatile var stopAt: String? = null
        @Volatile var skipIndex = false
    }

    val testHooks = TestHooks()

    private class Segment(val index: Int, val writer: SegmentWriter, val startUs: Long) {
        var endUs = startUs
        var samples = 0
        val keyPtsUs = ArrayList<Long>()
    }

    class Done(val index: Int, val id: ByteArray, val startUs: Long, val durationUs: Long, val bytes: Long, val keyOffsetsUs: IntArray) {
        val hex: String get() = NativeBridge.hex(id)
    }

    private val main = Handler(Looper.getMainLooper())
    private val splitExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-split-cb") }
    @Volatile var state = State.IDLE
        private set
    @Volatile var lastResult: Result? = null
        private set
    // The segments encrypted so far of the current recording (tests read it).
    val done = ArrayList<Done>()
    @Volatile var lastIndexHex: String? = null
        private set

    // Per recording.
    private var provider: ProcessCameraProvider? = null
    private var splitter: GlSplitter? = null
    private var encoderOutput: GlSplitter.Output? = null
    private var viewOutput: GlSplitter.Output? = null
    // Frames drawn to the viewfinder, kept when CameraX closes that output.
    @Volatile private var viewfinderFrames = 0
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderFormat: MediaFormat? = null
    private var width = 0
    private var height = 0
    @Volatile private var rotation = 0
    private var surfaceLatch = CountDownLatch(1)
    private var doneLatch = CountDownLatch(1)
    @Volatile private var stopReason: String? = null
    @Volatile private var abandoned = false
    @Volatile private var setupError: String? = null
    private val pendingSegments = AtomicInteger(0)
    private val encryptJobs = ArrayList<CountDownLatch>()
    private var maxSegmentBytes = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = 0L
    private var frames = 0
    private var bytesEncoded = 0L
    private var clipStartMs = 0L
    @Volatile private var firstFrameNs = 0L
    private var indexWritten = false
    private val indexLock = Any()

    val isRecording: Boolean get() = state != State.IDLE

    // Wall time since the first encoded frame, for the elapsed label.
    val elapsedMs: Long get() = if (firstFrameNs == 0L || state == State.IDLE) 0 else (System.nanoTime() - firstFrameNs) / 1_000_000

    private fun say(line: String) {
        Log.i(TAG, line)
        captures.say(line)
    }

    // Starts a recording. The camera is bound on the main thread with the
    // viewfinder provider as the Preview output; the work runs on an own
    // thread until onDone. Returns a reason when nothing was started.
    @Synchronized
    fun start(p: ProcessCameraProvider, owner: LifecycleOwner, lensFacing: Int, viewfinder: Preview.SurfaceProvider, onDone: (Result) -> Unit): String? {
        if (state != State.IDLE) return "BUSY"
        if (vaults.vault == null) return "VAULT_CLOSED"
        state = State.RECORDING
        provider = p
        resetRun()
        Thread({ run(p, owner, lensFacing, viewfinder, onDone) }, "pv-record").start()
        return null
    }

    private fun resetRun() {
        splitter = null
        encoderOutput = null
        viewOutput = null
        viewSurfaceOutput = null
        viewfinderFrames = 0
        encoder = null
        encoderSurface = null
        encoderFormat = null
        width = 0
        height = 0
        rotation = 0
        surfaceLatch = CountDownLatch(1)
        doneLatch = CountDownLatch(1)
        stopReason = null
        abandoned = false
        setupError = null
        pendingSegments.set(0)
        synchronized(encryptJobs) { encryptJobs.clear() }
        synchronized(done) { done.clear() }
        maxSegmentBytes = 0
        firstPtsUs = -1
        lastPtsUs = 0
        frames = 0
        bytesEncoded = 0
        clipStartMs = 0
        firstFrameNs = 0
        indexWritten = false
        lastIndexHex = null
    }

    // Asks the recording to end. The drain loop finishes the open segment,
    // encrypts it, writes the index, and reports through onDone.
    fun stop(reason: String) {
        if (state != State.RECORDING) return
        if (stopReason == null) stopReason = reason
        state = State.STOPPING
    }

    // stop, then wait up to timeoutMs for the index. Past the bound the open
    // segment is dropped and the index written over the segments encrypted
    // so far. Returns whether the recording finished within the bound.
    fun stopAndWait(reason: String, timeoutMs: Long): Boolean {
        if (state == State.IDLE) return true
        stop(reason)
        if (doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return true
        abandon()
        return false
    }

    private fun abandon() {
        abandoned = true
        say("record: stop bound of ${STOP_WAIT_MS} ms passed, open segment dropped")
        writeIndex()
    }

    private fun run(p: ProcessCameraProvider, owner: LifecycleOwner, lensFacing: Int, viewfinder: Preview.SurfaceProvider, onDone: (Result) -> Unit) {
        var error: String? = null
        VideoItems.recordingGate.lock()
        try {
            val bindError = bind(p, owner, lensFacing, viewfinder)
            if (bindError != null) {
                stopReason = "BIND_FAILED"
                error = bindError
            } else if (!surfaceLatch.await(SURFACE_WAIT_MS, TimeUnit.MILLISECONDS) || encoder == null) {
                stopReason = stopReason ?: "NO_SURFACE"
                error = setupError ?: "no camera surface within ${SURFACE_WAIT_MS} ms"
            } else {
                try {
                    drain()
                } catch (e: Exception) {
                    if (stopReason == null) stopReason = "ENCODER_ERROR"
                    error = "${e.javaClass.simpleName}: ${e.message}"
                    say("record: encoder failed: $error")
                }
            }
        } finally {
            unbind(p)
            releaseEncoder()
            splitter?.let { s ->
                val shown = viewOutput?.frames?.get() ?: viewfinderFrames
                say("record: split camera_frames=${s.cameraFrames.get()} camera_fps=${s.cameraFps()} encoder_frames=${encoderOutput?.frames?.get()} viewfinder_frames=$shown")
                s.release()
            }
            splitter = null
            waitEncrypts()
            writeIndex()
            VideoItems.recordingGate.unlock()
        }
        val recordedUs = if (firstPtsUs >= 0) lastPtsUs - firstPtsUs else 0
        val segments = synchronized(done) { ArrayList(done) }
        val result = Result(
            stopReason ?: "unknown", segments.lastOrNull()?.let { it.startUs + it.durationUs } ?: 0L, segments.size, lastIndexHex,
            width, height, rotation, frames, bytesEncoded, deliveredRate(), abandoned, error
        )
        say("record: frames=$frames bytes=$bytesEncoded recorded_ms=${recordedUs / 1000} max_segment_bytes=$maxSegmentBytes")
        say(result.line())
        lastResult = result
        state = State.IDLE
        doneLatch.countDown()
        onDone(result)
    }

    // Binds the Preview with the split effect on the main thread.
    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner, lensFacing: Int, viewfinder: Preview.SurfaceProvider): String? {
        val latch = CountDownLatch(1)
        var error: String? = null
        main.post {
            try {
                p.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(TARGET_WIDTH, TARGET_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                    )
                    .setTargetFrameRate(Range(TARGET_FPS, TARGET_FPS))
                    .build()
                preview.surfaceProvider = viewfinder
                val group = UseCaseGroup.Builder().addUseCase(preview).addEffect(SplitEffect()).build()
                p.bindToLifecycle(owner, selector, group)
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return "bind timed out"
        return error
    }

    private fun unbind(p: ProcessCameraProvider) {
        val latch = CountDownLatch(1)
        main.post {
            try {
                p.unbindAll()
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    // The viewfinder output CameraX hands the effect. Its transform (the
    // camera transform of the device, the turn to the display, the crop) is
    // what the encoder draws with too, so the clip holds what the viewfinder
    // shows, upright, with rotation 0 in the index and the container.
    @Volatile private var viewSurfaceOutput: SurfaceOutput? = null

    private val processor = object : SurfaceProcessor {
        override fun onInputSurface(request: SurfaceRequest) {
            onCameraInput(request)
        }

        override fun onOutputSurface(output: SurfaceOutput) {
            val s = splitter
            if (s == null) {
                output.close()
                return
            }
            val size = output.size
            val surface = output.getSurface(splitExecutor) { event ->
                viewOutput?.let { o ->
                    viewfinderFrames = o.frames.get()
                    try { s.removeOutput(o) } catch (e: Exception) {}
                }
                viewOutput = null
                event.surfaceOutput.close()
            }
            try {
                viewOutput = s.addOutput("viewfinder", surface, size.width, size.height, false) { m ->
                    val updated = FloatArray(16)
                    output.updateTransformMatrix(updated, m)
                    updated
                }
                viewSurfaceOutput = output
                say("record: viewfinder ${size.width}x${size.height}")
            } catch (e: Exception) {
                say("record: viewfinder output failed: ${e.javaClass.simpleName}: ${e.message}")
                output.close()
            }
        }
    }

    private inner class SplitEffect : CameraEffect(CameraEffect.PREVIEW, splitExecutor, processor, { t ->
        say("record: effect error ${t.javaClass.simpleName}: ${t.message}")
        stop("EFFECT_ERROR")
    })

    // The camera input: the splitter, then the encoder at the turned stream
    // size (a quarter turn swaps width and height). The encoder draws with
    // the viewfinder matrix; frames before the viewfinder output exists are
    // not encoded.
    private fun onCameraInput(request: SurfaceRequest) {
        try {
            val res = request.resolution
            val info = CountDownLatch(1)
            var turn = 0
            request.setTransformationInfoListener({ it.run() }) { t ->
                turn = t.rotationDegrees
                say("record: stream ${res.width}x${res.height} turn=${t.rotationDegrees} camera_transform=${t.hasCameraTransform()} crop=${t.cropRect} mirror=${t.isMirroring}")
                info.countDown()
            }
            info.await(1, TimeUnit.SECONDS)
            val swap = turn == 90 || turn == 270
            val ew = if (swap) res.height else res.width
            val eh = if (swap) res.width else res.height
            rotation = 0
            val s = GlSplitter { line -> Log.i(TAG, line) }
            splitter = s
            val input = s.start(res.width, res.height)
            val enc = createEncoder(ew, eh)
            encoderOutput = s.addOutput("encoder", enc, ew, eh, true) { m ->
                val view = viewSurfaceOutput ?: return@addOutput null
                val updated = FloatArray(16)
                view.updateTransformMatrix(updated, m)
                updated
            }
            request.provideSurface(input, { it.run() }) { }
            surfaceLatch.countDown()
        } catch (e: Exception) {
            setupError = "${e.javaClass.simpleName}: ${e.message}"
            say("record: setup failed: $setupError")
            request.willNotProvideSurface()
            stopReason = "ENCODER_SETUP_FAILED"
            surfaceLatch.countDown()
        }
    }

    private fun createEncoder(w: Int, h: Int): Surface {
        width = w
        height = h
        val bitrate = if (w.toLong() * h >= TARGET_WIDTH.toLong() * TARGET_HEIGHT) BITRATE_1080 else BITRATE_720
        val codec = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_BACKSTOP_S)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        encoderSurface = surface
        say("record: encoder ${codec.name} ${w}x$h bitrate=$bitrate fps=$TARGET_FPS gop_us=$GOP_US keys_per_segment=$KEYS_PER_SEGMENT backstop_s=$IFRAME_BACKSTOP_S")
        return surface
    }

    // Pulls encoded frames until the stream ends. A sync frame is asked for
    // one GOP after the last key frame; a segment closes when a key frame
    // arrives and the segment already holds KEYS_PER_SEGMENT.
    private fun drain() {
        val codec = encoder ?: return
        val vault = vaults.vault ?: throw IllegalStateException("vault closed")
        val info = MediaCodec.BufferInfo()
        clipStartMs = System.currentTimeMillis()
        var current: Segment? = null
        var requestedSync = false
        var lastKeyPts = -1L
        var eosSignalled = false
        var eosNs = 0L
        try {
            while (true) {
                if (abandoned) break
                if (stopReason != null && !eosSignalled) {
                    codec.signalEndOfInputStream()
                    eosSignalled = true
                    eosNs = System.nanoTime()
                }
                val idx = codec.dequeueOutputBuffer(info, 20_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoderFormat = codec.outputFormat
                    continue
                }
                if (idx < 0) {
                    if (eosSignalled && System.nanoTime() - eosNs > EOS_WAIT_MS * 1_000_000) break
                    continue
                }
                val buf = codec.getOutputBuffer(idx)!!
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0) {
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) break
                    continue
                }
                val sync = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (firstPtsUs < 0) {
                    firstPtsUs = info.presentationTimeUs
                    firstFrameNs = System.nanoTime()
                }
                val pts = info.presentationTimeUs - firstPtsUs
                lastPtsUs = info.presentationTimeUs
                bytesEncoded += info.size
                frames++
                if (sync) {
                    lastKeyPts = pts
                    requestedSync = false
                } else if (!requestedSync && lastKeyPts >= 0 && pts - lastKeyPts >= GOP_US) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    requestedSync = true
                }
                var seg = current
                val cut = seg != null && sync && seg.keyPtsUs.size >= KEYS_PER_SEGMENT
                if (seg == null || cut) {
                    if (seg != null) {
                        finishSegment(vault, seg, pts - seg.startUs)
                        current = null
                        seg = null
                    }
                    if (!sync) {
                        codec.releaseOutputBuffer(idx, false)
                        if (eos) break
                        continue
                    }
                    val refuse = segmentStartRefusal()
                    if (refuse != null) {
                        if (stopReason == null) stopReason = refuse
                        say("record: stopping at a segment boundary: $refuse")
                        codec.releaseOutputBuffer(idx, false)
                        if (!eosSignalled) {
                            codec.signalEndOfInputStream()
                            eosSignalled = true
                            eosNs = System.nanoTime()
                        }
                        if (refuse == "ENCODER_ERROR") throw IllegalStateException("encoder error hook")
                        continue
                    }
                    seg = startSegment(pts)
                    current = seg
                }
                val s = seg!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val rc = s.writer.add(buf, pts - s.startUs, sync)
                if (rc != NativeBridge.OK) {
                    say("record: segment ${s.index} add ${NativeBridge.errorName(rc)} at ${pts / 1000} ms")
                    if (stopReason == null) stopReason = if (rc == NativeBridge.ERR_SEGMENT_FULL) "SEGMENT_FULL" else NativeBridge.nativeErrorName(rc)
                    codec.releaseOutputBuffer(idx, false)
                    if (!eosSignalled) {
                        codec.signalEndOfInputStream()
                        eosSignalled = true
                        eosNs = System.nanoTime()
                    }
                    if (eos) break
                    continue
                }
                s.samples++
                if (sync) s.keyPtsUs.add(pts)
                s.endUs = pts
                codec.releaseOutputBuffer(idx, false)
                if (eos) break
            }
        } finally {
            val seg = current
            if (seg != null) {
                if (abandoned) {
                    seg.writer.close()
                    say("record: segment ${seg.index} dropped")
                } else {
                    finishSegment(vault, seg, seg.endUs - seg.startUs + framePeriodUs())
                }
            }
            if (stopReason == null) stopReason = "clip end"
        }
    }

    private fun framePeriodUs(): Long = 1_000_000L / TARGET_FPS

    // Why the next segment cannot start, or null.
    private fun segmentStartRefusal(): String? {
        val hook = testHooks.stopAt
        if (hook != null) {
            testHooks.stopAt = null
            return hook
        }
        if (pendingSegments.get() >= MAX_PENDING_SEGMENTS) return "QUEUE_FULL"
        if (captures.freeSpaceBytes() < CaptureSession.LOW_SPACE_BYTES + 4 * maxSegmentBytes) return "LOW_STORAGE"
        return null
    }

    private fun startSegment(startUs: Long): Segment {
        val fmt = encoderFormat ?: throw IllegalStateException("encoder format unknown")
        val sps = fmt.getByteBuffer("csd-0") ?: throw IllegalStateException("no csd-0")
        val pps = fmt.getByteBuffer("csd-1") ?: throw IllegalStateException("no csd-1")
        val index = synchronized(done) { done.size } + pendingSegments.get()
        val writer = SegmentWriter.open(width, height, rotation, sps, pps, SEGMENT_CAP)
        return Segment(index, writer, startUs)
    }

    // Finishes the segment and hands it to the encrypt thread. The writer is
    // closed (buffer wiped) after the encrypt returns, on every path.
    private fun finishSegment(vault: Vault, seg: Segment, durationUs: Long) {
        val rc = seg.writer.finish(durationUs)
        if (rc != NativeBridge.OK) {
            say("record: segment ${seg.index} finish ${NativeBridge.errorName(rc)}")
            seg.writer.close()
            if (stopReason == null) stopReason = NativeBridge.nativeErrorName(rc)
            return
        }
        val stats = seg.writer.stats()
        maxSegmentBytes = maxOf(maxSegmentBytes, stats.bytes)
        val keys = IntArray(seg.keyPtsUs.size) { (seg.keyPtsUs[it] - seg.startUs).toInt() }
        pendingSegments.incrementAndGet()
        val latch = CountDownLatch(1)
        synchronized(encryptJobs) { encryptJobs.add(latch) }
        captures.executor.execute {
            try {
                if (abandoned) {
                    say("record: segment ${seg.index} not encrypted (abandoned)")
                } else {
                    val t0 = System.nanoTime()
                    val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_SEGMENT, rotation, width, height, System.currentTimeMillis())
                    val stored = seg.writer.encrypt(vault, meta)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    synchronized(done) { done.add(Done(seg.index, stored.id, seg.startUs, durationUs, stats.bytes, keys)) }
                    say("record: segment ${seg.index} ${NativeBridge.hex(stored.id)} start_ms=${seg.startUs / 1000} duration_ms=${durationUs / 1000} bytes=${stats.bytes} samples=${stats.samples} fragments=${stats.fragments} keys=${keys.size} encrypt_ms=$ms")
                }
            } catch (e: Exception) {
                say("record: segment ${seg.index} encrypt failed: ${e.message}")
                if (stopReason == null) stopReason = "ENCRYPT_FAILED"
                stop("ENCRYPT_FAILED")
            } finally {
                seg.writer.close()
                pendingSegments.decrementAndGet()
                latch.countDown()
            }
        }
    }

    private fun waitEncrypts() {
        val jobs = synchronized(encryptJobs) { ArrayList(encryptJobs) }
        for (l in jobs) l.await(if (abandoned) 2 else 60, TimeUnit.SECONDS)
    }

    private fun deliveredRate(): Int {
        val span = lastPtsUs - firstPtsUs
        if (frames < 5 || span <= 0) return 0
        return Math.round((frames - 1) * 1_000_000.0 / span).toInt()
    }

    // Index v1 over the segments encrypted so far, once per recording.
    private fun writeIndex() {
        synchronized(indexLock) {
            if (indexWritten) return
            indexWritten = true
            if (testHooks.skipIndex) {
                testHooks.skipIndex = false
                say("record: index skipped (test hook)")
                return
            }
            val segments = synchronized(done) { done.sortedBy { it.index } }
            if (segments.isEmpty()) {
                say("record: nothing recorded")
                return
            }
            val vault = vaults.vault
            if (vault == null) {
                say("record: index not written, vault closed")
                return
            }
            // Segment numbers must be a run from 0: a failed encrypt in the
            // middle ends the clip at the segment before it.
            val run = ArrayList<Done>()
            for (d in segments) {
                if (d.index != run.size) break
                run.add(d)
            }
            val rate = deliveredRate()
            val index = VideoIndex(
                VideoIndex.Header(NativeBridge.VIDEO_CODEC_H264, rotation, width, height, (if (rate > 0) rate else TARGET_FPS) * 1000,
                    run.last().startUs + run.last().durationUs, clipStartMs),
                run.map { VideoIndex.Segment(it.id, it.startUs, it.durationUs, it.bytes, it.keyOffsetsUs) }
            )
            val bytes = try {
                index.encode()
            } catch (e: Vault.CoreException) {
                say("record: index encode failed: ${e.message}")
                return
            }
            try {
                val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_INDEX, rotation, width, height, clipStartMs)
                val id = vault.encryptItemArray(bytes, 0, bytes.size, meta).id
                lastIndexHex = NativeBridge.hex(id)
                say("record: index ${lastIndexHex} segments=${run.size} keys=${index.keysTotal} duration_ms=${index.header.totalDurationUs / 1000} fps=${index.header.frameRateMillihz / 1000}")
            } catch (e: Exception) {
                say("record: index encrypt failed: ${e.message}")
            } finally {
                bytes.fill(0)
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
}
