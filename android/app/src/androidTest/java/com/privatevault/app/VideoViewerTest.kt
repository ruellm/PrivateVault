package com.privatevault.app

import android.Manifest
import android.content.Intent
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

// T2 for Stage 3D (docs/PHASE3D_DESIGN.md Section 7): a recording from the
// prototype shows in the gallery as one video tile with a bounded thumbnail
// and no segment tile; the viewer plays it through every segment, seeks
// through the index rule, pauses and resumes, and is released by a lock; a
// lock during the thumbnail decrypt cancels it; a damaged segment stops
// playback at that segment with no frame from it; a damaged index gives a
// damaged tile and the viewer's error state; delete removes index and
// segments; an interrupted delete leaves nothing in the gallery and the
// sweep after the next unlock removes the segments; the sweep skips while
// an index is unreadable; the failure hooks take their paths; a recording
// started right after an unlock loses no segment; the leak scan is clean.
// Runs on the app vault through the lock controller with the throwaway test
// PIN; the vault is erased afterwards.
@RunWith(AndroidJUnit4::class)
class VideoViewerTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val lock = app.lock
    private val pin = "246810".toByteArray(Charsets.US_ASCII)

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
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        lock.testHooks.kdfOverride = null
    }

    private fun clearHooks() {
        NativeBridge.nativeSetViewerHooks(false, 0)
        GuardedSegmentDataSource.testHooks.failReadSegment = -1
        VideoFirstFrame.testHooks.fail = false
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
    // stopped (the background rule); lets that pass and unlocks again.
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

    private fun record(clipMs: Long): V3Probe.Outcome {
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val outcome = try {
            V3Probe(context, owner, provider, CameraSelector.LENS_FACING_BACK, app.vaultSession, ::log, clipMs = clipMs).run()
        } finally {
            instrumentation.runOnMainSync { provider.unbindAll(); owner.destroy() }
        }
        assertEquals("clip end", outcome.stopReason)
        assertNotNull("no index object", outcome.indexId)
        assertTrue(outcome.segments.size >= 2)
        return outcome
    }

    private fun launchVideo(hex: String, script: String? = null): ActivityScenario<VideoActivity> {
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_ID, hex)
        if (script != null) intent.putExtra(VideoActivity.EXTRA_SCRIPT, script)
        return ActivityScenario.launch(intent)
    }

    private fun flipByte(id: ByteArray, offset: Long) {
        RandomAccessFile(app.vaultSession.vault!!.objectPath(id), "rw").use { f ->
            f.seek(offset)
            val b = f.read()
            f.seek(offset)
            f.write(b xor 1)
        }
    }

    private fun objectHexes(): Set<String> = app.vaultSession.vault!!.listObjects().map { it.hex }.toSet()

    @Test
    fun galleryViewerDeleteAndSweep() {
        val before = LeakScan.run(context)
        assertTrue(before.lines().joinToString("\n"), realFindings(before).isEmpty())

        // Clip 1.
        val out1 = record(10_000L)
        val idx = out1.index!!
        val hex = NativeBridge.hex(out1.indexId!!)
        val segHexes = idx.segments.map { it.hex }
        val objects = segHexes.size + 1
        assertEquals(objects, objectHexes().size)

        // Gallery: one video tile, no segment tile, a bounded thumbnail,
        // all gone on lock.
        var gal: GalleryActivity? = null
        ActivityScenario.launch(GalleryActivity::class.java).use { s ->
            s.onActivity { a ->
                gal = a
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            waitFor(s, "classification", 60) { a -> a.classified == objects }
            s.onActivity { a ->
                val k = a.kindOf(hex)
                assertTrue("kind ${k?.label()}", k is VideoItems.Kind.Video)
                assertEquals(idx.header.totalDurationUs, (k as VideoItems.Kind.Video).index.header.totalDurationUs)
                for (sh in segHexes) assertTrue(a.kindOf(sh) is VideoItems.Kind.Segment)
                assertEquals(1, a.visibleCount)
            }
            waitFor(s, "the thumbnail", 60) { a -> a.cache.get(hex) != null || a.thumbFailed(hex) != null }
            s.onActivity { a ->
                assertNull(a.thumbFailed(hex), a.thumbFailed(hex))
                val b = a.cache.get(hex)!!
                log("thumbnail ${b.width}x${b.height} ${a.lastThumbDetail}")
                assertTrue(maxOf(b.width, b.height) <= PhotoLoader.THUMB_PX)
                if (idx.header.rotation == 90 || idx.header.rotation == 270) assertTrue(b.height > b.width)
            }
            lock.lock("test")
            waitDestroyed(s)
            assertEquals(0, gal!!.cache.size)
        }
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)

        // A lock during the slowed thumbnail decrypt cancels it.
        NativeBridge.nativeSetViewerHooks(false, 1500)
        run {
            val s = ActivityScenario.launch(GalleryActivity::class.java)
            var g: GalleryActivity? = null
            s.onActivity { g = it }
            waitFor(s, "the thumbnail decrypt", 60) { a -> a.thumbLoading == hex }
            val t0 = System.nanoTime()
            lock.lock("test")
            val lockMs = (System.nanoTime() - t0) / 1_000_000
            waitDestroyed(s)
            NativeBridge.nativeSetViewerHooks(false, 0)
            assertEquals(0, g!!.cache.size)
            val detail = g!!.lastThumbDetail
            log("lock during the thumbnail decrypt took $lockMs ms; $detail")
            assertTrue(detail, detail.contains("CANCELLED"))
            assertTrue("lock took $lockMs ms", lockMs < 6000)
            s.close()
        }
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)

        // Viewer: FLAG_SECURE, secure surface, playback through every
        // segment, then four scripted seeks.
        val freed0 = Vault.buffersFreed.get()
        var act: VideoActivity? = null
        launchVideo(hex, "seeks=4").use { s ->
            s.onActivity { a ->
                act = a
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(a.surfaceSecure)
            }
            waitFor(s, "playback and seeks", 120) { a -> a.report.scriptDone }
        }
        val a1 = act!!
        val r = a1.report
        log("viewer report: first_frame_ms=${r.firstFrameMs} transitions=${r.transitions} gaps=${r.boundaryGapsMs} seeks=${r.seeks.map { it.line() }} errors=${r.errors}")
        assertTrue(r.errors.joinToString(), r.errors.isEmpty())
        assertTrue(r.firstFrameMs >= 0)
        assertTrue(r.ended)
        assertEquals(idx.segments.size - 1, r.transitions)
        assertEquals(idx.segments.size - 1, r.boundaryGapsMs.size)
        assertEquals(4, r.seeks.size)
        assertEquals(0, r.seekTimeouts)
        for (sk in r.seeks) {
            val what = sk.line()
            assertTrue(what, Math.abs(sk.positionUs - sk.keyTimeUs) <= 1000)
            assertTrue(what, sk.positionUs <= sk.targetUs + 1000)
            if (idx.segments.any { it.startUs == sk.targetUs }) assertEquals(what, sk.targetUs, sk.keyTimeUs)
        }
        assertTrue(a1.playerReleased)
        assertEquals(0, a1.live.liveCount)
        assertEquals(a1.live.opens, a1.live.closes)
        assertTrue(Vault.buffersFreed.get() - freed0 >= idx.segments.size)

        // Pause and resume, a seek through the method the bar uses, then a
        // lock during playback.
        val s2 = launchVideo(hex)
        var act2: VideoActivity? = null
        s2.onActivity { act2 = it }
        waitFor(s2, "first frame", 60) { it.firstFrameRendered }
        Thread.sleep(300)
        var p1 = 0L
        s2.onActivity { a ->
            a.togglePlay()
            p1 = a.positionMs()
        }
        Thread.sleep(600)
        s2.onActivity { a ->
            assertFalse(a.isPlaying)
            assertTrue("position ${a.positionMs()} while paused at $p1", Math.abs(a.positionMs() - p1) <= 100)
            a.togglePlay()
        }
        Thread.sleep(800)
        s2.onActivity { a ->
            assertTrue(a.isPlaying)
            assertTrue("position ${a.positionMs()} after $p1", a.positionMs() > p1)
        }
        val seg1 = idx.segments[1]
        s2.onActivity { a -> a.seekToMs(seg1.startUs / 1000 + 300) }
        waitFor(s2, "the seek", 30) { a -> a.report.seeks.isNotEmpty() || a.report.seekTimeouts > 0 }
        s2.onActivity { a ->
            val sk = a.report.seeks.first()
            log("manual seek ${sk.line()}")
            assertEquals(1, sk.segment)
            assertEquals(seg1.startUs, sk.keyTimeUs)
        }
        val freed1 = Vault.buffersFreed.get()
        lock.lock("test")
        waitDestroyed(s2)
        assertTrue(act2!!.playerReleased)
        assertEquals(0, act2!!.live.liveCount)
        assertEquals(act2!!.live.opens, act2!!.live.closes)
        assertTrue(Vault.buffersFreed.get() >= freed1)
        s2.close()
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)

        // A damaged segment: playback stops at it with no frame from it.
        val seg1Id = seg1.id
        flipByte(seg1Id, 128 + 10)
        var act3: VideoActivity? = null
        launchVideo(hex).use { s ->
            s.onActivity { act3 = it }
            waitFor(s, "the segment error", 60) { a -> a.report.errors.isNotEmpty() || a.report.ended }
        }
        val r3 = act3!!.report
        log("damaged segment: errors=${r3.errors} last_frame_segment=${r3.lastFrameSegment} frames=${r3.framesRendered}")
        assertEquals(1, r3.errors.size)
        assertTrue(r3.errors[0], r3.errors[0].startsWith("segment 1:") && r3.errors[0].contains("AUTH_FAILED"))
        assertTrue(r3.lastFrameSegment <= 0)
        assertTrue(act3!!.playerReleased)
        assertEquals(0, act3!!.live.liveCount)
        flipByte(seg1Id, 128 + 10)

        // Delete from the viewer: index and every segment gone.
        var act4: VideoActivity? = null
        val s4 = launchVideo(hex)
        s4.onActivity { act4 = it }
        waitFor(s4, "the index", 30) { a -> a.loadStatus == NativeBridge.OK }
        s4.onActivity { a -> a.runDelete() }
        waitDestroyed(s4)
        val d = act4!!.deleteResult!!
        log("delete: ${d.line()}")
        assertTrue(d.line(), d.ok)
        assertEquals(idx.segments.size, d.segmentStatuses.size)
        assertTrue(objectHexes().isEmpty())
        s4.close()

        // Clip 2: the failure hooks, the damaged index, the interrupted
        // delete, and the sweep.
        ensureUnlocked()
        val out2 = record(6_000L)
        val idx2 = out2.index!!
        val hex2 = NativeBridge.hex(out2.indexId!!)
        val objects2 = idx2.segments.size + 1

        VideoFirstFrame.testHooks.fail = true
        ensureUnlocked()
        ActivityScenario.launch(GalleryActivity::class.java).use { s ->
            waitFor(s, "classification", 60) { a -> a.classified == objects2 }
            waitFor(s, "the thumbnail failure", 60) { a -> a.thumbFailed(hex2) != null }
            s.onActivity { a ->
                assertTrue(a.kindOf(hex2) is VideoItems.Kind.Video)
                assertNull(a.cache.get(hex2))
                assertEquals(1, a.visibleCount)
            }
        }
        VideoFirstFrame.testHooks.fail = false

        GuardedSegmentDataSource.testHooks.failReadSegment = 1
        ensureUnlocked()
        var act5: VideoActivity? = null
        launchVideo(hex2).use { s ->
            s.onActivity { act5 = it }
            waitFor(s, "the read error", 60) { a -> a.report.errors.isNotEmpty() || a.report.ended }
        }
        GuardedSegmentDataSource.testHooks.failReadSegment = -1
        log("read hook: errors=${act5!!.report.errors}")
        assertEquals(1, act5!!.report.errors.size)
        assertTrue(act5!!.playerReleased)
        assertEquals(0, act5!!.live.liveCount)

        // Allocation failure on a segment open: the hook goes on once the
        // clip ended and every segment is closed; a seek reopens segment 0.
        ensureUnlocked()
        var act6: VideoActivity? = null
        launchVideo(hex2).use { s ->
            s.onActivity { act6 = it }
            waitFor(s, "the end of clip 2", 60) { a -> a.report.ended }
            NativeBridge.nativeSetViewerHooks(true, 0)
            s.onActivity { a -> a.seekToMs(0) }
            waitFor(s, "the allocation error", 60) { a -> a.report.errors.isNotEmpty() }
        }
        NativeBridge.nativeSetViewerHooks(false, 0)
        log("alloc hook: errors=${act6!!.report.errors}")
        assertEquals(1, act6!!.report.errors.size)
        assertTrue(act6!!.report.errors[0], act6!!.report.errors[0].contains("OUT_OF_MEMORY"))
        assertTrue(act6!!.playerReleased)
        assertEquals(0, act6!!.live.liveCount)
        ensureUnlocked()
        ActivityScenario.launch(GalleryActivity::class.java).use { s ->
            waitFor(s, "the thumbnail after the hook", 60) { a -> a.cache.get(hex2) != null }
        }

        // A damaged index: damaged tile, viewer error state, the sweep skips.
        flipByte(out2.indexId!!, 128 + 10)
        ensureUnlocked()
        ActivityScenario.launch(GalleryActivity::class.java).use { s ->
            waitFor(s, "classification", 60) { a -> a.classified == objects2 }
            s.onActivity { a ->
                // The first chunk is damaged, so the header is unreadable
                // and the tile cannot say it was a video.
                val k = a.kindOf(hex2)
                assertTrue("kind ${k?.label()}", k is VideoItems.Kind.Damaged && k.reason.contains("AUTH_FAILED"))
                assertEquals(1, a.visibleCount)
            }
        }
        ensureUnlocked()
        launchVideo(hex2).use { s ->
            waitFor(s, "the index error", 30) { a -> a.loadStatus >= 0 }
            s.onActivity { a ->
                assertEquals(NativeBridge.ERR_AUTH_FAILED, a.loadStatus)
                assertNull(a.index)
            }
        }
        relock()
        val sw1 = waitSweep()
        log("sweep with a damaged index: ${sw1.line()}")
        assertNotNull(sw1.skipped)
        assertEquals(objects2, objectHexes().size)
        flipByte(out2.indexId!!, 128 + 10)

        // An interrupted delete (index only): nothing in the gallery, the
        // sweep after the next unlock removes the segments.
        assertEquals(NativeBridge.OK, app.vaultSession.vault!!.deleteObject(out2.indexId!!))
        ensureUnlocked()
        ActivityScenario.launch(GalleryActivity::class.java).use { s ->
            waitFor(s, "classification", 60) { a -> a.classified == objects2 - 1 }
            s.onActivity { a -> assertEquals(0, a.visibleCount) }
        }
        relock()
        val sw2 = waitSweep()
        log("sweep after the interrupted delete: ${sw2.line()}")
        assertNull(sw2.skipped, sw2.skipped)
        assertEquals(idx2.segments.size, sw2.deleted)
        assertEquals(0, sw2.failed)
        assertTrue(objectHexes().isEmpty())

        val after = LeakScan.run(context)
        assertTrue(after.lines().joinToString("\n"), realFindings(after).isEmpty())
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") && !isLockPrefs(it.path) }
        assertTrue("new non-object files: ${fresh.map { it.path }}", fresh.isEmpty())
    }

    // A recording started right after an unlock, with the sweep posted
    // behind the unlock, loses no segment: the gate keeps them apart.
    @Test
    fun recordingRightAfterUnlockLosesNoSegment() {
        relock()
        val out = record(6_000L)
        val sw = waitSweep()
        log("sweep beside the recording: ${sw.line()}")
        assertTrue(sw.line(), sw.skipped == "recording active" || sw.deleted == 0)
        val vault = app.vaultSession.vault!!
        val present = objectHexes()
        for (s in out.index!!.segments) assertTrue("segment ${s.hex} gone", s.hex in present)
        assertTrue(NativeBridge.hex(out.indexId!!) in present)
        assertEquals(NativeBridge.OK, VideoIndex.read(vault, out.indexId!!).status)
        assertEquals(out.index!!.segments.size + 1, present.size)
    }
}
