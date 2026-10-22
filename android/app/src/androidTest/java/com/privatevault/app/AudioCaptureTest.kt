package com.privatevault.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// T2 for Stage 3F (docs/PHASE3F_DESIGN.md Section 6): a recording with
// audio carries an AAC track both readers parse with equal counts and plays
// with the audio format reported; mute in the viewer and a focus loss pause;
// a muted recording, a revoked permission, and an old-shape clip give silent
// clips without error; a lock drains the audio; the audio hooks, the small
// caps, and the late-audio case stop or route as designed; the capture
// screen keeps FLAG_SECURE while recording with audio and resets the mute
// toggle. The emulator microphone delivers silence; counts, not sound, are
// asserted. Runs on the app vault with the throwaway test PIN; the vault is
// erased afterwards.
@RunWith(AndroidJUnit4::class)
class AudioCaptureTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val lock = app.lock
    private val recorder = app.videoRecorder
    private val pin = "246810".toByteArray(Charsets.US_ASCII)

    private class TestViewfinder : Preview.SurfaceProvider {
        private val thread = HandlerThread("pv-test-view").also { it.start() }
        private var reader: ImageReader? = null
        val frames = AtomicInteger(0)
        val released = CountDownLatch(1)

        override fun onSurfaceRequested(request: SurfaceRequest) {
            val res = request.resolution
            val r = ImageReader.newInstance(res.width, res.height, ImageFormat.PRIVATE, 3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            r.setOnImageAvailableListener({ rd ->
                rd.acquireLatestImage()?.close()
                frames.incrementAndGet()
            }, Handler(thread.looper))
            reader = r
            request.provideSurface(r.surface, { it.run() }) { released.countDown() }
        }

        fun close() {
            released.await(3, TimeUnit.SECONDS)
            reader?.close()
            thread.quitSafely()
        }
    }

    @Before
    fun setUp() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        lock.testHooks.kdfOverride = 2L to (64L shl 20)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        clearHooks()
    }

    @After
    fun tearDown() {
        clearHooks()
        if (recorder.isRecording) recorder.stopAndWait("teardown", 10_000)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        // The lock left the capture session closing; the tests that follow
        // build their vaults without an unlock, so resume it here.
        app.captureSession.resume()
        lock.testHooks.kdfOverride = null
    }

    private fun clearHooks() {
        recorder.testHooks.stopAt = null
        recorder.testHooks.skipIndex = false
        recorder.testHooks.audioQueueCap = 0
        recorder.testHooks.audioStageCap = 0
        recorder.testHooks.audioDelayMs = 0
        recorder.testHooks.videoDelayMs = 0
        recorder.testHooks.denyAudio = false
        NativeBridge.nativeSetTestHooks(0, 0)
    }

    private fun log(line: String) = android.util.Log.i("PrivateVaultTest", line)

    // A revoked permission kills the process, so the recorder is told the
    // permission is missing instead; the real revocation runs from the host.
    private fun grantAudio(on: Boolean) {
        recorder.testHooks.denyAudio = !on
        assertEquals(on, recorder.audioPermitted())
    }

    private fun isLockPrefs(path: String): Boolean = path.contains("/shared_prefs/privatevault_") && path.endsWith(".xml")

    private fun realFindings(r: LeakScan.Report): List<String> {
        val chance = r.findings.filter { it.path.endsWith(".pvo") && it.offset > 4 && (it.kind == "jpeg_soi" || it.kind == "mp4_ftyp") }
        for (c in chance) log("chance match in ciphertext: ${c.kind} at ${c.offset} in ${c.path}")
        val rest = r.findings.filter { it !in chance }.map { "${it.kind} at ${it.offset} in ${it.path}" }
        return rest + r.mediaStoreRows.filter { it.value > 0 }.map { "MediaStore ${it.key} rows: ${it.value}" }
    }

    private fun <A : android.app.Activity> waitFor(scenario: ActivityScenario<A>, what: String, seconds: Long, check: (A) -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            var ok = false
            scenario.onActivity { a -> ok = check(a) }
            if (ok) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // With no activity in front the app locks 700 ms after the last one
    // stopped; lets that pass and unlocks again.
    private fun ensureUnlocked() {
        Thread.sleep(900)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (lock.state == LockState.LOCKING && System.nanoTime() < deadline) Thread.sleep(50)
        if (lock.state == LockState.LOCKED) {
            lock.lastSweep = null
            assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        }
        assertEquals(LockState.UNLOCKED, lock.state)
    }

    private fun objectHexes(): Set<String> = app.vaultSession.vault!!.listObjects().map { it.hex }.toSet()

    // Records through the feature path for clipMs on a test lifecycle.
    private fun record(clipMs: Long, audio: Boolean = true, afterFirstSegment: (() -> Unit)? = null, stop: String? = "test"): VideoRecorder.Result {
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val vf = TestViewfinder()
        val latch = CountDownLatch(1)
        var result: VideoRecorder.Result? = null
        try {
            val refused = recorder.start(provider, owner, CameraSelector.LENS_FACING_BACK, vf, audio) { r ->
                result = r
                latch.countDown()
            }
            assertNull(refused, refused)
            val t0 = System.nanoTime()
            if (afterFirstSegment != null) {
                while (synchronized(recorder.done) { recorder.done.size } < 1 && recorder.isRecording && System.nanoTime() - t0 < 30_000_000_000L) Thread.sleep(50)
                afterFirstSegment()
            }
            while (recorder.isRecording && System.nanoTime() - t0 < clipMs * 1_000_000L) Thread.sleep(50)
            if (stop != null) recorder.stop(stop)
            assertTrue("recording did not end", latch.await(90, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { provider.unbindAll(); owner.destroy() }
            vf.close()
        }
        val r = result!!
        log(r.line())
        return r
    }

    private fun readIndex(hex: String): VideoIndex {
        val d = VideoIndex.read(app.vaultSession.vault!!, NativeBridge.fromHex(hex))
        assertEquals(NativeBridge.errorName(d.status), NativeBridge.OK, d.status)
        return d.index!!
    }

    // The readback gate: both readers over every segment, the lines logged,
    // and the per-segment results counted. Returns the media3 audio sample
    // count summed over the segments.
    // allowFrameworkGap accepts a segment whose audio the framework extractor
    // does not read because it starts after two fragments without audio (the
    // late-audio shape; GoldenVectorTest pins that reader limit).
    private fun readback(hex: String, wantAudio: Boolean, maxOverrunUs: Long = 50_000, allowFrameworkGap: Boolean = false): Int {
        val lines = ArrayList<String>()
        Readback.run(hex, app.vaultSession.vault!!) { l ->
            lines.add(l)
            log(l)
        }
        val idx = readIndex(hex)
        val results = lines.filter { it.contains(" result video=") }
        assertEquals(idx.segments.size, results.size)
        for ((n, r) in results.withIndex()) {
            assertTrue(r, r.contains("video=ok"))
            val gap = allowFrameworkGap && r.contains("audio=MISMATCH") &&
                lines.any { it.contains("readback: segment $n framework audio") && it.contains("samples=0 ") } &&
                lines.any { it.contains("readback: segment $n media3 audio") && !it.contains("samples=0 ") }
            if (gap) log("segment $n: audio read by media3 only (the framework stops after two fragments without the track)")
            else assertTrue(r, r.contains(if (wantAudio) "audio=ok" else "audio=none"))
            // The audio of a middle segment ends with its video; the last
            // segment keeps the tail the encoder lag leaves (D-047).
            val past = r.substringAfter("audio_past_video_us=").toLong()
            if (wantAudio && n < results.size - 1) assertTrue(r, past <= maxOverrunUs)
        }
        var audioSamples = 0
        for (l in lines) {
            if (l.contains(" media3 audio ")) {
                audioSamples += l.substringAfter("samples=").substringBefore(" ").toInt()
                // The audio of a segment starts within one frame plus the anchor lag.
                val firstUs = l.substringAfter("first_us=").substringBefore(" ").toLong()
                assertTrue(l, firstUs in 0..60_000)
            }
        }
        return audioSamples
    }

    private fun launchVideo(hex: String, script: String? = null): ActivityScenario<VideoActivity> {
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_ID, hex)
        if (script != null) intent.putExtra(VideoActivity.EXTRA_SCRIPT, script)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun recordWithAudioReadsAndPlays() {
        val before = LeakScan.run(context)
        assertTrue(before.lines().joinToString("\n"), realFindings(before).isEmpty())

        val a = record(8_000)
        assertTrue(a.line(), a.ok)
        assertEquals("on", a.audio)
        assertTrue(a.line(), a.segments >= 2)
        assertTrue(a.line(), a.audioFrames > 50)
        // Every frame after the first video frame went into a segment.
        assertEquals(a.line(), a.audioFrames, a.audioMoved + a.audioBeforeZero)
        assertEquals(0, a.audioClamped)
        assertEquals(0, a.closingTimeouts)
        val idx = readIndex(a.indexHex!!)
        assertEquals(NativeBridge.AUDIO_CODEC_AAC, idx.header.audioCodec)
        assertEquals(VideoRecorder.AUDIO_CHANNELS, idx.header.audioChannels)
        assertEquals(VideoRecorder.AUDIO_RATE, idx.header.audioSampleRate)
        val samples = readback(a.indexHex!!, true)
        assertEquals(a.line(), a.audioMoved, samples)

        // Playback: the audio track plays, mute sets the volume, a focus loss pauses.
        var act: VideoActivity? = null
        val focus = CountDownLatch(1)
        launchVideo(a.indexHex!!).use { s ->
            s.onActivity { act = it }
            waitFor(s, "first frame", 60) { it.firstFrameRendered }
            waitFor(s, "the audio format", 30) { it.report.audioFormat != null }
            waitFor(s, "playing", 20) { it.isPlaying }
            s.onActivity { v ->
                assertFalse(v.silent)
                v.setMuted(true)
                assertTrue(v.muted)
                v.setMuted(false)
            }
            // Another app takes the focus: the player pauses.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }
                .build()
            instrumentation.runOnMainSync { if (am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus.countDown() }
            assertTrue(focus.await(2, TimeUnit.SECONDS))
            waitFor(s, "the pause on focus loss", 10) { !it.isPlaying }
            s.onActivity { v -> assertTrue(v.report.errors.joinToString(), v.report.errors.isEmpty()) }
            am.abandonAudioFocusRequest(request)
        }
        log("playback: audio=${act!!.report.audioFormat} underruns=${act!!.report.audioUnderruns} first_frame_ms=${act!!.report.firstFrameMs}")
        assertTrue(act!!.report.audioFormat!!.contains("audio/mp4a-latm"))
        assertTrue(act!!.playerReleased)

        ensureUnlocked()
        val after = LeakScan.run(context)
        assertTrue(after.lines().joinToString("\n"), realFindings(after).isEmpty())
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") && !isLockPrefs(it.path) }
        assertTrue("new non-object files: ${fresh.map { it.path }}", fresh.isEmpty())
    }

    @Test
    fun mutedDeniedAndOldClipsPlaySilent() {
        // Muted: no audio track, audio_codec 0, silent playback without error.
        val m = record(5_000, audio = false)
        assertTrue(m.line(), m.ok)
        assertEquals("muted", m.audio)
        assertEquals(0, m.audioFrames)
        val idxM = readIndex(m.indexHex!!)
        assertEquals(NativeBridge.AUDIO_CODEC_NONE, idxM.header.audioCodec)
        assertEquals(0, idxM.header.audioChannels)
        readback(m.indexHex!!, false)
        ensureUnlocked()
        var act: VideoActivity? = null
        launchVideo(m.indexHex!!).use { s ->
            s.onActivity { act = it }
            waitFor(s, "the muted clip", 90) { it.report.ended || it.report.errors.isNotEmpty() }
        }
        assertTrue(act!!.silent)
        assertNull(act!!.report.audioFormat)
        assertTrue(act!!.report.errors.isEmpty())

        // Denied: the permission reported missing while the app runs, the next
        // recording has no audio; granted again, audio returns.
        ensureUnlocked()
        grantAudio(false)
        val d = record(5_000)
        assertTrue(d.line(), d.ok)
        assertEquals("denied", d.audio)
        assertEquals(NativeBridge.AUDIO_CODEC_NONE, readIndex(d.indexHex!!).header.audioCodec)
        readback(d.indexHex!!, false)
        ensureUnlocked()
        grantAudio(true)
        val g = record(5_000)
        assertTrue(g.line(), g.ok)
        assertEquals("on", g.audio)
        assertEquals(NativeBridge.AUDIO_CODEC_AAC, readIndex(g.indexHex!!).header.audioCodec)

        // The old shape (the 3D muxer, no audio) still plays silent.
        ensureUnlocked()
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val old = try {
            V3Probe(context, owner, provider, CameraSelector.LENS_FACING_BACK, app.vaultSession, ::log, clipMs = 5_000L).run()
        } finally {
            instrumentation.runOnMainSync { provider.unbindAll(); owner.destroy() }
        }
        assertNotNull(old.indexId)
        ensureUnlocked()
        var actOld: VideoActivity? = null
        launchVideo(NativeBridge.hex(old.indexId!!)).use { s ->
            s.onActivity { actOld = it }
            waitFor(s, "the old clip", 90) { it.report.ended || it.report.errors.isNotEmpty() }
        }
        assertTrue(actOld!!.silent)
        assertNull(actOld!!.report.audioFormat)
        assertTrue(actOld!!.report.errors.isEmpty())

        // The capture screen: mute=1 records muted, a mode switch resets the
        // toggle, FLAG_SECURE holds while recording with audio.
        ensureUnlocked()
        var cap: CaptureActivity? = null
        ActivityScenario.launch<CaptureActivity>(
            Intent(context, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_ACTIONS, "mute=1,record=3000")
        ).use { s ->
            s.onActivity { cap = it }
            waitFor(s, "the muted recording to start", 60) { it.isRecording }
            s.onActivity { a -> assertTrue(a.muted) }
            waitFor(s, "the muted recording to end", 90) { !it.isRecording && recorder.lastResult != null }
            s.onActivity { a ->
                assertEquals("muted", recorder.lastResult!!.audio)
                a.setVideoMode(false)
                a.setVideoMode(true)
                assertFalse(a.muted)
                assertFalse(a.audioDenied)
            }
            // The two mode switches rebind the camera; the emulator camera
            // fails to open for a new session while a previous one is still
            // closing, so let it settle before the screen goes.
            Thread.sleep(3000)
        }
        Thread.sleep(3000)
        ensureUnlocked()
        ActivityScenario.launch<CaptureActivity>(
            Intent(context, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_ACTIONS, "record=3000")
        ).use { s ->
            waitFor(s, "the recording to start", 60) { it.isRecording }
            s.onActivity { a ->
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(a.muted)
            }
            waitFor(s, "the recording to end", 90) { !it.isRecording && recorder.lastResult != null }
        }
        val screen = recorder.lastResult!!
        assertTrue(screen.line(), screen.ok)
        assertEquals("on", screen.audio)
    }

    @Test
    fun lockHooksCapsAndLateAudio() {
        // A lock during a recording with audio: the audio drained, the clip indexed.
        val locked = record(6_000, stop = null, afterFirstSegment = {
            Thread.sleep(1500)
            lock.lock("test")
        })
        assertEquals(LockState.LOCKED, lock.state)
        assertEquals("lock", locked.reason)
        assertTrue(locked.line(), locked.ok)
        assertEquals("on", locked.audio)
        assertTrue(VideoItems.recordingGate.tryLock())
        VideoItems.recordingGate.unlock()
        lock.lastSweep = null
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        assertEquals(NativeBridge.AUDIO_CODEC_AAC, readIndex(locked.indexHex!!).header.audioCodec)
        readback(locked.indexHex!!, true)

        // The audio hooks: each stops with its reason and an index.
        for (hook in listOf("AUDIO_ERROR", "AUDIO_TAKEN")) {
            ensureUnlocked()
            val h = record(20_000, afterFirstSegment = { recorder.testHooks.stopAt = hook })
            assertEquals(hook, h.reason)
            assertTrue(h.line(), h.ok)
            assertTrue(h.segments >= 1)
            assertEquals(h.segments, readIndex(h.indexHex!!).segments.size)
        }

        // The caps set small: the queue (96 KiB, about six seconds of audio,
        // more than a slow camera start) fills while the video drain is held
        // for eight seconds after the first segment, the stage (8 KiB) fills
        // within a fragment; both keep the clip.
        ensureUnlocked()
        recorder.testHooks.audioQueueCap = 96L shl 10
        recorder.testHooks.videoDelayMs = 8000
        val q = record(30_000)
        recorder.testHooks.audioQueueCap = 0
        recorder.testHooks.videoDelayMs = 0
        assertEquals(q.line(), "AUDIO_QUEUE_FULL", q.reason)
        assertTrue(q.line(), q.ok)
        ensureUnlocked()
        recorder.testHooks.audioStageCap = 8192
        val st = record(20_000)
        recorder.testHooks.audioStageCap = 0
        assertEquals(st.line(), "AUDIO_FULL", st.reason)
        assertTrue(st.line(), st.ok)

        // Late audio: the audio thread held for 5 s after the first segment,
        // across the next cut; that segment times out and the late frames go
        // into the one after it, none dropped.
        ensureUnlocked()
        recorder.testHooks.audioDelayMs = 5000
        val late = record(14_000)
        recorder.testHooks.audioDelayMs = 0
        assertTrue(late.line(), late.ok)
        assertTrue(late.line(), late.segments >= 3)
        assertEquals(late.line(), late.audioFrames, late.audioMoved + late.audioBeforeZero)
        assertTrue(late.line(), late.closingTimeouts >= 1)
        val samples = readback(late.indexHex!!, true, Long.MAX_VALUE, allowFrameworkGap = true)
        assertEquals(late.line(), late.audioMoved, samples)
        log("late audio: clamped=${late.audioClamped} timeouts=${late.closingTimeouts}")
    }
}
