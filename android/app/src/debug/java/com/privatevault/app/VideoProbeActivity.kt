package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaFormat
import android.graphics.Bitmap
import android.os.Bundle
import android.view.PixelCopy
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.ChunkIndex
import java.util.concurrent.ConcurrentHashMap

// Stage 3C playback probe, debug variant only (docs/PHASE3C_DESIGN.md
// Section 5). Plays the segments of one index object through ExoPlayer over
// guarded buffers, measures time to first frame, the gap at each segment
// boundary, seek latency and landing position, and memory, then logs the
// numbers. FLAG_SECURE on the window and a secure SurfaceView keep the frames
// out of captures and the recents card. A lock, or the activity stopping,
// releases the player, which closes every data source and frees its buffer.
// Not exported; reached from the vault screen's video_play action.
@androidx.annotation.OptIn(UnstableApi::class)
class VideoProbeActivity : Activity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        // <index hex | latest>[:seeks=<n>][:hold=<ms>]
        const val EXTRA_ARG = "arg"
        const val EXTRA_AUTO = "auto"
        const val SEEK_TIMEOUT_MS = 8000L
    }

    // frameUs is the time the frame listener reported for the first frame
    // released after the seek; via names the signal (the frame listener; the
    // first-frame callback of the player can arrive late from an earlier seek).
    class SeekResult(
        val targetUs: Long, val segment: Int, val key: Int, val keyTimeUs: Long, val latencyMs: Long,
        val positionUs: Long, val frameUs: Long, val via: String
    ) {
        fun line(): String = "target_ms=${targetUs / 1000} segment=$segment key=$key key_ms=${keyTimeUs / 1000} latency_ms=$latencyMs position_ms=${positionUs / 1000} frame_ms=${if (frameUs >= 0) frameUs / 1000 else -1} via=$via"
    }

    // Everything the run measured. Tests read it through the activity.
    class Report {
        // Pixel samples of the SurfaceView (PixelCopy): mean luma 0..255 and
        // distinct sampled colours, taken while playing and during the hold.
        val surfaceSamples = ArrayList<String>()
        var indexHex = ""
        var segments = 0
        var firstFrameMs = -1L
        val boundaryGapsMs = ArrayList<Long>()
        var transitions = 0
        val seeks = ArrayList<SeekResult>()
        // Distinct landings the plan found; fewer than asked when the clip
        // has fewer segments or shorter ones.
        var seeksPlanned = 0
        val errors = ArrayList<String>()
        var framesRendered = 0
        var droppedFrames = 0
        var peakLiveBytes = 0L
        var peakLiveBuffers = 0
        var pssPeakKiB = 0
        var nativePeakKiB = 0L
        var javaPeakKiB = 0L
        var endedOnce = false
        var seekTimeouts = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock: LockController get() = PvApp.of(this).lock
    private val vaults: VaultSession get() = PvApp.of(this).vaultSession
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var playButton: Button
    private lateinit var seekButton: Button
    private var player: ExoPlayer? = null
    private var index: VideoIndex? = null
    private var auto = false
    private var seekCount = 10
    private var holdMs = 0L
    // secure=0 in the argument leaves the SurfaceView unsecured (the window keeps FLAG_SECURE), for the black-surface comparison on the Xiaomi.
    private var secureSurface = true
    // plain=1 (Stage 3E trial): the bare FragmentedMp4Extractor with no seek
    // map and no rotation from the index, so a segment that carries sidx,
    // tfdt, and the tkhd rotation is read the standard way.
    private var plainExtractor = false
    private var started = false
    private var prepareNs = 0L
    private var seekNs = 0L
    @Volatile private var seekPending = false
    @Volatile private var seekFrameUs = -1L
    @Volatile private var seekSeenNs = 0L
    @Volatile private var seekVia = ""
    @Volatile private var lastFrameUs = -1L
    @Volatile private var lastReleaseNs = 0L
    private var seekPlan: List<Long> = emptyList()
    private var nextSeek = 0
    private var sampler: Sampler? = null
    private val seekMaps = ConcurrentHashMap<Int, ChunkIndex>()

    val report = Report()
    val live = LiveBuffers { say(it) }
    @Volatile var done = false
        private set
    @Volatile var playerReleased = false
        private set
    @Volatile var firstFrameRendered = false
        private set
    // Set once setSecure(true) was called on the surface view.
    var surfaceSecure = false
        private set

    private fun say(line: String) {
        Log.i(TAG, line)
        main.post {
            if (this::status.isInitialized) status.text = line
        }
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
        lock.addListener(this)
        val arg = intent?.getStringExtra(EXTRA_ARG) ?: "latest"
        auto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
        val parts = arg.split(':')
        for (p in parts.drop(1)) {
            when {
                p.startsWith("seeks=") -> seekCount = p.substringAfter('=').toIntOrNull() ?: 10
                p.startsWith("hold=") -> holdMs = p.substringAfter('=').toLongOrNull() ?: 0L
                p.startsWith("secure=") -> secureSurface = p.substringAfter('=') != "0"
                p.startsWith("plain=") -> plainExtractor = p.substringAfter('=') == "1"
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        surface = SurfaceView(this)
        surface.setSecure(secureSurface)
        surfaceSecure = secureSurface
        root.addView(FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = Button(this).apply {
            text = "Play"
            isEnabled = false
            setOnClickListener { togglePlay() }
        }
        seekButton = Button(this).apply {
            text = "Seek"
            isEnabled = false
            setOnClickListener { manualSeek() }
        }
        buttons.addView(playButton)
        buttons.addView(seekButton)
        buttons.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })
        root.addView(buttons)
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            text = "loading index"
        }
        root.addView(status)
        setContentView(root)
        PhotoLoader.executor.execute { loadIndex(parts[0]) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        routeIfLocked()
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

    // Finds and decodes the index object: the given ID, or the newest object
    // that decodes as an index.
    private fun loadIndex(selector: String) {
        val v = vaults.vault
        if (v == null) {
            finishWith("video_play: vault not open")
            return
        }
        var found: VideoIndex? = null
        var hex = ""
        if (selector == "latest") {
            for (e in v.listObjects()) {
                if (e.size > VideoIndex.OBJECT_CAP + 4096) continue
                val d = VideoIndex.read(v, e.id)
                if (d.status == NativeBridge.OK && d.index != null) {
                    found = d.index
                    hex = e.hex
                    break
                }
            }
            if (found == null) {
                finishWith("video_play: no index object in the vault")
                return
            }
        } else {
            if (selector.length != 32 || selector.any { it !in "0123456789abcdef" }) {
                finishWith("video_play: bad index id")
                return
            }
            val d = VideoIndex.read(v, NativeBridge.fromHex(selector))
            if (d.status != NativeBridge.OK || d.index == null) {
                finishWith("video_play: index ${NativeBridge.errorName(d.status)}")
                return
            }
            found = d.index
            hex = selector
        }
        val idx = found ?: return
        report.indexHex = hex
        report.segments = idx.segments.size
        say("video_play: index $hex segments=${idx.segments.size} keys=${idx.keysTotal} duration_ms=${idx.header.totalDurationUs / 1000} ${idx.header.width}x${idx.header.height} rotation=${idx.header.rotation} frame_rate_millihz=${idx.header.frameRateMillihz}")
        main.post { if (started && !isFinishing) buildPlayer(v, idx) }
    }

    private fun finishWith(line: String) {
        say(line)
        report.errors.add(line)
        done = true
        say("video_play: done")
        main.post { finish() }
    }

    private fun buildPlayer(vault: Vault, idx: VideoIndex) {
        index = idx
        val sources = GuardedSegmentDataSource.Factory(vault, idx, live, seekMaps)
        val extractors: ExtractorsFactory = if (plainExtractor) {
            ExtractorsFactory { arrayOf(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)) }
        } else {
            SeekableSegmentExtractor.Factory(seekMaps)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 4000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(sources, extractors))
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_ENDED -> {
                        say("video_play: ended at ${p.currentPosition}ms of item ${p.currentMediaItemIndex}")
                        if (!report.endedOnce) {
                            report.endedOnce = true
                            if (auto) main.post { startSeeks() }
                        }
                    }
                    Player.STATE_READY -> if (!auto) {
                        playButton.isEnabled = true
                        seekButton.isEnabled = true
                    }
                    else -> {}
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) report.transitions++
                say("video_play: item ${p.currentMediaItemIndex} reason=$reason")
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                say("video_play: discontinuity reason=$reason from item ${old.mediaItemIndex} ${old.positionMs}ms to item ${new.mediaItemIndex} ${new.positionMs}ms")
            }

            override fun onRenderedFirstFrame() {
                if (!firstFrameRendered) {
                    firstFrameRendered = true
                    report.firstFrameMs = (System.nanoTime() - prepareNs) / 1_000_000
                    val f = p.videoFormat
                    say("video_play: first frame after ${report.firstFrameMs}ms format_rotation=${f?.rotationDegrees} ${f?.width}x${f?.height} plain=$plainExtractor")
                    main.postDelayed({ if (player != null) sampleSurface("playing") }, 2000)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                say("video_play: size ${videoSize.width}x${videoSize.height} unapplied_rotation=${videoSize.unappliedRotationDegrees}")
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val text = "player error ${error.errorCodeName}: ${cause?.javaClass?.simpleName}: ${cause?.message}"
                report.errors.add(text)
                say("video_play: $text")
                if (auto) finishProbe()
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
        p.playWhenReady = auto
        player = p
        sampler = Sampler().also { it.start() }
        prepareNs = System.nanoTime()
        p.prepare()
        say("video_play: prepared ${items.size} items, auto=$auto secure_surface=$secureSurface")
    }

    // Playback thread. A frame whose time is below the previous one is the
    // first of the next segment; the gap is measured between release times.
    private fun onFrame(presentationTimeUs: Long, releaseTimeNs: Long) {
        report.framesRendered++
        if (seekPending) {
            seekPending = false
            seekFrameUs = presentationTimeUs
            seekSeenNs = System.nanoTime()
            seekVia = "frame_listener"
        } else if (lastFrameUs >= 0 && presentationTimeUs < lastFrameUs && lastReleaseNs > 0) {
            val gapMs = (releaseTimeNs - lastReleaseNs) / 1_000_000
            synchronized(report.boundaryGapsMs) { report.boundaryGapsMs.add(gapMs) }
            Log.i(TAG, "video_play: boundary gap_ms=$gapMs (last_frame_ms=${lastFrameUs / 1000} next_frame_ms=${presentationTimeUs / 1000})")
        }
        lastFrameUs = presentationTimeUs
        lastReleaseNs = releaseTimeNs
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        p.playWhenReady = !p.playWhenReady
        playButton.text = if (p.playWhenReady) "Pause" else "Play"
    }

    // Clip times to seek to: segment starts first (up to half of them, spread
    // over the clip), then times inside segments, each landing on a key frame
    // no earlier seek landed on. A seek to the position the player already
    // holds renders nothing, so repeats are left out.
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

    // Copies the SurfaceView content into a small bitmap and logs its mean
    // luma and colour count (no image is kept or written). Tells whether the
    // decoder output reaches the surface when the screen looks black.
    private fun sampleSurface(label: String) {
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) {
            say("video_play: surface_pixels $label: view ${w}x$h, no surface")
            return
        }
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    say("video_play: surface_pixels $label: PixelCopy result $result (view ${w}x$h valid=${surface.holder.surface.isValid})")
                } else {
                    var sum = 0L
                    val colours = HashSet<Int>()
                    for (y in 0 until 64) for (x in 0 until 64) {
                        val c = bmp.getPixel(x, y)
                        sum += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
                        colours.add(c and 0xffffff)
                    }
                    val line = "$label: mean_luma=${sum / 4096} distinct_colours=${colours.size} view=${w}x$h"
                    report.surfaceSamples.add(line)
                    say("video_play: surface_pixels $line")
                }
                bmp.eraseColor(0)
                bmp.recycle()
            }, main)
        } catch (e: Exception) {
            say("video_play: surface_pixels $label: ${e.javaClass.simpleName}: ${e.message}")
            bmp.recycle()
        }
    }

    private fun startSeeks() {
        val idx = index ?: return
        val p = player ?: return
        p.pause()
        seekPlan = planSeeks(idx, seekCount)
        report.seeksPlanned = seekPlan.size
        nextSeek = 0
        say("video_play: seeking to ${seekPlan.size} positions")
        runNextSeek()
    }

    private fun runNextSeek() {
        val idx = index ?: return
        val p = player ?: return
        if (nextSeek >= seekPlan.size) {
            finishProbe()
            return
        }
        val target = seekPlan[nextSeek++]
        val loc = idx.locate(target)
        val seg = idx.segments[loc.segment]
        seekFrameUs = -1L
        seekPending = true
        seekNs = System.nanoTime()
        p.seekTo(loc.segment, (loc.keyTimeUs - seg.startUs) / 1000)
        waitSeek(target, loc, seg)
    }

    private fun waitSeek(target: Long, loc: VideoIndex.Location, seg: VideoIndex.Segment) {
        main.postDelayed({
            val p = player ?: return@postDelayed
            if (!seekPending) {
                val latency = (seekSeenNs - seekNs) / 1_000_000
                val item = p.currentMediaItemIndex
                val positionUs = idx(item).startUs + p.currentPosition * 1000
                val r = SeekResult(target, loc.segment, loc.key, loc.keyTimeUs, latency, positionUs,
                    if (seekFrameUs >= 0) seg.startUs + seekFrameUs else -1L, seekVia)
                report.seeks.add(r)
                say("video_play: seek ${r.line()}")
                runNextSeek()
            } else if ((System.nanoTime() - seekNs) / 1_000_000 > SEEK_TIMEOUT_MS) {
                report.seekTimeouts++
                say("video_play: seek to ${target / 1000}ms: no frame within ${SEEK_TIMEOUT_MS}ms")
                runNextSeek()
            } else {
                waitSeek(target, loc, seg)
            }
        }, 20)
    }

    private fun idx(item: Int): VideoIndex.Segment = index!!.segments[item.coerceIn(0, index!!.segments.size - 1)]

    private fun manualSeek() {
        val idx = index ?: return
        if (seekPlan.isEmpty()) seekPlan = planSeeks(idx, seekCount)
        if (nextSeek >= seekPlan.size) nextSeek = 0
        val p = player ?: return
        val target = seekPlan[nextSeek++]
        val loc = idx.locate(target)
        val seg = idx.segments[loc.segment]
        seekFrameUs = -1L
        seekPending = true
        seekNs = System.nanoTime()
        p.seekTo(loc.segment, (loc.keyTimeUs - seg.startUs) / 1000)
        waitSeek(target, loc, seg)
    }

    private fun finishProbe() {
        if (done) return
        done = true
        sampler?.finish()
        report.peakLiveBytes = live.peakBytes
        report.peakLiveBuffers = live.peakCount
        val gaps = synchronized(report.boundaryGapsMs) { ArrayList(report.boundaryGapsMs) }
        say("video_play: summary first_frame_ms=${report.firstFrameMs} transitions=${report.transitions} boundary_gaps_ms=${gaps.joinToString(",")} frames=${report.framesRendered} dropped=${report.droppedFrames} seeks=${report.seeks.size} seek_timeouts=${report.seekTimeouts} errors=${report.errors.size}")
        say("video_play: memory peak_live_buffers=${report.peakLiveBuffers} peak_live_bytes=${report.peakLiveBytes} opens=${live.opens} closes=${live.closes} pss_peak=${report.pssPeakKiB}KiB native_heap_peak=${report.nativePeakKiB}KiB java_heap_peak=${report.javaPeakKiB}KiB")
        say("video_play: done")
        if (holdMs > 0) {
            main.postDelayed({ if (player != null) sampleSurface("hold") }, 1500)
            main.postDelayed({ finish() }, holdMs)
        } else {
            finish()
        }
    }

    private fun releasePlayer(reason: String) {
        val p = player ?: return
        player = null
        sampler?.finish()
        p.release()
        // release() returns before the loader thread has closed a data
        // source still open; wait for that close (and its wipe) briefly.
        val t0 = System.nanoTime()
        while (live.liveCount > 0 && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(5)
        playerReleased = true
        say("video_play: player released ($reason) live_buffers=${live.liveCount} live_bytes=${live.liveBytes} opens=${live.opens} closes=${live.closes} close_wait_ms=${(System.nanoTime() - t0) / 1_000_000}")
        if (!done) {
            done = true
            say("video_play: done")
        }
    }

    private inner class Sampler : Thread("pv-play-sample") {
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
