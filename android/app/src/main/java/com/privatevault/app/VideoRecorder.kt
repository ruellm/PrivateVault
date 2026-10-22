package com.privatevault.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Records a clip into segment objects and an index (docs/PHASE3E_DESIGN.md
// Sections 4.2 to 4.7, docs/PHASE3F_DESIGN.md Sections 4.2 and 4.5). One
// camera stream goes through a CameraEffect whose processor draws every
// frame to the viewfinder surface and to the encoder input surface. Encoder
// output buffers go straight into the core segment writer (guarded memory);
// a segment closes on the third key frame and is encrypted on the
// process-scoped encrypt thread from that buffer; the index is written after
// the last segment. With audio on, an AudioRecord thread feeds the AAC
// encoder; encoded frames go into a guarded queue in the core and move into
// the writer once the video has reached their time, timed on the clock the
// camera stamps its frames with. Every way a recording ends goes through
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
        // Audio (D-044): AAC-LC, 48 kHz, stereo, 128 kbit/s, the camcorder source.
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val AUDIO_RATE = 48000
        const val AUDIO_CHANNELS = 2
        const val AUDIO_BITRATE = 128_000
        const val AUDIO_SOURCE = MediaRecorder.AudioSource.CAMCORDER
        const val AAC_FRAMES = 1024
        // A segment whose video ended waits this long for its last audio.
        const val CLOSING_WAIT_MS = 500L
        // The audio encoder drain after the end of stream is bounded by this.
        const val AUDIO_EOS_WAIT_MS = 1000L
    }

    enum class State { IDLE, RECORDING, STOPPING }

    class Result(
        val reason: String, val durationUs: Long, val segments: Int, val indexHex: String?,
        val width: Int, val height: Int, val rotation: Int, val frames: Int, val bytes: Long,
        val deliveredFps: Int, val abandoned: Boolean, val error: String?,
        // on, muted, or denied; the frames pushed, moved into segments, clamped as
        // late, dropped before the first video frame; the camera clock.
        val audio: String, val audioFrames: Int, val audioMoved: Int, val audioClamped: Int,
        val audioBeforeZero: Int, val audioQueuePeak: Int, val closingTimeouts: Int, val clock: String
    ) {
        val ok: Boolean get() = indexHex != null
        fun line(): String =
            "record: done reason=$reason duration_ms=${durationUs / 1000} segments=$segments index=${indexHex ?: "none"} " +
                "${width}x$height rotation=$rotation frames=$frames bytes=$bytes fps=$deliveredFps abandoned=$abandoned " +
                "audio=$audio audio_frames=$audioFrames audio_moved=$audioMoved audio_clamped=$audioClamped " +
                "audio_before_zero=$audioBeforeZero audio_queue_peak=$audioQueuePeak closing_timeouts=$closingTimeouts clock=$clock" +
                (if (error != null) " error=$error" else "")
    }

    // Test hooks: a stop forced at the next segment start or on the audio
    // thread by its reason, an index left unwritten (a process kill
    // stand-in), small audio caps, and a pause of the audio thread after
    // the first segment (the late-audio case).
    class TestHooks {
        @Volatile var stopAt: String? = null
        @Volatile var skipIndex = false
        @Volatile var audioQueueCap = 0L
        @Volatile var audioStageCap = 0L
        @Volatile var audioDelayMs = 0L
        // A pause of the video drain after the first segment (the queue fills).
        @Volatile var videoDelayMs = 0L
        // The microphone permission reported missing (a revocation kills the
        // process, so a test cannot revoke its own).
        @Volatile var denyAudio = false
    }

    val testHooks = TestHooks()

    private class Segment(val index: Int, val writer: SegmentWriter, val startUs: Long) {
        var endUs = startUs
        var samples = 0
        val keyPtsUs = ArrayList<Long>()
        // Set when the video of the segment ended at a cut; the segment then
        // waits for its audio up to that time.
        var finalEndUs = -1L
        var closingSinceNs = 0L
    }

    class Done(val index: Int, val id: ByteArray, val startUs: Long, val durationUs: Long, val bytes: Long, val keyOffsetsUs: IntArray, val audioSamples: Int) {
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
    // Audio.
    private var audioMode = "muted"
    private var audio: AudioThread? = null
    private val audioLock = Any()
    private var queue: AudioQueue? = null
    @Volatile private var audioFormat: SegmentWriter.AudioFormat? = null
    private var audioFormatLatch = CountDownLatch(1)
    // Added to monotonic audio times to reach the camera clock (D-046).
    @Volatile private var audioOffsetUs = 0L
    @Volatile private var cameraClock = "none"
    private var closing: Segment? = null
    private var nextSegmentIndex = 0
    private var audioMoved = 0
    private var audioClamped = 0
    private var audioBeforeZero = 0
    private var audioQueuePeak = 0
    private var closingTimeouts = 0

    val isRecording: Boolean get() = state != State.IDLE

    // Wall time since the first encoded frame, for the elapsed label.
    val elapsedMs: Long get() = if (firstFrameNs == 0L || state == State.IDLE) 0 else (System.nanoTime() - firstFrameNs) / 1_000_000

    // The audio mode of the running or last recording: on, muted, or denied.
    val currentAudio: String get() = audioMode

    fun audioPermitted(): Boolean =
        !testHooks.denyAudio && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun say(line: String) {
        Log.i(TAG, line)
        captures.say(line)
    }

    // Starts a recording. The camera is bound on the main thread with the
    // viewfinder provider as the Preview output; the work runs on an own
    // thread until onDone. audio false records a muted clip; a missing
    // microphone permission records without audio too. Returns a reason
    // when nothing was started.
    @Synchronized
    fun start(p: ProcessCameraProvider, owner: LifecycleOwner, lensFacing: Int, viewfinder: Preview.SurfaceProvider, audio: Boolean = true, onDone: (Result) -> Unit): String? {
        if (state != State.IDLE) return "BUSY"
        if (vaults.vault == null) return "VAULT_CLOSED"
        state = State.RECORDING
        provider = p
        resetRun()
        audioMode = if (!audio) "muted" else if (!audioPermitted()) "denied" else "on"
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
        audio = null
        queue = null
        audioFormat = null
        audioFormatLatch = CountDownLatch(1)
        audioOffsetUs = 0
        cameraClock = "none"
        closing = null
        nextSegmentIndex = 0
        audioMoved = 0
        audioClamped = 0
        audioBeforeZero = 0
        audioQueuePeak = 0
        closingTimeouts = 0
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
            if (audioMode == "on") {
                try {
                    val cap = if (testHooks.audioQueueCap > 0) testHooks.audioQueueCap else NativeBridge.AUDIO_QUEUE_CAP
                    queue = AudioQueue.open(cap)
                    val a = AudioThread()
                    audio = a
                    a.start()
                } catch (e: Vault.CoreException) {
                    say("record: audio queue open failed: ${e.message}")
                    audioMode = "error"
                }
            }
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
            audio?.stopAndJoin()
            waitEncrypts()
            writeIndex()
            synchronized(audioLock) {
                queue?.close()
                queue = null
            }
            VideoItems.recordingGate.unlock()
        }
        val recordedUs = if (firstPtsUs >= 0) lastPtsUs - firstPtsUs else 0
        val segments = synchronized(done) { ArrayList(done) }
        val a = audio
        if (a != null) say("record: audio frames_read=${a.framesRead} reads=${a.reads} short_reads=${a.shortReads} aac_frames=${a.aacFrames} pushed=${a.pushed} anchor=${a.anchorLine}")
        val result = Result(
            stopReason ?: "unknown", segments.lastOrNull()?.let { it.startUs + it.durationUs } ?: 0L, segments.size, lastIndexHex,
            width, height, rotation, frames, bytesEncoded, deliveredRate(), abandoned, error,
            audioMode, a?.pushed ?: 0, audioMoved, audioClamped, audioBeforeZero, audioQueuePeak, closingTimeouts, cameraClock
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
        say("record: encoder ${codec.name} ${w}x$h bitrate=$bitrate fps=$TARGET_FPS gop_us=$GOP_US keys_per_segment=$KEYS_PER_SEGMENT backstop_s=$IFRAME_BACKSTOP_S audio=$audioMode")
        return surface
    }

    // Which clock the camera stamps its frames with (D-046): the clock whose
    // reading at the first frame callback is nearest the frame timestamp.
    // Audio times are monotonic; the offset moves them onto that clock.
    private fun resolveClock(s: GlSplitter) {
        val ts = s.firstTimestampNs
        val dMono = (ts - s.firstFrameNs) / 1_000_000
        val dBoot = (ts - s.firstFrameBootNs) / 1_000_000
        val useBoot = Math.abs(dBoot) < Math.abs(dMono)
        cameraClock = if (useBoot) "BOOTTIME" else "MONOTONIC"
        val nowMono = System.nanoTime()
        val nowBoot = SystemClock.elapsedRealtimeNanos()
        audioOffsetUs = if (useBoot) (nowBoot - nowMono) / 1000 else 0L
        say("record: clock camera=$cameraClock first_ts_minus_monotonic_ms=$dMono first_ts_minus_boottime_ms=$dBoot audio_offset_us=$audioOffsetUs")
    }

    // Camera-clock time to the monotonic time the queue holds.
    private fun toMonotonic(cameraUs: Long): Long = (cameraUs - audioOffsetUs).coerceAtLeast(0)

    // Moves the queued audio below untilUs (camera clock) into the segment.
    // A core refusal stops the recording with AUDIO_FULL.
    private fun moveAudio(seg: Segment, untilUs: Long) {
        val q = queue ?: return
        if (audioMode != "on") return
        val r = synchronized(audioLock) { q.move(seg.writer, toMonotonic(seg.startUs), toMonotonic(untilUs)) }
        audioMoved += r.moved
        audioClamped += r.clamped
        if (r.status != NativeBridge.OK) {
            say("record: segment ${seg.index} audio ${NativeBridge.errorName(r.status)}")
            if (stopReason == null) stopReason = "AUDIO_FULL"
            stop("AUDIO_FULL")
        }
    }

    private fun queueStats(): AudioQueue.Stats? {
        val q = queue ?: return null
        return synchronized(audioLock) { q.stats() }
    }

    // Pulls encoded frames until the stream ends. A sync frame is asked for
    // one GOP after the last key frame; a segment closes when a key frame
    // arrives and the segment already holds KEYS_PER_SEGMENT. Audio moves
    // behind the video (D-047); a cut segment waits for its last audio.
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
        var clockResolved = false
        var videoDelayed = false
        try {
            while (true) {
                if (abandoned) break
                if (!videoDelayed && testHooks.videoDelayMs > 0 && synchronized(done) { done.size } >= 1) {
                    videoDelayed = true
                    say("record: video drain held ${testHooks.videoDelayMs} ms (test hook)")
                    Thread.sleep(testHooks.videoDelayMs)
                }
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
                    checkClosing(vault, false)
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
                if (!clockResolved) {
                    clockResolved = true
                    splitter?.let { resolveClock(it) }
                    if (audioMode == "on") {
                        audioFormatLatch.await(2, TimeUnit.SECONDS)
                        if (audioFormat == null) {
                            // No encoder format in time: the clip records without audio.
                            say("record: audio format not ready, recording without audio")
                            audioMode = "error"
                            audio?.stopAndJoin()
                            synchronized(audioLock) {
                                queue?.close()
                                queue = null
                            }
                        }
                    }
                }
                if (firstPtsUs < 0) {
                    firstPtsUs = info.presentationTimeUs
                    firstFrameNs = System.nanoTime()
                    // The clip begins with this frame; earlier audio goes.
                    queue?.let { q -> audioBeforeZero = synchronized(audioLock) { q.discardBefore(toMonotonic(firstPtsUs)) } }
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
                        beginClosing(vault, seg, pts)
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
                // Segment times are on the camera clock; sample times run from the segment start.
                val rc = s.writer.add(buf, info.presentationTimeUs - s.startUs, sync)
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
                s.endUs = info.presentationTimeUs
                codec.releaseOutputBuffer(idx, false)
                // The audio the video has reached now goes behind it.
                moveAudio(s, firstPtsUs + pts + 1)
                queueStats()?.let { if (it.frames > audioQueuePeak) audioQueuePeak = it.frames }
                checkClosing(vault, false)
                if (eos) break
            }
        } finally {
            // The video has ended: the audio thread drains its encoder, then
            // every queued frame goes into the last segment (D-047).
            audio?.stopAndJoin()
            val seg = current
            if (abandoned) {
                closing?.let { it.writer.close(); say("record: segment ${it.index} dropped") }
                closing = null
                if (seg != null) {
                    seg.writer.close()
                    say("record: segment ${seg.index} dropped")
                }
            } else {
                val c = closing
                if (seg == null && c != null) {
                    // The cut segment is the last one: it takes every queued frame.
                    closing = null
                    moveAudio(c, Long.MAX_VALUE / 2)
                    finishSegment(vault, c, c.finalEndUs - c.startUs)
                } else {
                    checkClosing(vault, true)
                    if (seg != null) {
                        moveAudio(seg, Long.MAX_VALUE / 2)
                        finishSegment(vault, seg, seg.endUs - seg.startUs + framePeriodUs())
                    }
                }
            }
            if (stopReason == null) stopReason = "clip end"
        }
    }

    private fun framePeriodUs(): Long = 1_000_000L / TARGET_FPS

    // The segment whose video ended at the cut: it takes the audio below the
    // cut and waits for audio past it. An older closing segment is finished first.
    private fun beginClosing(vault: Vault, seg: Segment, cutRelUs: Long) {
        checkClosing(vault, true)
        seg.finalEndUs = firstPtsUs + cutRelUs
        seg.closingSinceNs = System.nanoTime()
        moveAudio(seg, seg.finalEndUs)
        closing = seg
    }

    // Finishes the closing segment once the queued audio has passed its end,
    // or after the wait, or when forced.
    private fun checkClosing(vault: Vault, force: Boolean) {
        val c = closing ?: return
        val st = queueStats()
        val passed = queue == null || audioMode != "on" || (st != null && st.frames > 0 && st.headPtsUs + audioOffsetUs >= c.finalEndUs)
        val waited = System.nanoTime() - c.closingSinceNs > CLOSING_WAIT_MS * 1_000_000
        if (!passed && !waited && !force) return
        if (!passed) {
            closingTimeouts++
            say("record: segment ${c.index} closed after the ${CLOSING_WAIT_MS} ms audio wait")
        }
        closing = null
        finishSegment(vault, c, c.finalEndUs - c.startUs)
    }

    // Why the next segment cannot start, or null.
    private fun segmentStartRefusal(): String? {
        val hook = testHooks.stopAt
        if (hook != null && hook != "AUDIO_ERROR" && hook != "AUDIO_TAKEN") {
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
        val writer = SegmentWriter.openAv(width, height, rotation, sps, pps, if (audioMode == "on") audioFormat else null, SEGMENT_CAP)
        return Segment(nextSegmentIndex++, writer, firstPtsUs + startUs)
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
        val audioStats = seg.writer.audioStats()
        maxSegmentBytes = maxOf(maxSegmentBytes, stats.bytes)
        val keys = IntArray(seg.keyPtsUs.size) { (seg.keyPtsUs[it] - (seg.startUs - firstPtsUs)).toInt() }
        pendingSegments.incrementAndGet()
        val latch = CountDownLatch(1)
        synchronized(encryptJobs) { encryptJobs.add(latch) }
        val startRel = seg.startUs - firstPtsUs
        captures.executor.execute {
            try {
                if (abandoned) {
                    say("record: segment ${seg.index} not encrypted (abandoned)")
                } else {
                    val t0 = System.nanoTime()
                    val meta = Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_SEGMENT, rotation, width, height, System.currentTimeMillis())
                    val stored = seg.writer.encrypt(vault, meta)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    synchronized(done) { done.add(Done(seg.index, stored.id, startRel, durationUs, stats.bytes, keys, audioStats.samples)) }
                    say("record: segment ${seg.index} ${NativeBridge.hex(stored.id)} start_ms=${startRel / 1000} duration_ms=${durationUs / 1000} bytes=${stats.bytes} samples=${stats.samples} fragments=${stats.fragments} keys=${keys.size} audio_samples=${audioStats.samples} audio_first_tick=${audioStats.firstTick} encrypt_ms=$ms")
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
            val a = audioFormat
            val withAudio = a != null && audioMode == "on"
            val index = VideoIndex(
                VideoIndex.Header(NativeBridge.VIDEO_CODEC_H264, rotation, width, height, (if (rate > 0) rate else TARGET_FPS) * 1000,
                    run.last().startUs + run.last().durationUs, clipStartMs,
                    if (withAudio) NativeBridge.AUDIO_CODEC_AAC else NativeBridge.AUDIO_CODEC_NONE,
                    if (withAudio) a!!.channels else 0, if (withAudio) a!!.sampleRate else 0),
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
                say("record: index ${lastIndexHex} segments=${run.size} keys=${index.keysTotal} duration_ms=${index.header.totalDurationUs / 1000} fps=${index.header.frameRateMillihz / 1000} audio_codec=${index.header.audioCodec} channels=${index.header.audioChannels} rate=${index.header.audioSampleRate}")
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

    // AudioRecord fed to the AAC encoder in 1024-frame chunks (D-044, D-046).
    // Each chunk gets the monotonic time of its first frame from the first
    // AudioRecord.getTimestamp, extended by the frame count; the encoder keeps
    // those times on its output, and every output buffer goes into the core
    // queue in place. PCM passes through one direct buffer, zeroed at the end.
    // A microphone taken by another client, a read error, or an encoder error
    // ends the recording through stop (D-049).
    private inner class AudioThread : Thread("pv-audio") {
        @Volatile private var stopping = false
        @Volatile var framesRead = 0L
        @Volatile var reads = 0
        @Volatile var shortReads = 0
        @Volatile var aacFrames = 0
        @Volatile var pushed = 0
        @Volatile var anchorLine = "none"
        @Volatile private var silenced = false
        private var anchorNs = -1L
        private var anchorFrame = 0L
        private var delayed = false
        private val stopped = CountDownLatch(1)

        fun stopAndJoin() {
            stopping = true
            stopped.await(AUDIO_EOS_WAIT_MS + 3000, TimeUnit.MILLISECONDS)
            join(2000)
        }

        private fun frameTimeUs(frame: Long): Long = (anchorNs + (frame - anchorFrame) * 1_000_000_000L / AUDIO_RATE) / 1000

        override fun run() {
            val frameBytes = 2 * AUDIO_CHANNELS
            val chunk = AAC_FRAMES * frameBytes
            val mask = if (AUDIO_CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            var codec: MediaCodec? = null
            val scratch = ByteBuffer.allocateDirect(chunk).order(ByteOrder.nativeOrder())
            val pending = ArrayList<Pair<ByteBuffer, Long>>()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var callback: AudioManager.AudioRecordingCallback? = null
            if (!audioPermitted()) {
                say("record: audio thread without the microphone permission")
                audioFormatLatch.countDown()
                stopped.countDown()
                return
            }
            try {
                // One second of buffer: a stall of the thread drops no PCM.
                rec = AudioRecord(AUDIO_SOURCE, AUDIO_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, chunk * 48))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    say("record: audio record not initialized (min_buffer=$minBuffer)")
                    stop("AUDIO_ERROR")
                    return
                }
                val stageCap = testHooks.audioStageCap
                codec = MediaCodec.createEncoderByType(AUDIO_MIME)
                val format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, AUDIO_CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunk * 2)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val session = rec.audioSessionId
                    val cb = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            for (c in configs) {
                                if (c.clientAudioSessionId == session && c.isClientSilenced) silenced = true
                            }
                        }
                    }
                    audioManager.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
                    callback = cb
                }
                rec.startRecording()
                say("record: audio started source=CAMCORDER rate=${rec.sampleRate} channels=${rec.channelCount} min_buffer=$minBuffer encoder=${codec.name}")
                val info = MediaCodec.BufferInfo()
                var eosQueued = false
                var eosNs = 0L
                while (true) {
                    if (!stopping) {
                        val hook = testHooks.stopAt
                        if (hook == "AUDIO_ERROR") {
                            testHooks.stopAt = null
                            throw IllegalStateException("audio error hook")
                        }
                        if (hook == "AUDIO_TAKEN") {
                            testHooks.stopAt = null
                            silenced = true
                        }
                        if (silenced) {
                            say("record: microphone taken")
                            stop("AUDIO_TAKEN")
                            stopping = true
                            continue
                        }
                        if (!delayed && testHooks.audioDelayMs > 0 && synchronized(done) { done.size } >= 1) {
                            delayed = true
                            say("record: audio thread held ${testHooks.audioDelayMs} ms (test hook)")
                            sleep(testHooks.audioDelayMs)
                        }
                        scratch.clear()
                        val n = rec.read(scratch, chunk, AudioRecord.READ_BLOCKING)
                        if (n < 0 || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            say("record: audio read error $n state=${rec.recordingState}")
                            stop("AUDIO_ERROR")
                            stopping = true
                            continue
                        }
                        reads++
                        if (n < chunk) shortReads++
                        val frameIndex = framesRead
                        framesRead += n / frameBytes
                        scratch.position(0)
                        scratch.limit(n)
                        if (anchorNs < 0) {
                            val ts = AudioTimestamp()
                            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                anchorNs = ts.nanoTime
                                anchorFrame = ts.framePosition
                                anchorLine = "getTimestamp frame_position=${ts.framePosition} frames_read=$framesRead age_ms=${(System.nanoTime() - ts.nanoTime) / 1_000_000} after_reads=$reads"
                            } else if (reads >= 8) {
                                anchorNs = System.nanoTime()
                                anchorFrame = framesRead
                                anchorLine = "fallback (no timestamp after $reads reads): read return time"
                            }
                            // Held in direct buffers until the anchor exists.
                            val copy = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
                            copy.put(scratch)
                            copy.flip()
                            pending.add(copy to frameIndex)
                            if (anchorNs < 0) continue
                            say("record: audio anchor $anchorLine")
                            for ((pcm, fi) in pending) {
                                feed(codec, pcm, frameTimeUs(fi), info)
                                pcm.clear()
                                while (pcm.hasRemaining()) pcm.put(0)
                            }
                            pending.clear()
                            continue
                        }
                        feed(codec, scratch, frameTimeUs(frameIndex), info)
                    } else {
                        if (!eosQueued) {
                            try {
                                rec.stop()
                            } catch (e: Exception) {
                            }
                            val idx = codec.dequeueInputBuffer(100_000)
                            if (idx >= 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                                eosNs = System.nanoTime()
                            }
                        }
                        if (drainOut(codec, info, 100_000)) break
                        if (eosQueued && System.nanoTime() - eosNs > AUDIO_EOS_WAIT_MS * 1_000_000) {
                            say("record: audio encoder drain timed out")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                say("record: audio failed: ${e.javaClass.simpleName}: ${e.message}")
                stop("AUDIO_ERROR")
            } finally {
                audioFormatLatch.countDown()
                callback?.let { audioManager.unregisterAudioRecordingCallback(it) }
                try {
                    rec?.stop()
                } catch (e: Exception) {
                }
                rec?.release()
                try {
                    codec?.stop()
                } catch (e: Exception) {
                }
                codec?.release()
                scratch.clear()
                while (scratch.hasRemaining()) scratch.put(0)
                for ((pcm, _) in pending) {
                    pcm.clear()
                    while (pcm.hasRemaining()) pcm.put(0)
                }
                stopped.countDown()
            }
        }

        private fun feed(codec: MediaCodec, pcm: ByteBuffer, ptsUs: Long, info: MediaCodec.BufferInfo) {
            var idx = -1
            while (idx < 0) {
                idx = codec.dequeueInputBuffer(50_000)
                if (idx < 0) drainOut(codec, info, 0)
            }
            val ib = codec.getInputBuffer(idx)!!
            ib.clear()
            val n = pcm.remaining()
            ib.put(pcm)
            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
            drainOut(codec, info, 0)
        }

        // Pulls encoder output into the queue; returns true at the end of the stream.
        private fun drainOut(codec: MediaCodec, info: MediaCodec.BufferInfo, waitUs: Long): Boolean {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, waitUs)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val csd = f.getByteBuffer("csd-0")
                    if (csd != null && audioFormat == null) {
                        val d = csd.duplicate()
                        val config = ByteArray(d.remaining())
                        d.get(config)
                        audioFormat = SegmentWriter.AudioFormat(AUDIO_CHANNELS, AUDIO_RATE, AUDIO_BITRATE, config, testHooks.audioStageCap)
                        audioFormatLatch.countDown()
                        say("record: audio format ${f.getString(MediaFormat.KEY_MIME)} rate=${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} channels=${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} csd0_bytes=${config.size}")
                    }
                    continue
                }
                if (idx < 0) return false
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val ob = codec.getOutputBuffer(idx)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    aacFrames++
                    val q = queue
                    if (q != null) {
                        val rc = synchronized(audioLock) { q.push(ob, info.presentationTimeUs) }
                        if (rc == NativeBridge.OK) {
                            pushed++
                        } else {
                            say("record: audio queue ${NativeBridge.errorName(rc)} at frame $aacFrames")
                            stop(if (rc == NativeBridge.ERR_AUDIO_QUEUE_FULL) "AUDIO_QUEUE_FULL" else "AUDIO_ERROR")
                            stopping = true
                        }
                    }
                }
                codec.releaseOutputBuffer(idx, false)
                if (eos) return true
            }
        }
    }
}
