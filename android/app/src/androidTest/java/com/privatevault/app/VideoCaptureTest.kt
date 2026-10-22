package com.privatevault.app

import android.Manifest
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// T2 for Stage 3E (docs/PHASE3E_DESIGN.md Section 7): a recording from the
// feature path with the viewfinder live writes segments that carry tfdt,
// sidx, and the tkhd rotation and pass the compatibility gate (framework
// and media3 extraction with key counts equal to the index); the clip plays
// through the standard path and seeks inside a segment land on the key
// frame; an old-shape clip still plays through the wrapper; the wrapper on
// a new clip is pinned as a known limit; a lock during a recording keeps and
// indexes the segments; the low-space, encoder-error, and queue-full hooks
// stop with their reason and an index; a recording without an index leaves
// orphans the next sweep removes; FLAG_SECURE holds while recording; the
// leak scan is clean; the read-ahead on a 60 s clip stays bounded. Runs on
// the app vault with the throwaway test PIN; the vault is erased afterwards.
@RunWith(AndroidJUnit4::class)
class VideoCaptureTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val lock = app.lock
    private val recorder = app.videoRecorder
    private val pin = "246810".toByteArray(Charsets.US_ASCII)

    // A viewfinder output for the effect: an ImageReader the GL splitter
    // draws into, every image closed on arrival.
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
        app.captureSession.resume()
        lock.testHooks.kdfOverride = null
    }

    private fun clearHooks() {
        recorder.testHooks.stopAt = null
        recorder.testHooks.skipIndex = false
        NativeBridge.nativeSetTestHooks(0, 0)
    }

    private fun log(line: String) = android.util.Log.i("PrivateVaultTest", line)

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

    private fun waitDestroyed(scenario: ActivityScenario<*>) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (scenario.state != Lifecycle.State.DESTROYED && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private fun waitSweep(): VideoItems.SweepResult {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (lock.lastSweep == null && System.nanoTime() < deadline) Thread.sleep(50)
        return lock.lastSweep ?: throw AssertionError("no sweep ran")
    }

    private fun relock() {
        lock.lock("test")
        lock.lastSweep = null
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
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

    private class Recording(val result: VideoRecorder.Result, val viewfinderFrames: Int)

    // Records through the feature path for clipMs on a test lifecycle. The
    // body runs once the first segment is encrypted (for the hooks); stop
    // may be null to let the caller end the recording.
    private fun record(clipMs: Long, afterFirstSegment: (() -> Unit)? = null, stop: String? = "test"): Recording {
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val vf = TestViewfinder()
        val latch = CountDownLatch(1)
        var result: VideoRecorder.Result? = null
        try {
            // The 3E checks cover the single-track shape; AudioCaptureTest covers the audio.
            val refused = recorder.start(provider, owner, CameraSelector.LENS_FACING_BACK, vf, false) { r ->
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
        return Recording(r, vf.frames.get())
    }

    private fun readIndex(hex: String): VideoIndex {
        val d = VideoIndex.read(app.vaultSession.vault!!, NativeBridge.fromHex(hex))
        assertEquals(NativeBridge.errorName(d.status), NativeBridge.OK, d.status)
        return d.index!!
    }

    // The compatibility gate and the container shape, per segment.
    private fun checkSegments(idx: VideoIndex) {
        val vault = app.vaultSession.vault!!
        for ((n, s) in idx.segments.withIndex()) {
            val status = IntArray(1)
            val plain = NativeBridge.nativeDecryptToArray(vault.rawHandle, s.id, (9 shl 20), status)
            assertNotNull("segment $n ${NativeBridge.errorName(status[0])}", plain)
            try {
                val payload = plain!!.copyOfRange(NativeBridge.ITEM_HEADER_BYTES, plain.size)
                assertEquals(s.byteLen, payload.size.toLong())
                val boxes = Mp4Boxes.walk(ByteBuffer.wrap(payload))
                log("segment $n ${boxes.line()}")
                assertNull(boxes.error, boxes.error)
                assertEquals(s.keyOffsetsUs.size, boxes.moofCount)
                assertEquals(boxes.moofCount, boxes.sidxReferences)
                assertEquals(idx.header.rotation, boxes.tkhdRotation)
                assertEquals(boxes.moofCount, boxes.tfdts.size)
                for ((k, t) in boxes.tfdts.withIndex()) assertEquals("segment $n fragment $k", s.keyOffsetsUs[k].toLong(), t)
                val framework = SegmentVerify.inspect(payload)
                log("segment $n framework: $framework")
                assertTrue(framework, framework.contains("sync=${s.keyOffsetsUs.size} "))
                // The framework reports no rotation key for 0.
                val frameworkRotation = if (idx.header.rotation == 0) "rotation=-1 " else "rotation=${idx.header.rotation} "
                assertTrue(framework, framework.contains(frameworkRotation))
                assertTrue(framework, framework.contains("first_frame_decoded=yes"))
                val media3 = Media3Sync.count(payload)
                log("segment $n media3: $media3")
                assertNull(media3.error, media3.error)
                assertEquals(s.keyOffsetsUs.size, media3.syncSamples)
                assertEquals(s.keyOffsetsUs.map { it.toLong() }, media3.syncTimesUs)
                payload.fill(0)
            } finally {
                plain!!.fill(0)
            }
        }
    }

    private fun launchVideo(hex: String, script: String? = null): ActivityScenario<VideoActivity> {
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_ID, hex)
        if (script != null) intent.putExtra(VideoActivity.EXTRA_SCRIPT, script)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun recordPlayStopsAndOrphans() {
        val before = LeakScan.run(context)
        assertTrue(before.lines().joinToString("\n"), realFindings(before).isEmpty())

        // Clip A from the feature path, viewfinder live.
        val a = record(10_000)
        assertTrue(a.result.line(), a.result.ok)
        assertEquals("test", a.result.reason)
        assertTrue("segments ${a.result.segments}", a.result.segments >= 2)
        assertTrue("viewfinder frames ${a.viewfinderFrames}", a.viewfinderFrames > 10)
        val hexA = a.result.indexHex!!
        val idxA = readIndex(hexA)
        assertEquals(a.result.segments, idxA.segments.size)
        assertEquals(idxA.segments.size + 1, objectHexes().size)
        checkSegments(idxA)

        // The standard path: every segment reports tfdt, seeks land on the key frame.
        var act: VideoActivity? = null
        launchVideo(hexA, "seeks=4").use { s ->
            s.onActivity { act = it }
            waitFor(s, "playback and seeks", 120) { it.report.scriptDone }
        }
        val r = act!!.report
        log("standard path: first_frame_ms=${r.firstFrameMs} transitions=${r.transitions} gaps=${r.boundaryGapsMs} seeks=${r.seeks.map { it.line() }} tfdt=${act!!.segmentTfdt}")
        assertTrue(r.errors.joinToString(), r.errors.isEmpty())
        assertTrue(r.ended)
        assertEquals(idxA.segments.size - 1, r.transitions)
        assertEquals(idxA.segments.size, act!!.segmentTfdt.size)
        assertTrue(act!!.segmentTfdt.values.all { it })
        assertTrue(r.seeks.size >= 3)
        assertEquals(0, r.seekTimeouts)
        var inside = 0
        for (sk in r.seeks) {
            val what = sk.line()
            assertTrue(what, Math.abs(sk.positionUs - sk.keyTimeUs) <= 1000)
            assertTrue(what, sk.frameUs < 0 || Math.abs(sk.frameUs - sk.keyTimeUs) <= 1000)
            if (idxA.segments.none { it.startUs == sk.keyTimeUs }) inside++
        }
        assertTrue("no in-segment seek landed", inside >= 1)
        assertTrue(act!!.playerReleased)
        assertEquals(0, act!!.live.liveCount)

        // The wrapper on a new clip (known limit, pinned): the probe always
        // takes the wrapper and still lands on the key frame.
        ensureUnlocked()
        var probe: VideoProbeActivity? = null
        ActivityScenario.launch<VideoProbeActivity>(
            Intent(context, VideoProbeActivity::class.java)
                .putExtra(VideoProbeActivity.EXTRA_ARG, "$hexA:seeks=4")
                .putExtra(VideoProbeActivity.EXTRA_AUTO, true)
        ).use { s ->
            s.onActivity { probe = it }
            waitFor(s, "the probe", 120) { it.done }
        }
        val pr = probe!!.report
        log("wrapper on a tfdt clip: seeks=${pr.seeks.map { it.line() }} errors=${pr.errors}")
        assertTrue(pr.errors.joinToString(), pr.errors.isEmpty())
        assertTrue(pr.seeks.size >= 3)
        for (sk in pr.seeks) {
            assertTrue(sk.line(), Math.abs(sk.positionUs - sk.keyTimeUs) <= 1000)
            assertTrue(sk.line(), sk.frameUs < 0 || Math.abs(sk.frameUs - sk.keyTimeUs) <= 1000)
        }

        // An old-shape clip (the 3D muxer) still plays through the wrapper.
        ensureUnlocked()
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val old = try {
            V3Probe(context, owner, provider, CameraSelector.LENS_FACING_BACK, app.vaultSession, ::log, clipMs = 6_000L).run()
        } finally {
            instrumentation.runOnMainSync { provider.unbindAll(); owner.destroy() }
        }
        assertNotNull(old.indexId)
        val hexOld = NativeBridge.hex(old.indexId!!)
        ensureUnlocked()
        var actOld: VideoActivity? = null
        launchVideo(hexOld).use { s ->
            s.onActivity { actOld = it }
            waitFor(s, "the old clip", 90) { it.report.ended || it.report.errors.isNotEmpty() }
        }
        log("wrapper path: tfdt=${actOld!!.segmentTfdt} errors=${actOld!!.report.errors}")
        assertTrue(actOld!!.report.errors.isEmpty())
        assertTrue(actOld!!.segmentTfdt.isNotEmpty())
        assertTrue(actOld!!.segmentTfdt.values.none { it })

        // A lock during a recording: the segments so far are indexed, the vault locks.
        ensureUnlocked()
        val countBefore = objectHexes().size
        val locked = record(5_000, stop = null, afterFirstSegment = {
            Thread.sleep(1500)
            lock.lock("test")
        })
        assertEquals(LockState.LOCKED, lock.state)
        assertEquals("lock", locked.result.reason)
        assertTrue(locked.result.line(), locked.result.ok)
        assertTrue(VideoItems.recordingGate.tryLock())
        VideoItems.recordingGate.unlock()
        lock.lastSweep = null
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)
        val idxLock = readIndex(locked.result.indexHex!!)
        val present = objectHexes()
        for (s in idxLock.segments) assertTrue("segment ${s.hex} missing", s.hex in present)
        assertEquals(countBefore + idxLock.segments.size + 1, present.size)
        checkSegments(idxLock)

        // The stop hooks: each ends with its reason and an index over the segments so far.
        for (hook in listOf("LOW_STORAGE", "ENCODER_ERROR", "QUEUE_FULL")) {
            ensureUnlocked()
            val h = record(20_000, afterFirstSegment = { recorder.testHooks.stopAt = hook })
            assertEquals(hook, h.result.reason)
            assertTrue(h.result.line(), h.result.ok)
            assertTrue(h.result.segments >= 1)
            val idx = readIndex(h.result.indexHex!!)
            assertEquals(h.result.segments, idx.segments.size)
        }

        // No index (a process kill stand-in): orphans, removed by the next sweep.
        ensureUnlocked()
        val objectsBefore = objectHexes().size
        recorder.testHooks.skipIndex = true
        val orphan = record(4_000)
        assertNull(orphan.result.indexHex)
        assertTrue(orphan.result.segments >= 1)
        assertEquals(objectsBefore + orphan.result.segments, objectHexes().size)
        relock()
        val sw = waitSweep()
        log("sweep after the index-less recording: ${sw.line()}")
        assertNull(sw.skipped, sw.skipped)
        assertEquals(orphan.result.segments, sw.deleted)
        assertEquals(objectsBefore, objectHexes().size)

        // FLAG_SECURE on the capture screen while recording.
        var cap: CaptureActivity? = null
        ActivityScenario.launch<CaptureActivity>(
            Intent(context, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_ACTIONS, "record=3000")
        ).use { s ->
            s.onActivity { cap = it }
            waitFor(s, "the recording to start", 60) { it.isRecording }
            s.onActivity { a ->
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(a.videoMode)
            }
            waitFor(s, "the recording to end", 90) { !it.isRecording && recorder.lastResult != null }
        }
        val screenResult = recorder.lastResult!!
        log("capture screen: ${screenResult.line()}")
        assertTrue(screenResult.line(), screenResult.ok)

        ensureUnlocked()
        val after = LeakScan.run(context)
        assertTrue(after.lines().joinToString("\n"), realFindings(after).isEmpty())
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") && !isLockPrefs(it.path) }
        assertTrue("new non-object files: ${fresh.map { it.path }}", fresh.isEmpty())
    }

    // A 60 s clip: the read-ahead opens at most a few segments ahead and
    // holds at most two guarded buffers at once.
    @Test
    fun readAheadBoundOnLongClip() {
        val a = record(60_000)
        assertTrue(a.result.line(), a.result.ok)
        val idx = readIndex(a.result.indexHex!!)
        // The emulator delivers 2 to 27 fps depending on its load; the bound
        // is what matters here, so a slow run still needs a long clip.
        assertTrue("segments ${idx.segments.size}", idx.segments.size >= 5)
        ensureUnlocked()
        var act: VideoActivity? = null
        val timeline = ArrayList<String>()
        launchVideo(a.result.indexHex!!).use { s ->
            s.onActivity { act = it }
            waitFor(s, "first frame", 60) { it.firstFrameRendered }
            val t0 = System.nanoTime()
            var early = -1
            while (true) {
                var ended = false
                s.onActivity { v ->
                    val secs = (System.nanoTime() - t0) / 1_000_000_000
                    timeline.add("${secs}s opens=${v.live.opens} closes=${v.live.closes} live=${v.live.liveCount} pos_ms=${v.positionMs()}")
                    if (secs >= 3 && early < 0) early = v.live.opens
                    ended = v.report.ended || v.report.errors.isNotEmpty()
                }
                if (ended) break
                if (System.nanoTime() - t0 > 120_000_000_000L) break
                Thread.sleep(2000)
            }
            log("read-ahead timeline: ${timeline.joinToString("; ")}")
            s.onActivity { v ->
                assertTrue(v.report.errors.joinToString(), v.report.errors.isEmpty())
                assertTrue("opens ${early} after 3 s of ${idx.segments.size} segments", early in 1..4)
                assertTrue("peak live buffers ${v.live.peakCount}", v.live.peakCount <= 2)
            }
        }
        log("read-ahead: ${act!!.live.opens} opens, peak live ${act!!.live.peakCount} (${act!!.live.peakBytes} bytes), pss_peak=${act!!.report.pssPeakKiB}KiB")
    }
}
