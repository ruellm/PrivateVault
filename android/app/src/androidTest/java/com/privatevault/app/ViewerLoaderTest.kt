package com.privatevault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

// T2: the viewer pipeline (docs/PHASE3B_DESIGN.md Sections 2, 5, 6, 12) over
// synthetic objects: the orientation rule on the committed EXIF fixtures,
// decoded size bounded by the target, the large, unknown, and damaged tiles,
// clean failures of the allocation and the decode, delete, and a lock during
// a slowed decrypt. Everything is synthetic; nothing is written but objects.
@RunWith(AndroidJUnit4::class)
class ViewerLoaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val dir = File(context.filesDir, "viewer_test")
    private lateinit var vault: Vault
    private val created = 1791849600000L

    @Before
    fun setUp() {
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        vault = Vault.openWithKey(dir, TestKeys.VMK)
        NativeBridge.nativeSetViewerHooks(false, 0)
        PhotoLoader.testHooks.failDecode = false
    }

    @After
    fun tearDown() {
        NativeBridge.nativeSetViewerHooks(false, 0)
        PhotoLoader.testHooks.failDecode = false
        vault.close()
        dir.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray = assets.open("$name.jpg").use { it.readBytes() }

    private fun storeJpeg(bytes: ByteArray, rotation: Int, width: Int = 64, height: Int = 32): ByteArray =
        vault.encryptItemArray(bytes, 0, bytes.size, Vault.ItemMeta(NativeBridge.MEDIA_JPEG, rotation, width, height, created)).id

    private fun isRed(p: Int) = Color.red(p) > 150 && Color.green(p) < 110 && Color.blue(p) < 110
    private fun isGrey(p: Int) = Color.red(p) in 90..170 && Math.abs(Color.red(p) - Color.green(p)) < 30 && Math.abs(Color.red(p) - Color.blue(p)) < 30

    // Every EXIF value with every header rotation: the red square ends up where
    // one turn puts it, never two. The header turn is applied here the way
    // PhotoView draws it.
    @Test
    fun orientationRuleOnFixtures() {
        for (exif in listOf(1, 3, 6, 8)) {
            val bytes = fixture("orient_$exif")
            assertEquals(exif, JpegExif.orientation(ByteBuffer.wrap(bytes)))
            for (rot in listOf(0, 90, 180, 270)) {
                val id = storeJpeg(bytes, rot)
                val r = PhotoLoader.load(vault, id, 200, 200)
                val what = "exif $exif rotation $rot"
                assertTrue("$what: ${r.label()}", r is PhotoLoader.Loaded.Photo)
                r as PhotoLoader.Loaded.Photo
                assertEquals(what, exif, r.exifOrientation)
                assertEquals(what, rot, r.headerRotation)
                assertEquals(what, if (exif == 1) rot else 0, r.turnDegrees)
                val shown = PhotoLoader.turned(r.bitmap, r.turnDegrees)
                val total = if (exif == 1) rot else JpegExif.degrees(exif)
                val quarter = total == 90 || total == 270
                assertEquals(what, if (quarter) 32 else 64, shown.width)
                assertEquals(what, if (quarter) 64 else 32, shown.height)
                val w = shown.width
                val h = shown.height
                val (rx, ry) = when (total) {
                    0 -> 4 to 4
                    90 -> (w - 5) to 4
                    180 -> (w - 5) to (h - 5)
                    else -> 4 to (h - 5)
                }
                val red = shown.getPixel(rx, ry)
                val opposite = shown.getPixel(w - 1 - rx, h - 1 - ry)
                Log.i("PrivateVaultTest", "$what: total $total, ${w}x$h, red at ($rx,$ry)=${Integer.toHexString(red)} opposite=${Integer.toHexString(opposite)}")
                assertTrue("$what: red square not at ($rx,$ry)", isRed(red))
                assertTrue("$what: opposite corner not grey", isGrey(opposite))
                PhotoLoader.dispose(shown)
                assertEquals(NativeBridge.OK, vault.deleteObject(id))
            }
        }
    }

    private fun bigJpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(90, 140, 200))
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        b.recycle()
        return out.toByteArray()
    }

    @Test
    fun decodedSizeIsBoundedByTheTargetAndNeverUpscaled() {
        val id = storeJpeg(bigJpeg(1600, 1200), 0, 1600, 1200)
        val small = PhotoLoader.load(vault, id, 300, 300) as PhotoLoader.Loaded.Photo
        assertTrue("${small.bitmap.width}x${small.bitmap.height}", small.bitmap.width <= 300 && small.bitmap.height <= 300)
        assertEquals(300, small.bitmap.width)
        assertEquals(225, small.bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, small.bitmap.config)
        assertTrue(small.bitmap.isMutable)
        PhotoLoader.dispose(small.bitmap)
        val thumb = PhotoLoader.load(vault, id, PhotoLoader.THUMB_PX, PhotoLoader.THUMB_PX) as PhotoLoader.Loaded.Photo
        assertEquals(256, thumb.bitmap.width)
        PhotoLoader.dispose(thumb.bitmap)
        val full = PhotoLoader.load(vault, id, 5000, 5000) as PhotoLoader.Loaded.Photo
        assertEquals(1600, full.bitmap.width)
        assertEquals(1200, full.bitmap.height)
        PhotoLoader.dispose(full.bitmap)
        assertTrue(full.bitmap.isRecycled)
        // A turned viewer target swaps the bound: rotation 90 with no EXIF tag.
        val turnedId = storeJpeg(bigJpeg(1600, 1200), 90, 1600, 1200)
        val turned = PhotoLoader.load(vault, turnedId, 300, 600) as PhotoLoader.Loaded.Photo
        assertEquals(90, turned.turnDegrees)
        assertTrue("${turned.bitmap.width}x${turned.bitmap.height}", turned.bitmap.width <= 600 && turned.bitmap.height <= 300)
        PhotoLoader.dispose(turned.bitmap)
    }

    @Test
    fun largeUnknownAndDamagedTiles() {
        // Above the cap: never decrypted; the buffer counter does not move.
        val freed = Vault.buffersFreed.get()
        val large = vault.encryptSample(TestKeys.SEED, PhotoLoader.SIZE_CAP + 1)
        assertTrue(PhotoLoader.load(vault, large, 200, 200) is PhotoLoader.Loaded.Large)
        assertEquals(freed, Vault.buffersFreed.get())
        assertEquals(NativeBridge.OK, vault.deleteObject(large))
        // Not an item, empty, and a video segment.
        val plain = PhotoLoader.load(vault, vault.encryptSample(TestKeys.SEED, 5000), 200, 200)
        assertTrue(plain.label(), plain is PhotoLoader.Loaded.Unknown)
        assertEquals("PV_ERR_NOT_ITEM", (plain as PhotoLoader.Loaded.Unknown).reason)
        val empty = PhotoLoader.load(vault, vault.encryptSample(TestKeys.SEED, 0), 200, 200)
        assertTrue(empty.label(), empty is PhotoLoader.Loaded.Unknown)
        val segment = vault.encryptItemArray(ByteArray(1000), 0, 1000, Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_SEGMENT, 0, 1280, 720, created)).id
        val seg = PhotoLoader.load(vault, segment, 200, 200)
        assertTrue(seg.label(), seg is PhotoLoader.Loaded.Unknown)
        assertEquals("video_segment", (seg as PhotoLoader.Loaded.Unknown).reason)
        // Tampered and truncated objects: damaged with the error name.
        val id = storeJpeg(fixture("orient_1"), 0)
        val file = vault.objectPath(id)
        val good = file.readBytes()
        RandomAccessFile(file, "rw").use { f ->
            f.seek(128 + 10)
            val b = f.read()
            f.seek(128 + 10)
            f.write(b xor 1)
        }
        val tampered = PhotoLoader.load(vault, id, 200, 200)
        assertTrue(tampered.label(), tampered is PhotoLoader.Loaded.Damaged)
        assertEquals("PV_ERR_AUTH_FAILED", (tampered as PhotoLoader.Loaded.Damaged).name)
        file.writeBytes(good)
        RandomAccessFile(file, "rw").use { f -> f.setLength(good.size - 5L) }
        val cut = PhotoLoader.load(vault, id, 200, 200)
        assertTrue(cut.label(), cut is PhotoLoader.Loaded.Damaged)
        assertTrue((cut as PhotoLoader.Loaded.Damaged).name.startsWith("PV_ERR_"))
        file.writeBytes(good)
        assertTrue(PhotoLoader.load(vault, id, 200, 200) is PhotoLoader.Loaded.Photo)
        // A JPEG item whose payload is not a JPEG.
        val junk = storeJpeg(ByteArray(5000) { 7 }, 0)
        val undecodable = PhotoLoader.load(vault, junk, 200, 200)
        assertTrue(undecodable.label(), undecodable is PhotoLoader.Loaded.Damaged)
        assertEquals("DecodeException", (undecodable as PhotoLoader.Loaded.Damaged).name)
        // Every buffer handed to Kotlin was given back.
        assertTrue(Vault.buffersFreed.get() > freed)
    }

    @Test
    fun allocationAndDecodeFailuresAreClean() {
        val id = storeJpeg(fixture("orient_6"), 90)
        NativeBridge.nativeSetViewerHooks(true, 0)
        assertTrue(PhotoLoader.load(vault, id, 200, 200) is PhotoLoader.Loaded.NoMemory)
        NativeBridge.nativeSetViewerHooks(false, 0)
        val freed = Vault.buffersFreed.get()
        PhotoLoader.testHooks.failDecode = true
        assertTrue(PhotoLoader.load(vault, id, 200, 200) is PhotoLoader.Loaded.NoMemory)
        assertEquals(freed + 1, Vault.buffersFreed.get())
        PhotoLoader.testHooks.failDecode = false
        val ok = PhotoLoader.load(vault, id, 200, 200)
        assertTrue(ok.label(), ok is PhotoLoader.Loaded.Photo)
        PhotoLoader.dispose((ok as PhotoLoader.Loaded.Photo).bitmap)
        assertEquals(freed + 2, Vault.buffersFreed.get())
    }

    @Test
    fun deleteRemovesTheObjectAndItsPartial() {
        val id = storeJpeg(fixture("orient_3"), 0)
        val file = vault.objectPath(id)
        val partial = File(file.path + ".partial")
        partial.writeBytes(ByteArray(10))
        assertTrue(file.exists())
        assertEquals(NativeBridge.OK, vault.deleteObject(id))
        assertFalse(file.exists())
        assertFalse(partial.exists())
        assertEquals(NativeBridge.ERR_NOT_FOUND, vault.deleteObject(id))
        val gone = PhotoLoader.load(vault, id, 200, 200)
        assertTrue(gone.label(), gone is PhotoLoader.Loaded.Damaged)
        assertEquals("PV_ERR_NOT_FOUND", (gone as PhotoLoader.Loaded.Damaged).name)
        assertEquals(0, vault.listObjects().size)
    }

    // A lock arriving during a decrypt: close tells the core to stop at the
    // next chunk, the load reports cancelled, and close returns soon after.
    @Test
    fun lockDuringSlowedDecryptCancels() {
        val id = storeJpeg(bigJpeg(1600, 1200), 0, 1600, 1200)
        NativeBridge.nativeSetViewerHooks(false, 1500)
        var result: PhotoLoader.Loaded? = null
        val worker = Thread { result = PhotoLoader.load(vault, id, 300, 300) }
        worker.start()
        Thread.sleep(300)
        val t0 = System.nanoTime()
        vault.close()
        val closeMs = (System.nanoTime() - t0) / 1_000_000
        worker.join(10_000)
        Log.i("PrivateVaultTest", "close took $closeMs ms, load result ${result?.label()}")
        assertTrue(result?.label() ?: "none", result is PhotoLoader.Loaded.Cancelled)
        assertTrue("close took $closeMs ms", closeMs < 2500)
        assertFalse(vault.isOpen)
        // A load on the closed vault is cancelled at once.
        assertTrue(PhotoLoader.load(vault, id, 300, 300) is PhotoLoader.Loaded.Cancelled)
    }
}
