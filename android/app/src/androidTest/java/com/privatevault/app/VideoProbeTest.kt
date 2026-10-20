package com.privatevault.app

import android.Manifest
import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

// T2: the Stage 3C prototype end to end on the emulator camera
// (docs/PHASE3C_DESIGN.md Section 8). One recording at the approved settings
// writes index v1 whose key counts agree with the encoder and the container,
// every segment starts on a key frame, and the index times equal the
// encoder's key-frame times. The playback probe then renders the clip over
// guarded buffers, crosses every boundary, seeks to ten positions, and is
// released by a lock, once during playback and once during a slowed
// decrypt. The leak scan is clean before and after. Runs on the app vault
// through the lock controller with the throwaway test PIN; the vault is
// erased afterwards.
@RunWith(AndroidJUnit4::class)
class VideoProbeTest {
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
        NativeBridge.nativeSetViewerHooks(false, 0)
    }

    @After
    fun tearDown() {
        NativeBridge.nativeSetViewerHooks(false, 0)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        lock.testHooks.kdfOverride = null
    }

    private fun log(line: String) = Log.i("PrivateVaultTest", line)

    private fun isLockPrefs(path: String): Boolean = path.contains("/shared_prefs/privatevault_") && path.endsWith(".xml")

    // Findings that are not a chance three- or four-byte match inside the
    // ciphertext of an object (the scan reports those with their offset so
    // they can be judged; they are logged here and not counted).
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

    private fun launch(indexHex: String): ActivityScenario<VideoProbeActivity> {
        val intent = Intent(context, VideoProbeActivity::class.java)
            .putExtra(VideoProbeActivity.EXTRA_ARG, indexHex)
            .putExtra(VideoProbeActivity.EXTRA_AUTO, true)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun recordingIndexAndPlayback() {
        val before = LeakScan.run(context)
        assertTrue(before.lines().joinToString("\n"), realFindings(before).isEmpty())

        // Recording at the approved settings.
        val owner = TestLifecycleOwner()
        instrumentation.runOnMainSync { owner.start(); owner.resume() }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val outcome = try {
            V3Probe(context, owner, provider, CameraSelector.LENS_FACING_BACK, app.vaultSession, ::log, clipMs = 10_000L).run()
        } finally {
            instrumentation.runOnMainSync { provider.unbindAll(); owner.destroy() }
        }
        assertEquals("clip end", outcome.stopReason)
        assertTrue("segments: ${outcome.segments.size}", outcome.segments.size >= 3)
        assertNotNull("no index object", outcome.indexId)
        val idx = outcome.index!!
        assertEquals(outcome.segments.size, idx.segments.size)
        assertEquals(outcome.segments.size, outcome.counts.size)
        assertEquals(idx.segments.last().endUs, idx.header.totalDurationUs)
        // The index holds the measured camera rate, or the delivered rate of
        // the encoder output when the meter got no frames (seen on the Xiaomi).
        if (outcome.frameRateMeasured) assertEquals(outcome.frameRate * 1000, idx.header.frameRateMillihz)
        assertTrue("index rate ${idx.header.frameRateMillihz}", idx.header.frameRateMillihz in 5_000..60_000)
        assertEquals(NativeBridge.VIDEO_CODEC_H264, idx.header.videoCodec)
        for ((i, d) in outcome.segments.withIndex()) {
            val c = outcome.counts[i]
            val s = idx.segments[i]
            val what = "segment $i"
            // Every segment starts on a key frame: the encoder flagged its
            // first buffer, and the container marks its first sample sync.
            assertTrue(what, d.firstSampleKey)
            assertEquals(what, true, c.containerFirstSync)
            assertEquals(what, 0, s.keyOffsetsUs[0])
            // Encoder, container, media3, and index key counts agree.
            assertEquals(what, d.keyPtsUs.size, c.encoderKeys)
            assertEquals(what, c.encoderKeys, c.containerSyncs)
            assertEquals(what, c.encoderKeys, c.media3Syncs)
            assertEquals(what, c.encoderKeys, c.indexKeys)
            assertTrue("$what framework ${c.frameworkSyncs}", c.frameworkSyncs >= 1)
            // Index times are the encoder's key-frame times.
            assertEquals(what, d.startUs, s.startUs)
            assertArrayEquals(what, d.keyOffsetsUs(), s.keyOffsetsUs)
            for ((k, off) in s.keyOffsetsUs.withIndex()) assertEquals(what, d.keyPtsUs[k], s.startUs + off)
            assertArrayEquals(what, d.id, s.id)
            assertEquals(what, d.bytes.toLong(), s.byteLen)
            // Three GOPs per segment except the last.
            if (i < outcome.segments.size - 1) assertEquals(what, V3Probe.KEYS_PER_SEGMENT, d.keyPtsUs.size)
            if (i > 0) assertTrue(what, outcome.segments[i - 1].startUs + outcome.segments[i - 1].durationUs <= s.startUs + 1_000_000L / outcome.frameRate)
        }
        assertTrue(outcome.leakReport.lines().joinToString("\n"), realFindings(outcome.leakReport).isEmpty())
        // The lock controller's preference files (alias, attempt record) are
        // written by the setup and the unlocks of this test; they hold no media.
        val newFiles = outcome.newFiles.filter { !isLockPrefs(it) }
        assertTrue("new files: $newFiles", newFiles.isEmpty())
        val vault = app.vaultSession.vault!!
        val back = VideoIndex.read(vault, outcome.indexId!!)
        assertEquals(NativeBridge.OK, back.status)
        assertArrayEquals(idx.encode(), back.index!!.encode())
        val hex = NativeBridge.hex(outcome.indexId!!)

        // Playback through to the end, then ten seeks.
        val freed0 = Vault.buffersFreed.get()
        var act: VideoProbeActivity? = null
        launch(hex).use { scenario ->
            scenario.onActivity { a ->
                act = a
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(a.surfaceSecure)
            }
            waitFor(scenario, "playback and seeks", 180) { a -> a.done }
        }
        val a = act!!
        val r = a.report
        log("playback report: first_frame_ms=${r.firstFrameMs} transitions=${r.transitions} gaps=${r.boundaryGapsMs} seeks=${r.seeks.map { it.line() }} errors=${r.errors}")
        assertTrue(r.errors.joinToString(), r.errors.isEmpty())
        assertTrue(r.firstFrameMs >= 0)
        assertTrue(r.endedOnce)
        assertEquals(idx.segments.size - 1, r.transitions)
        assertEquals(idx.segments.size - 1, r.boundaryGapsMs.size)
        assertEquals(10, r.seeks.size)
        assertEquals(0, r.seekTimeouts)
        // The container's key sample times follow the index offsets, apart
        // from the muxer's rule for the last sample of a fragment (it gets the
        // previous sample's duration, so an uneven frame gap right before a
        // key frame shifts the container times after it by the difference;
        // docs/VIDEO_PLAYBACK_PROBE.md). The largest difference is logged.
        var maxDriftUs = 0L
        for ((i, c) in outcome.counts.withIndex()) {
            val offsets = idx.segments[i].keyOffsetsUs
            assertEquals("segment $i", offsets.size, c.containerSyncTimesUs.size)
            for ((k, t) in c.containerSyncTimesUs.withIndex()) {
                val drift = Math.abs(t - offsets[k])
                maxDriftUs = maxOf(maxDriftUs, drift)
                assertTrue("segment $i key $k: container $t index ${offsets[k]}", drift <= 300_000)
            }
        }
        log("container key times vs index offsets: largest difference ${maxDriftUs / 1000} ms")
        val frameUs = 1_000_000L / outcome.frameRate
        val gopUs = V3Probe.GOP_US + 3 * 1_000_000L / outcome.frameRate
        for (s in r.seeks) {
            val what = s.line()
            // The player position after the seek is the key time the rule
            // chose, within one GOP before the target, and the first frame
            // released is that key frame, within the container drift and a
            // frame period (docs/VIDEO_PLAYBACK_PROBE.md).
            assertTrue(what, Math.abs(s.positionUs - s.keyTimeUs) <= 1000)
            assertTrue(what, s.frameUs >= 0)
            assertTrue(what, Math.abs(s.frameUs - s.keyTimeUs) <= maxDriftUs + 2 * frameUs)
            assertTrue(what, s.positionUs <= s.targetUs + 1000)
            assertTrue(what, s.targetUs - s.positionUs <= gopUs)
            if (idx.segments.any { it.startUs == s.targetUs }) assertEquals(what, s.targetUs, s.keyTimeUs)
        }
        assertEquals(0, a.live.liveCount)
        assertEquals(a.live.opens, a.live.closes)
        assertTrue(a.live.opens >= idx.segments.size)
        assertTrue(Vault.buffersFreed.get() - freed0 >= idx.segments.size)
        assertTrue(a.playerReleased)

        // A lock during playback releases the player and frees the buffers.
        val freed1 = Vault.buffersFreed.get()
        val scenario2 = launch(hex)
        var act2: VideoProbeActivity? = null
        scenario2.onActivity { act2 = it }
        waitFor(scenario2, "first frame", 60) { it.firstFrameRendered }
        lock.lock("test")
        waitDestroyed(scenario2)
        assertTrue(act2!!.playerReleased)
        assertEquals(0, act2!!.live.liveCount)
        assertEquals(act2!!.live.opens, act2!!.live.closes)
        assertTrue(Vault.buffersFreed.get() > freed1)
        scenario2.close()
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)

        // A lock during a slowed segment decrypt cancels it.
        NativeBridge.nativeSetViewerHooks(false, 1500)
        val scenario3 = launch(hex)
        var act3: VideoProbeActivity? = null
        scenario3.onActivity { act3 = it }
        waitFor(scenario3, "a segment open in flight", 60) { it.live.opening > 0 }
        val t0 = System.nanoTime()
        lock.lock("test")
        val lockMs = (System.nanoTime() - t0) / 1_000_000
        waitDestroyed(scenario3)
        NativeBridge.nativeSetViewerHooks(false, 0)
        log("lock during decrypt took $lockMs ms, open status ${NativeBridge.errorName(act3!!.live.lastOpenStatus)}")
        assertTrue(act3!!.playerReleased)
        assertEquals(NativeBridge.ERR_CANCELLED, act3!!.live.lastOpenStatus)
        assertEquals(0, act3!!.live.liveCount)
        assertTrue("lock took $lockMs ms", lockMs < 6000)
        scenario3.close()
        assertTrue(lock.unlock(pin) is LockController.Result.Ok)

        val after = LeakScan.run(context)
        assertTrue(after.lines().joinToString("\n"), realFindings(after).isEmpty())
        val fresh = LeakScan.newEntries(before, after).filter { !it.path.endsWith(".pvo") && !isLockPrefs(it.path) }
        assertTrue("new non-object files: ${fresh.map { it.path }}", fresh.isEmpty())
        assertFalse(app.vaultSession.vault!!.listObjects().isEmpty())
    }
}
