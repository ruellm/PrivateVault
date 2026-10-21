package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaFormat
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.ChunkIndex
import java.util.concurrent.ConcurrentHashMap

// One clip over ExoPlayer (docs/PHASE3D_DESIGN.md Section 4.2). The index is
// read through the viewer pipeline; each segment is decrypted into guarded
// memory when the player opens it and wiped when it closes it; the picture
// goes to a secure SurfaceView and the index rotation is applied by the
// codec. Tap to play or pause, a seek bar through the index seek rule,
// elapsed and total time, Delete behind an in-activity confirmation. A lock
// or a stop releases the player, which closes every open segment. The
// surface keeps the screen on while playing; no wake lock. Not exported;
// the extras are the index object ID and an optional script for the T3 runs.
@androidx.annotation.OptIn(UnstableApi::class)
class VideoActivity : Activity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        const val EXTRA_ID = "id"
        // seeks=<n>: after the clip ended, seek to n planned positions and log each landing.
        const val EXTRA_SCRIPT = "script"
        const val TICK_MS = 200L
        const val SEEK_TIMEOUT_MS = 8000L
        const val CLOSE_WAIT_NS = 2_000_000_000L
        const val READ_AHEAD_MIN_BYTES = 4L shl 20
    }

    class SeekResult(
        val targetUs: Long, val segment: Int, val key: Int, val keyTimeUs: Long, val latencyMs: Long,
        val positionUs: Long, val frameUs: Long
    ) {
        fun line(): String = "target_ms=${targetUs / 1000} segment=$segment key=$key key_ms=${keyTimeUs / 1000} latency_ms=$latencyMs position_ms=${positionUs / 1000} frame_ms=${if (frameUs >= 0) frameUs / 1000 else -1}"
    }

    // What the run measured; tests and the scripted runs read it.
    class Report {
        var firstFrameMs = -1L
        var transitions = 0
        val boundaryGapsMs = ArrayList<Long>()
        val errors = ArrayList<String>()
        val seeks = ArrayList<SeekResult>()
        var seekTimeouts = 0
        var framesRendered = 0
        var droppedFrames = 0
        @Volatile var lastFrameSegment = -1
        var ended = false
        var scriptDone = false
        var pssPeakKiB = 0
        var nativePeakKiB = 0L
        var javaPeakKiB = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock: LockController get() = PvApp.of(this).lock
    private val vaults: VaultSession get() = PvApp.of(this).vaultSession
    private lateinit var frame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var bar: SeekBar
    private lateinit var status: TextView
    private lateinit var confirm: LinearLayout
    private lateinit var deleteButton: Button
    private var id: ByteArray = ByteArray(0)
    private var hex = ""
    private var scriptSeeks = 0
    private var started = false
    private var player: ExoPlayer? = null
    private var prepareNs = 0L
    private var videoW = 0
    private var videoH = 0
    private var dragging = false
    @Volatile private var currentItem = 0
    @Volatile private var lastFrameUs = -1L
    @Volatile private var lastReleaseNs = 0L
    @Volatile private var seekPending = false
    @Volatile private var seekFrameUs = -1L
    @Volatile private var seekSeenNs = 0L
    private var seekNs = 0L
    private var seekPlan: List<Long> = emptyList()
    private var nextSeek = 0
    private var sampler: Sampler? = null
    private val seekMaps = ConcurrentHashMap<Int, ChunkIndex>()
    // Per opened segment: true when it took the standard path (tests read it).
    val segmentTfdt = ConcurrentHashMap<Int, Boolean>()
    private val ticker = Runnable { tick() }

    val report = Report()
    val live = LiveBuffers { Log.i(TAG, it) }
    // The index, or null until loaded or when the load failed (loadStatus says why).
    var index: VideoIndex? = null
        private set
    @Volatile var loadStatus = -1
        private set
    @Volatile var playerReleased = false
        private set
    @Volatile var firstFrameRendered = false
        private set
    var surfaceSecure = false
        private set
    @Volatile var deleteResult: VideoItems.DeleteResult? = null
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true

    private fun say(line: String) {
        Log.i(TAG, "video: $line")
        if (this::status.isInitialized) status.text = line
    }

    private fun routeIfLocked(): Boolean {
        if (lock.state == LockState.UNLOCKED) return false
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return true
    }

    override fun onLockStateChanged(state: LockState) {
        if (state != LockState.UNLOCKED && !isFinishing) {
            releasePlayer("lock")
            routeIfLocked()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (routeIfLocked()) return
        hex = intent?.getStringExtra(EXTRA_ID) ?: ""
        if (hex.length != 32 || hex.any { it !in "0123456789abcdef" }) {
            finish()
            return
        }
        id = NativeBridge.fromHex(hex)
        for (p in (intent?.getStringExtra(EXTRA_SCRIPT) ?: "").split(':')) {
            if (p.startsWith("seeks=")) scriptSeeks = p.substringAfter('=').toIntOrNull() ?: 0
        }
        lock.addListener(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        frame = FrameLayout(this)
        surface = SurfaceView(this)
        surface.setSecure(true)
        surfaceSecure = true
        frame.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.setOnClickListener { togglePlay() }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "0:00"
            setPadding(16, 0, 8, 0)
        }
        bar = SeekBar(this).apply {
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = VideoItems.durationLabel(progress * 1000L)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    dragging = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    seekToMs(bar.progress.toLong())
                }
            })
        }
        total = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "0:00"
            setPadding(8, 0, 16, 0)
        }
        row.addView(elapsed)
        row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(total)
        root.addView(row)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })
        deleteButton = Button(this).apply {
            text = "Delete"
            isEnabled = false
            setOnClickListener { confirm.visibility = View.VISIBLE }
        }
        buttons.addView(deleteButton)
        root.addView(buttons)
        confirm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(this@VideoActivity).apply {
                text = "Delete this video? The index and every segment are removed from the vault."
                setTextColor(Color.WHITE)
            })
            val r = LinearLayout(this@VideoActivity).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(Button(this@VideoActivity).apply {
                text = "Delete"
                setOnClickListener { runDelete() }
            })
            r.addView(Button(this@VideoActivity).apply {
                text = "Cancel"
                setOnClickListener { confirm.visibility = View.GONE }
            })
            addView(r)
        }
        root.addView(confirm)
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            text = "loading"
        }
        root.addView(status)
        setContentView(root)
        PhotoLoader.executor.execute { loadIndex() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (routeIfLocked()) return
        val idx = index
        if (idx != null && player == null && !isFinishing) buildPlayer(idx)
    }

    override fun onStop() {
        started = false
        releasePlayer("stop")
        if (!isFinishing) finish()
        super.onStop()
    }

    override fun onDestroy() {
        lock.removeListener(this)
        releasePlayer("destroy")
        super.onDestroy()
    }

    private fun loadIndex() {
        val v = vaults.vault
        val d = if (v == null) VideoIndex.Decoded(NativeBridge.ERR_CANCELLED, null) else VideoIndex.read(v, id)
        main.post {
            if (isFinishing) return@post
            loadStatus = d.status
            val idx = d.index
            deleteButton.isEnabled = true
            if (d.status != NativeBridge.OK || idx == null) {
                say("index $hex ${NativeBridge.errorName(d.status)}")
                return@post
            }
            index = idx
            total.text = VideoItems.durationLabel(idx.header.totalDurationUs)
            bar.max = (idx.header.totalDurationUs / 1000).toInt()
            Log.i(TAG, "video: index $hex segments=${idx.segments.size} keys=${idx.keysTotal} duration_ms=${idx.header.totalDurationUs / 1000} ${idx.header.width}x${idx.header.height} rotation=${idx.header.rotation}")
            if (started && lock.state == LockState.UNLOCKED) buildPlayer(idx)
        }
    }

    private fun buildPlayer(idx: VideoIndex) {
        val v = vaults.vault ?: return
        val sources = GuardedSegmentDataSource.Factory(v, idx, live, seekMaps, segmentTfdt)
        val extractors = SeekableSegmentExtractor.Factory(seekMaps, idx.header.rotation, segmentTfdt)
        // The read-ahead stops at about two segments of samples, whichever
        // of the byte and time thresholds comes first (docs/PHASE3E_DESIGN.md
        // Section 4.8).
        val largest = idx.segments.maxOf { it.byteLen }
        val targetBytes = maxOf(READ_AHEAD_MIN_BYTES, 2 * largest).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 4000, 500, 1000)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        Log.i(TAG, "video: read-ahead target_bytes=$targetBytes largest_segment=$largest")
        // The data is local and a failed segment stays failed: no retries,
        // the first failure is the error.
        val noRetry = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = androidx.media3.common.C.TIME_UNSET
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(sources, extractors).setLoadErrorHandlingPolicy(noRetry))
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        bar.isEnabled = true
                        if (!report.ended) say("playing")
                    }
                    Player.STATE_ENDED -> {
                        surface.keepScreenOn = false
                        say("ended")
                        if (!report.ended) {
                            report.ended = true
                            logSummary()
                            if (scriptSeeks > 0) main.post { startSeeks() } else report.scriptDone = true
                        }
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                surface.keepScreenOn = playing
                if (playing) say("playing") else if (p.playbackState == Player.STATE_READY) say("paused")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentItem = p.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) report.transitions++
                Log.i(TAG, "video: item $currentItem reason=$reason")
            }

            override fun onRenderedFirstFrame() {
                if (!firstFrameRendered) {
                    firstFrameRendered = true
                    report.firstFrameMs = (System.nanoTime() - prepareNs) / 1_000_000
                    Log.i(TAG, "video: first frame after ${report.firstFrameMs}ms")
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val swap = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                videoW = if (swap) videoSize.height else videoSize.width
                videoH = if (swap) videoSize.width else videoSize.height
                Log.i(TAG, "video: size ${videoSize.width}x${videoSize.height} unapplied_rotation=${videoSize.unappliedRotationDegrees} shown=${videoW}x$videoH")
                fitSurface()
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val text = if (cause is SegmentOpenException) "segment ${cause.segment}: ${NativeBridge.errorName(cause.status)}"
                else "${error.errorCodeName}: ${cause?.javaClass?.simpleName}: ${cause?.message}"
                report.errors.add(text)
                say("error: $text")
                releasePlayer("error")
                report.scriptDone = true
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                report.droppedFrames += droppedFrames
            }
        })
        p.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
                onFrame(presentationTimeUs, releaseTimeNs)
            }
        })
        p.setVideoSurfaceView(surface)
        val items = idx.segments.indices.map { n ->
            MediaItem.Builder().setUri(GuardedSegmentDataSource.uri(n)).setMimeType(MimeTypes.VIDEO_MP4).build()
        }
        p.setMediaItems(items)
        p.playWhenReady = true
        player = p
        playerReleased = false
        sampler = Sampler().also { it.start() }
        prepareNs = System.nanoTime()
        p.prepare()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
        Log.i(TAG, "video: prepared ${items.size} items")
    }

    // Sizes the surface to the picture inside the frame, letterboxed.
    private fun fitSurface() {
        if (videoW <= 0 || videoH <= 0 || frame.width <= 0 || frame.height <= 0) return
        val scale = minOf(frame.width.toDouble() / videoW, frame.height.toDouble() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    // Playback thread. A frame whose time is below the previous one is the
    // first of the next segment; the gap is measured between release times.
    private fun onFrame(presentationTimeUs: Long, releaseTimeNs: Long) {
        report.framesRendered++
        report.lastFrameSegment = currentItem
        if (seekPending) {
            seekPending = false
            seekFrameUs = presentationTimeUs
            seekSeenNs = System.nanoTime()
        } else if (lastFrameUs >= 0 && presentationTimeUs < lastFrameUs && lastReleaseNs > 0) {
            val gapMs = (releaseTimeNs - lastReleaseNs) / 1_000_000
            synchronized(report.boundaryGapsMs) { report.boundaryGapsMs.add(gapMs) }
            Log.i(TAG, "video: boundary gap_ms=$gapMs (last_frame_ms=${lastFrameUs / 1000} next_frame_ms=${presentationTimeUs / 1000})")
        }
        lastFrameUs = presentationTimeUs
        lastReleaseNs = releaseTimeNs
    }

    // Clip time of the player position.
    fun positionMs(): Long = positionUs() / 1000

    private fun positionUs(): Long {
        val p = player ?: return 0
        val idx = index ?: return 0
        val item = p.currentMediaItemIndex.coerceIn(0, idx.segments.size - 1)
        return idx.segments[item].startUs + p.currentPosition * 1000
    }

    // Every TICK_MS: the time and the bar follow the player; playing counts
    // as activity for the idle lock.
    private fun tick() {
        val p = player ?: return
        val pos = positionMs()
        if (!dragging) {
            elapsed.text = VideoItems.durationLabel(pos * 1000)
            bar.progress = pos.toInt()
        }
        if (p.isPlaying) lock.touch()
        main.postDelayed(ticker, TICK_MS)
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            seekToMs(0)
            p.playWhenReady = true
            return
        }
        p.playWhenReady = !p.playWhenReady
    }

    // The seek rule: the last key frame at or before the clip time, through
    // the index; the player decodes forward from it. The bar seeks in
    // milliseconds; the scripted seeks keep microseconds so a segment start
    // is hit exactly.
    fun seekToMs(ms: Long) = seekToUs(maxOf(0L, ms) * 1000)

    fun seekToUs(us: Long) {
        val p = player ?: return
        val idx = index ?: return
        val loc = idx.locate(maxOf(0L, us))
        val seg = idx.segments[loc.segment]
        seekFrameUs = -1L
        seekPending = true
        seekNs = System.nanoTime()
        Log.i(TAG, "video: seek target_ms=${us / 1000} segment=${loc.segment} key=${loc.key} key_ms=${loc.keyTimeUs / 1000}")
        p.seekTo(loc.segment, (loc.keyTimeUs - seg.startUs) / 1000)
        currentItem = loc.segment
        elapsed.text = VideoItems.durationLabel(loc.keyTimeUs)
        bar.progress = (loc.keyTimeUs / 1000).toInt()
        waitSeek(us, loc, seg)
    }

    private fun waitSeek(target: Long, loc: VideoIndex.Location, seg: VideoIndex.Segment) {
        main.postDelayed({
            val p = player ?: return@postDelayed
            if (!seekPending) {
                val latency = (seekSeenNs - seekNs) / 1_000_000
                val positionUs = positionUs()
                val r = SeekResult(target, loc.segment, loc.key, loc.keyTimeUs, latency, positionUs,
                    if (seekFrameUs >= 0) seg.startUs + seekFrameUs else -1L)
                synchronized(report.seeks) { report.seeks.add(r) }
                Log.i(TAG, "video: seek ${r.line()}")
                if (scriptSeeks > 0 && nextSeek > 0) runNextSeek()
            } else if ((System.nanoTime() - seekNs) / 1_000_000 > SEEK_TIMEOUT_MS) {
                report.seekTimeouts++
                Log.i(TAG, "video: seek to ${target / 1000}ms: no frame within ${SEEK_TIMEOUT_MS}ms (state ${p.playbackState})")
                if (scriptSeeks > 0 && nextSeek > 0) runNextSeek()
            } else {
                waitSeek(target, loc, seg)
            }
        }, 20)
    }

    // Scripted seeks after the clip ended: segment starts first, then times
    // inside segments, each landing on a key frame no earlier seek used.
    private fun planSeeks(idx: VideoIndex, count: Int): List<Long> {
        val n = idx.segments.size
        val plan = ArrayList<Long>()
        val landed = HashSet<Pair<Int, Int>>()
        fun add(t: Long): Boolean {
            val loc = idx.locate(t)
            if (!landed.add(loc.segment to loc.key)) return false
            plan.add(t)
            return true
        }
        val starts = minOf(count / 2, n)
        for (i in 0 until starts) {
            val s = if (starts <= 1) 0 else (i.toLong() * (n - 1) / (starts - 1)).toInt()
            add(idx.segments[s].startUs)
        }
        for (offsetUs in listOf(1_500_000L, 2_500_000L, 500_000L)) {
            for (seg in idx.segments) {
                if (plan.size >= count) break
                if (offsetUs < seg.durationUs) add(seg.startUs + offsetUs)
            }
        }
        return plan
    }

    private fun startSeeks() {
        val idx = index ?: return
        val p = player ?: return
        p.pause()
        seekPlan = planSeeks(idx, scriptSeeks)
        nextSeek = 0
        Log.i(TAG, "video: seeking to ${seekPlan.size} positions")
        runNextSeek()
    }

    private fun runNextSeek() {
        if (nextSeek >= seekPlan.size) {
            Log.i(TAG, "video: seeks done seeks=${report.seeks.size} timeouts=${report.seekTimeouts}")
            report.scriptDone = true
            return
        }
        val target = seekPlan[nextSeek++]
        seekToUs(target)
    }

    private fun logSummary() {
        val gaps = synchronized(report.boundaryGapsMs) { ArrayList(report.boundaryGapsMs) }
        Log.i(TAG, "video: summary first_frame_ms=${report.firstFrameMs} transitions=${report.transitions} boundary_gaps_ms=${gaps.joinToString(",")} frames=${report.framesRendered} dropped=${report.droppedFrames} errors=${report.errors.size}")
        Log.i(TAG, "video: memory peak_live_buffers=${live.peakCount} peak_live_bytes=${live.peakBytes} opens=${live.opens} closes=${live.closes} pss_peak=${report.pssPeakKiB}KiB native_heap_peak=${report.nativePeakKiB}KiB java_heap_peak=${report.javaPeakKiB}KiB")
    }

    // Index first, then the segments, on the viewer thread; the player is
    // released first so no segment is open.
    fun runDelete() {
        confirm.visibility = View.GONE
        deleteButton.isEnabled = false
        releasePlayer("delete")
        val idx = index
        PhotoLoader.executor.execute {
            val v = vaults.vault
            val r = if (v == null) VideoItems.DeleteResult(NativeBridge.ERR_CANCELLED, IntArray(0)) else VideoItems.delete(v, id, idx)
            main.post {
                deleteResult = r
                Log.i(TAG, "video: delete $hex: ${r.line()}")
                if (isFinishing) return@post
                if (r.ok) {
                    say("deleted")
                    finish()
                } else {
                    say("delete failed: ${r.line()}")
                    deleteButton.isEnabled = true
                }
            }
        }
    }

    private fun releasePlayer(reason: String) {
        main.removeCallbacks(ticker)
        val p = player ?: return
        player = null
        sampler?.finish()
        surface.keepScreenOn = false
        p.release()
        // release() returns before the loader thread has closed a data
        // source still open; wait for that close (and its wipe) briefly.
        val t0 = System.nanoTime()
        while (live.liveCount > 0 && System.nanoTime() - t0 < CLOSE_WAIT_NS) Thread.sleep(5)
        playerReleased = true
        Log.i(TAG, "video: player released ($reason) live_buffers=${live.liveCount} live_bytes=${live.liveBytes} opens=${live.opens} closes=${live.closes} close_wait_ms=${(System.nanoTime() - t0) / 1_000_000}")
        if (!report.ended) logSummary()
    }

    private inner class Sampler : Thread("pv-video-sample") {
        @Volatile private var running = true
        override fun run() {
            val rt = Runtime.getRuntime()
            while (running) {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                report.pssPeakKiB = maxOf(report.pssPeakKiB, mi.totalPss)
                report.nativePeakKiB = maxOf(report.nativePeakKiB, Debug.getNativeHeapAllocatedSize() shr 10)
                report.javaPeakKiB = maxOf(report.javaPeakKiB, (rt.totalMemory() - rt.freeMemory()) shr 10)
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
