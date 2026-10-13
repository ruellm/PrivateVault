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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// T2: the leak scan before and after captures. After captures the only new
// files are objects, and no file in any app-visible location starts with or
// contains a JPEG marker in its first 64 KiB.
@RunWith(AndroidJUnit4::class)
class LeakScanTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)

    @Test
    fun scanFindsNothingBeforeAndAfterCaptures() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        // Start from a clean vault directory in filesDir, as the app uses.
        val dir = Vault.dirFor(context.filesDir)
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        val vault = Vault.openWithKey(dir, TestKeys.VMK)
        app.vaultSession.adopt(vault)
        val before = LeakScan.run(context)
        for (l in before.lines()) Log.i("PrivateVaultTest", "before: $l")
        assertEquals(before.lines().joinToString("\n"), 0, before.findingCount)

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
        val after = LeakScan.run(context)
        for (l in after.lines()) Log.i("PrivateVaultTest", "after: $l")
        assertEquals(after.lines().joinToString("\n"), 0, after.findingCount)
        val fresh = LeakScan.newEntries(before, after)
        for (e in fresh) Log.i("PrivateVaultTest", "new file: ${e.path} ${e.size} bytes")
        val objectsDir = File(dir, "objects").path
        val unexpected = fresh.filter { !(it.path.startsWith(objectsDir) && it.path.endsWith(".pvo")) }
        assertTrue("unexpected new files: $unexpected", unexpected.isEmpty())
        assertEquals(2, fresh.size)
        app.vaultSession.close()
        dir.deleteRecursively()
    }

    // The scan must not create the directories it looks at (D-015).
    @Test
    fun scanCreatesNoExternalDirectories() {
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val ext = android.os.Environment.getExternalStorageDirectory()
        val dirs = listOf("Android/data/$pkg", "Android/media/$pkg", "Android/obb/$pkg").map { File(ext, it) }
        val before = dirs.map { it.exists() }
        LeakScan.run(context)
        val after = dirs.map { it.exists() }
        assertEquals("directories created by the scan: ${dirs.zip(before.zip(after))}", before, after)
    }

    @Test
    fun scanDetectsPlantedMarkers() {
        // The scanner itself: a planted JPEG marker and an ftyp box are found,
        // then removed.
        val planted = File(context.cacheDir, "planted.bin")
        planted.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100))
        val planted2 = File(context.cacheDir, "planted.mp4")
        planted2.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray(Charsets.US_ASCII) + ByteArray(100))
        try {
            val r = LeakScan.run(context)
            assertTrue(r.findings.any { it.kind == "jpeg_soi_at_start" && it.path == planted.path })
            assertTrue(r.findings.any { it.kind == "mp4_ftyp_at_4" && it.path == planted2.path })
        } finally {
            planted.delete()
            planted2.delete()
        }
        assertTrue(LeakScan.run(context).findings.none { it.path == planted.path || it.path == planted2.path })
    }
}
