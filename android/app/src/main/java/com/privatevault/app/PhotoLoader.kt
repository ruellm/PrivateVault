package com.privatevault.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

// The viewer pipeline for one object (docs/PHASE3B_DESIGN.md Section 2):
// plaintext length, size cap, decrypt into guarded memory, item header, EXIF
// orientation, decode at the target size, free the buffer. Every bitmap this
// object makes is software, mutable, and meant to be erased through dispose.
object PhotoLoader {

    const val TAG = "PrivateVault"
    const val SIZE_CAP = 32L shl 20
    const val THUMB_PX = 256

    // One thread for every decrypt and decode of the gallery and the viewer.
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-view") }

    // Bitmaps erased and recycled so far (tests read it).
    val erased = AtomicInteger(0)

    class TestHooks {
        // Throws OutOfMemoryError in place of the decode.
        @Volatile var failDecode = false
    }

    val testHooks = TestHooks()

    sealed class Loaded {
        // turnDegrees is the extra clockwise turn the viewer applies (Section 5.2).
        class Photo(val bitmap: Bitmap, val turnDegrees: Int, val exifOrientation: Int, val headerRotation: Int) : Loaded()
        object Large : Loaded()
        class Unknown(val reason: String) : Loaded()
        class Damaged(val name: String) : Loaded()
        object NoMemory : Loaded()
        object Cancelled : Loaded()

        // Tile text; carries no content.
        fun label(): String = when (this) {
            is Photo -> "photo"
            Large -> "large item"
            is Unknown -> "unknown item"
            is Damaged -> "damaged: $name"
            NoMemory -> "out of memory"
            Cancelled -> "cancelled"
        }
    }

    fun load(vault: Vault, id: ByteArray, targetW: Int, targetH: Int): Loaded {
        val size = try {
            vault.plaintextSize(id)
        } catch (e: Vault.CoreException) {
            return if (!vault.isOpen) Loaded.Cancelled else Loaded.Damaged(NativeBridge.nativeErrorName(e.code))
        }
        if (size > SIZE_CAP) return Loaded.Large
        if (size == 0L) return Loaded.Unknown("empty")
        val load = vault.viewerLoad(id, SIZE_CAP)
        when (load.status) {
            NativeBridge.OK -> {}
            NativeBridge.ERR_CANCELLED -> return Loaded.Cancelled
            NativeBridge.ERR_OUT_OF_MEMORY -> return Loaded.NoMemory
            else -> return Loaded.Damaged(NativeBridge.nativeErrorName(load.status))
        }
        load.use { l ->
            if (l.itemStatus != NativeBridge.OK) return Loaded.Unknown(NativeBridge.nativeErrorName(l.itemStatus))
            val item = l.item ?: return Loaded.Unknown("no header")
            if (item.mediaType != NativeBridge.MEDIA_JPEG) return Loaded.Unknown(item.typeName())
            val payload = l.payload ?: return Loaded.Unknown("no buffer")
            val exif = JpegExif.orientation(payload)
            // The decoder applies a tag other than 1 itself; the header turn
            // is used only when there is no tag to apply (Section 5.2).
            val turn = if (exif >= 2) 0 else item.rotation
            if (BuildConfig.DEBUG && exif >= 2 && JpegExif.degrees(exif) != item.rotation) {
                Log.i(TAG, "orientation ${NativeBridge.hex(id)}: exif=$exif header=${item.rotation} disagree, exif applied")
            }
            val bitmap = try {
                if (testHooks.failDecode) throw OutOfMemoryError("test hook")
                decode(payload, max(1, targetW), max(1, targetH), turn)
            } catch (e: ImageDecoder.DecodeException) {
                return Loaded.Damaged("DecodeException")
            } catch (e: OutOfMemoryError) {
                return Loaded.NoMemory
            } catch (e: IOException) {
                return Loaded.Damaged(e.javaClass.simpleName)
            }
            return Loaded.Photo(bitmap, turn, exif, item.rotation)
        }
    }

    // Decodes at the largest size that fits the target without upscaling;
    // the target is swapped when the viewer will turn the result by a quarter.
    private fun decode(payload: ByteBuffer, targetW: Int, targetH: Int, turn: Int): Bitmap {
        val src = ImageDecoder.createSource(payload)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val boundW = if (turn == 90 || turn == 270) targetH else targetW
            val boundH = if (turn == 90 || turn == 270) targetW else targetH
            val scale = minOf(boundW.toDouble() / w, boundH.toDouble() / h, 1.0)
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }

    // Overwrites the pixels, then frees the bitmap.
    fun dispose(b: Bitmap?) {
        if (b == null || b.isRecycled) return
        if (b.isMutable) b.eraseColor(0)
        b.recycle()
        erased.incrementAndGet()
    }

    // A turned copy for thumbnails; the source is erased and freed.
    fun turned(b: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false)
        if (out !== b) dispose(b)
        return out
    }
}
