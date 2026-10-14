package com.privatevault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
//noinspection ExifInterface
import android.media.ExifInterface

// Debug-only measurements for the Stage 3B viewer design (brief Sections 4.3
// and 4.5, Milestone A). orient_probe: for every JPEG object, the item header
// orientation fields, the EXIF orientation tag inside the JPEG, and the
// decoded dimensions from ImageDecoder and BitmapFactory over one buffer in
// guarded native memory. alloc_probe: sodium_malloc at viewer sizes and the
// memory lock limit. Lines carry object IDs, sizes, times, and tag values only.
object ViewerProbes {

    private const val SAMPLE = 4

    fun allocProbe(say: (String) -> Unit) {
        for (mib in listOf(5L, 8L, 16L, 32L, 64L)) {
            val out = LongArray(6)
            val rc = NativeBridge.nativeProbeSecureAlloc(mib shl 20, out)
            say(
                "alloc_probe ${mib}MiB: ${NativeBridge.errorName(rc)} sodium_malloc=${if (out[0] == 1L) "ok" else "FAILED"} " +
                    "mlock_errno=${out[1]} rlimit_memlock=${out[2]}/${out[3]} alloc_ms=${out[4]} free_ms=${out[5]}"
            )
        }
    }

    fun orientProbe(vaults: VaultSession, say: (String) -> Unit) {
        val v = vaults.vault ?: throw IllegalStateException("vault not open")
        var photos = 0
        var skipped = 0
        for (id in v.listObjectIds()) {
            if (!v.isOpen) {
                say("orient_probe stopped: vault locked")
                break
            }
            val hex = NativeBridge.hex(id)
            val (rc, r) = v.decryptVerifyItem(id)
            val item = r?.item
            if (rc != NativeBridge.OK || r == null || item == null || item.mediaType != NativeBridge.MEDIA_JPEG) {
                skipped++
                continue
            }
            val status = IntArray(1)
            val t0 = System.nanoTime()
            val whole = NativeBridge.nativeProbeDecryptDirect(v.rawHandle, id, r.plaintextLen, status)
            val decryptMs = (System.nanoTime() - t0) / 1_000_000
            if (whole == null) {
                say("orient_probe $hex: decrypt to direct buffer ${NativeBridge.errorName(status[0])}")
                continue
            }
            try {
                photos++
                whole.position(item.headerLen)
                val payload = whole.slice()
                say("orient_probe $hex: header rotation=${item.rotation} ${item.width}x${item.height} payload=${item.payloadLen} direct=${payload.isDirect} decrypt_ms=$decryptMs")
                val exif = try {
                    val e = ExifInterface(ByteBufferInputStream(payload.duplicate()))
                    "orientation=${e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)} " +
                        "image_width=${e.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)} image_length=${e.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)} " +
                        "pixel_x=${e.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION)} pixel_y=${e.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION)} " +
                        "make=${e.getAttribute(ExifInterface.TAG_MAKE)} model=${e.getAttribute(ExifInterface.TAG_MODEL)} thumbnail=${e.hasThumbnail()}"
                } catch (e: Exception) {
                    "failed ${e.javaClass.simpleName}: ${e.message}"
                }
                say("orient_probe $hex: exif $exif")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ByteBufferInputStream(payload.duplicate()), null, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = SAMPLE
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val t1 = System.nanoTime()
                val bf = BitmapFactory.decodeStream(ByteBufferInputStream(payload.duplicate()), null, opts)
                val bfMs = (System.nanoTime() - t1) / 1_000_000
                say("orient_probe $hex: bitmapfactory bounds=${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType} decoded(sample $SAMPLE)=${bf?.width}x${bf?.height} ms=$bfMs")
                bf?.recycle()
                var header = ""
                val t2 = System.nanoTime()
                val decoded = try {
                    val src = ImageDecoder.createSource(payload.duplicate())
                    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        header = "${info.size.width}x${info.size.height} mime=${info.mimeType}"
                        decoder.setTargetSampleSize(SAMPLE)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } catch (e: Exception) {
                    say("orient_probe $hex: imagedecoder failed ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                val idMs = (System.nanoTime() - t2) / 1_000_000
                say(
                    "orient_probe $hex: imagedecoder header=$header decoded(sample $SAMPLE)=${decoded?.width}x${decoded?.height} " +
                        "config=${decoded?.config} mutable=${decoded?.isMutable} ms=$idMs"
                )
                if (decoded != null) {
                    decoded.eraseColor(0)
                    decoded.recycle()
                }
            } finally {
                NativeBridge.nativeProbeFreeDirect(whole)
            }
        }
        say("orient_probe: $photos jpeg objects probed, $skipped other objects skipped")
    }
}
