package com.privatevault.app

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
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
import java.util.concurrent.Executors

// T2: real captures from the emulator camera, each stored as one item-wrapped
// object through the same CaptureSession path the app uses. Nothing is
// written except the objects; verification decodes the JPEG in memory.
@RunWith(AndroidJUnit4::class)
class CaptureTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = PvApp.of(context)
    private val dir = File(context.filesDir, "capture_test")
    private lateinit var vault: Vault
    private val owner = TestLifecycleOwner()
    private val camera = TestCamera(owner)
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        assumeTrue(context.packageManager.hasSystemFeature("android.hardware.camera.any"))
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        vault = Vault.openWithKey(dir, TestKeys.VMK)
        app.vaultSession.adopt(vault)
        NativeBridge.nativeSetTestHooks(0, 0)
        camera.bind(CameraSelector.LENS_FACING_BACK)
    }

    @After
    fun tearDown() {
        camera.unbind()
        app.vaultSession.close()
        dir.deleteRecursively()
        executor.shutdown()
    }

    @Test
    fun threePhotosBecomeThreeVerifiedItems() {
        val session = app.captureSession
        val results = ArrayList<CaptureSession.Result>()
        for (i in 1..3) {
            assertTrue(session.reserve())
            val shutter = System.nanoTime()
            val c = camera.take(session.executor)
            if (c.error != null) {
                session.release()
                throw AssertionError("capture error ${c.error.imageCaptureError}: ${c.error.message}")
            }
            val proxy = c.proxy!!
            val latch = java.util.concurrent.CountDownLatch(1)
            session.submit(proxy, shutter) { r -> results.add(r); latch.countDown() }
            assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertEquals(3, results.size)
        for (r in results) {
            Log.i("PrivateVaultTest", r.line())
            assertEquals(r.line(), "OK", r.status)
            assertEquals("ok", r.verify)
            assertTrue(r.jpegLen > 0)
        }
        // Three objects, each verifies, header parses with the recorded fields,
        // and the decrypted bytes decode as a JPEG of the recorded dimensions.
        val ids = vault.listObjectIds()
        assertEquals(3, ids.size)
        assertFalse(File(dir, "objects").listFiles()!!.any { it.name.endsWith(".partial") })
        for (id in ids) {
            val (rc, v) = vault.decryptVerifyItem(id)
            assertEquals(NativeBridge.OK, rc)
            val item = v!!.item
            assertNotNull(item)
            assertEquals(NativeBridge.MEDIA_JPEG, item!!.mediaType)
            assertTrue(item.rotation in listOf(0, 90, 180, 270))
            assertEquals(48, item.headerLen)
            assertEquals(48 + item.payloadLen, v.plaintextLen)
            val r = results.first { it.idHex == NativeBridge.hex(id) }
            assertEquals(r.jpegLen.toLong(), item.payloadLen)
            assertEquals(r.width, item.width)
            assertEquals(r.height, item.height)
            assertEquals(r.rotation, item.rotation)
            // Hash equality: BLAKE2b of the direct buffer before encryption
            // (debug path) against the decrypt-side payload hash.
            if (r.bufferPath == "DIRECT") {
                assertEquals(r.payloadHashHex, NativeBridge.hex(v.payloadHash!!))
            }
            val status = IntArray(1)
            val plain = NativeBridge.nativeDecryptToArray(vault.rawHandle, id, 64 shl 20, status)
            assertEquals(NativeBridge.OK, status[0])
            assertNotNull(plain)
            assertEquals(v.plaintextLen, plain!!.size.toLong())
            // Header bytes, then the JPEG start marker.
            assertEquals("PVITEM01", String(plain, 0, 8, Charsets.US_ASCII))
            assertEquals(0xFF, plain[48].toInt() and 0xff)
            assertEquals(0xD8, plain[49].toInt() and 0xff)
            val bmp = BitmapFactory.decodeByteArray(plain, 48, plain.size - 48)
            assertNotNull("JPEG did not decode", bmp)
            assertEquals(item.width, bmp!!.width)
            assertEquals(item.height, bmp.height)
            bmp.recycle()
            plain.fill(0)
        }
        Log.i("PrivateVaultTest", "buffer paths: ${results.map { it.bufferPath }}")
    }

    @Test
    fun planeBufferDirectnessIsRecorded() {
        val c = camera.take(executor)
        val proxy = c.proxy ?: throw AssertionError("capture error: ${c.error?.message}")
        try {
            assertTrue(TestCamera.isJpeg(proxy))
            val buffer = proxy.planes[0].buffer
            Log.i(
                "PrivateVaultTest",
                "plane0 isDirect=${buffer.isDirect} hasArray=${buffer.hasArray()} remaining=${buffer.remaining()} " +
                    "${proxy.width}x${proxy.height} rotation=${proxy.imageInfo.rotationDegrees} class=${buffer.javaClass.name}"
            )
        } finally {
            proxy.close()
        }
    }

    @Test
    fun encryptItemFromHeapArrayPath() {
        // The heap-array path the app takes when a plane buffer is not direct,
        // exercised with synthetic bytes so it is covered on every device.
        val payload = ByteArray(100_000) { (it * 7).toByte() }
        val meta = Vault.ItemMeta(NativeBridge.MEDIA_JPEG, 270, 1280, 960, 1791849600000L)
        val stored = vault.encryptItemArray(payload, 0, payload.size, meta)
        assertEquals(Vault.BufferPath.HEAP_ARRAY, stored.path)
        assertEquals(48L + payload.size, stored.plaintextLen)
        val (rc, v) = vault.decryptVerifyItem(stored.id)
        assertEquals(NativeBridge.OK, rc)
        assertEquals(270, v!!.item!!.rotation)
        assertEquals(payload.size.toLong(), v.item!!.payloadLen)
        // Direct buffer with the same bytes gives the same payload hash.
        val direct = java.nio.ByteBuffer.allocateDirect(payload.size)
        direct.put(payload)
        direct.flip()
        val stored2 = vault.encryptItem(direct, meta)
        assertEquals(Vault.BufferPath.DIRECT, stored2.path)
        val (rc2, v2) = vault.decryptVerifyItem(stored2.id)
        assertEquals(NativeBridge.OK, rc2)
        assertTrue(v.payloadHash!!.contentEquals(v2!!.payloadHash!!))
        // Phase 1 synthetic objects are unknown items.
        val plainId = vault.encryptSample(TestKeys.SEED, 5000L)
        val (rc3, v3) = vault.decryptVerifyItem(plainId)
        assertEquals(NativeBridge.OK, rc3)
        assertEquals(NativeBridge.ERR_NOT_ITEM, v3!!.itemStatus)
        assertEquals(null, v3.item)
    }
}
