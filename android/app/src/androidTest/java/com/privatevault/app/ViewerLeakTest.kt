package com.privatevault.app

import android.Manifest
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// T2: a viewer session over real captures leaves nothing behind. Two
// captures, a scan, every object loaded at thumbnail and screen size through
// the viewer pipeline, a second scan: no new files, no markers, no owned
// MediaStore rows.
@RunWith(AndroidJUnit4::class)
class ViewerLeakTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)

    @Test
    fun viewerSessionLeavesNothing() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        val dir = Vault.dirFor(context.filesDir)
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        val vault = Vault.openWithKey(dir, TestKeys.VMK)
        app.vaultSession.adopt(vault)
        // A lock in an earlier test leaves the capture session closed until the
        // next unlock; this test adopts its vault directly, so it reopens it.
        app.captureSession.resume()
        val owner = TestLifecycleOwner()
        val camera = TestCamera(owner)
        camera.bind(CameraSelector.LENS_FACING_BACK)
        try {
            for (i in 1..2) {
                assertTrue(app.captureSession.reserve())
                val c = camera.take(app.captureSession.executor)
                val proxy = c.proxy ?: throw AssertionError("capture error: ${c.error?.message}")
                val latch = CountDownLatch(1)
                var ok = false
                app.captureSession.submit(proxy, System.nanoTime()) { r -> ok = r.ok; latch.countDown() }
                assertTrue(latch.await(60, TimeUnit.SECONDS))
                assertTrue(ok)
            }
        } finally {
            camera.unbind()
        }
        val before = LeakScan.run(context)
        assertEquals(before.lines().joinToString("\n"), 0, before.findingCount)
        val lines = ArrayList<String>()
        ViewerActions.viewAll(app.vaultSession, { lines.add(it); Log.i("PrivateVaultTest", it) })
        assertTrue(lines.last(), lines.last().startsWith("view_all: 2 photos"))
        val after = LeakScan.run(context)
        assertEquals(after.lines().joinToString("\n"), 0, after.findingCount)
        val fresh = LeakScan.newEntries(before, after)
        assertTrue("new files after the viewer session: ${fresh.map { it.path }}", fresh.isEmpty())
        app.vaultSession.close()
        dir.deleteRecursively()
    }
}
