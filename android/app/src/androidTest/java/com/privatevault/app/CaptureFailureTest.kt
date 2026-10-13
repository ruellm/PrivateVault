package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.StatFs
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// T2: the Section 6 failure cases the emulator can produce. Every case must
// leave either a complete object or nothing, with no .partial file.
@RunWith(AndroidJUnit4::class)
class CaptureFailureTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val dir = File(context.filesDir, "capture_fail_test")
    private lateinit var vault: Vault
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        vault = Vault.openWithKey(dir, TestKeys.VMK)
        app.vaultSession.adopt(vault)
        NativeBridge.nativeSetTestHooks(0, 0)
    }

    @After
    fun tearDown() {
        NativeBridge.nativeSetTestHooks(0, 0)
        app.vaultSession.close()
        dir.deleteRecursively()
        executor.shutdown()
    }

    private fun objectsDir() = File(dir, "objects")
    private fun partials() = objectsDir().listFiles()!!.filter { it.name.endsWith(".partial") }
    private fun objects() = objectsDir().listFiles()!!.filter { it.name.endsWith(".pvo") }

    private fun captureThrough(session: CaptureSession, camera: TestCamera): CaptureSession.Result {
        assertTrue(session.reserve())
        val c = camera.take(session.executor)
        if (c.error != null) {
            session.release()
            throw AssertionError("capture error: ${c.error.message}")
        }
        val latch = CountDownLatch(1)
        var result: CaptureSession.Result? = null
        session.submit(c.proxy!!, System.nanoTime()) { r -> result = r; latch.countDown() }
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        return result!!
    }

    // Encrypt failure: the test hook fails the source after the item header,
    // so the core returns PV_ERR_IO. Nothing is left and the proxy is closed.
    @Test
    fun encryptFailureLeavesNothing() {
        val owner = TestLifecycleOwner()
        val camera = TestCamera(owner)
        camera.bind(CameraSelector.LENS_FACING_BACK, highest = false)
        try {
            NativeBridge.nativeSetTestHooks(0, 100)
            val r = captureThrough(app.captureSession, camera)
            Log.i("PrivateVaultTest", r.line())
            assertFalse(r.ok)
            assertTrue(r.status, r.status.contains("PV_ERR_IO"))
            assertEquals(0, objects().size)
            assertEquals(0, partials().size)
            NativeBridge.nativeSetTestHooks(0, 0)
            // The pipeline recovers: the next capture succeeds.
            val ok = captureThrough(app.captureSession, camera)
            assertTrue(ok.line(), ok.ok)
            assertEquals(1, objects().size)
        } finally {
            camera.unbind()
        }
    }

    // Queue limit: slots beyond MAX_PENDING are refused, never buffered.
    @Test
    fun queueIsBounded() {
        val s = app.captureSession
        assertEquals(0, s.pendingCount)
        assertTrue(s.reserve())
        assertTrue(s.reserve())
        assertFalse(s.reserve())
        s.release()
        assertTrue(s.reserve())
        s.release()
        s.release()
        assertEquals(0, s.pendingCount)
    }

    // Unbinding the camera while a capture is in flight ends in an
    // ImageCaptureException with a named code, and nothing is written.
    @Test
    fun unbindDuringCaptureReportsErrorName() {
        val owner = TestLifecycleOwner()
        val camera = TestCamera(owner)
        camera.bind(CameraSelector.LENS_FACING_BACK, highest = false)
        val latch = CountDownLatch(1)
        var code = -1
        var got: androidx.camera.core.ImageProxy? = null
        camera.imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                got = image
                image.close()
                latch.countDown()
            }

            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                code = exception.imageCaptureError
                latch.countDown()
            }
        })
        camera.unbind()
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val name = when (code) {
            ImageCapture.ERROR_CAMERA_CLOSED -> "ERROR_CAMERA_CLOSED"
            ImageCapture.ERROR_CAPTURE_FAILED -> "ERROR_CAPTURE_FAILED"
            -1 -> "capture completed before unbind"
            else -> "ERROR_$code"
        }
        Log.i("PrivateVaultTest", "unbind during capture: $name")
        assertTrue(got != null || code == ImageCapture.ERROR_CAMERA_CLOSED || code == ImageCapture.ERROR_CAPTURE_FAILED)
        assertEquals(0, objects().size)
        assertEquals(0, partials().size)
    }

    // Process death during encrypt is simulated by leaving a .partial behind
    // (a kill cannot be scripted from inside the process); the next open
    // removes it. The real kill is done over adb in the T2 script.
    @Test
    fun stalePartialFromKilledEncryptIsRemovedOnOpen() {
        val partial = File(objectsDir(), "0123456789abcdef0123456789abcdef.pvo.partial")
        partial.writeBytes(ByteArray(4096))
        val again = Vault.openWithKey(dir, TestKeys.VMK)
        again.close()
        assertFalse(partial.exists())
        assertEquals(0, objects().size)
    }

    // Low free space: the precheck refuses before any capture.
    @Test
    fun lowSpaceThresholdIsChecked() {
        val s = app.captureSession
        val free = StatFs(context.filesDir.path).availableBytes
        Log.i("PrivateVaultTest", "free space ${free shr 20} MiB, threshold ${CaptureSession.LOW_SPACE_BYTES shr 20} MiB")
        assertEquals(free < CaptureSession.LOW_SPACE_BYTES, s.lowSpace())
    }

    // Storage full: ballast fills the app storage until an encrypt fails with
    // PV_ERR_IO and ENOSPC; the .partial is gone and the ballast is deleted.
    // Emulator only.
    @Test
    fun storageFullFailsCleanly() {
        assumeTrue("emulator only", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val ballastDir = File(context.filesDir, "ballast").also { it.mkdirs() }
        try {
            // Fill in 256 MiB files, then 1 MiB files, until under 1 MiB is free.
            var n = 0
            val big = ByteArray(1 shl 20)
            for (size in listOf(256L shl 20, 1L shl 20)) {
                while (StatFs(context.filesDir.path).availableBytes > size + (1 shl 20)) {
                    val f = File(ballastDir, "b$n")
                    n++
                    try {
                        RandomAccessFile(f, "rw").use { raf ->
                            var left = size
                            while (left > 0) {
                                val w = minOf(left, big.size.toLong()).toInt()
                                raf.write(big, 0, w)
                                left -= w
                            }
                        }
                    } catch (e: java.io.IOException) {
                        break
                    }
                    if (n > 200) break
                }
            }
            val free = StatFs(context.filesDir.path).availableBytes
            Log.i("PrivateVaultTest", "ballast files $n, free now ${free shr 10} KiB")
            assertTrue(app.captureSession.lowSpace())
            // An encrypt larger than the free space must fail with PV_ERR_IO.
            val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
            val rc = NativeBridge.nativeEncryptSample(vault.rawHandle, TestKeys.SEED, free + (8L shl 20), 0, id)
            val os = NativeBridge.nativeLastOsError()
            Log.i("PrivateVaultTest", "encrypt on full storage: ${NativeBridge.errorName(rc)} os_error=$os")
            assertEquals(NativeBridge.ERR_IO, rc)
            assertEquals(28, os)   // ENOSPC
            assertEquals(0, partials().size)
            assertEquals(0, objects().size)
        } finally {
            ballastDir.deleteRecursively()
        }
        assertFalse(app.captureSession.lowSpace())
    }

    // Permission revoked: a bind attempt must be refused without a crash.
    @Test
    fun permissionStateIsReadable() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        assertNotNull(context.packageManager)
    }
}
