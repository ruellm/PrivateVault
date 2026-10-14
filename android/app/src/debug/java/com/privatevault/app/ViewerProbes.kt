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

    // Tag names the platform class knows, checked for presence only. GPS tags first.
    private val tagNames = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_Y_CB_CR_POSITIONING, ExifInterface.TAG_EXIF_VERSION, ExifInterface.TAG_FLASHPIX_VERSION,
        ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_COMPONENTS_CONFIGURATION, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_INTEROPERABILITY_INDEX, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_COMPRESSION, ExifInterface.TAG_BITS_PER_SAMPLE
    )

    // Which EXIF tags the first few JPEG objects carry: names only, never a value.
    // The GPS presence check uses the platform latLong reader as a second opinion.
    fun exifTags(vaults: VaultSession, say: (String) -> Unit, limit: Int = 3) {
        val v = vaults.vault ?: throw IllegalStateException("vault not open")
        var seen = 0
        for (e in v.listObjects()) {
            if (seen >= limit) break
            val load = v.viewerLoad(e.id, PhotoLoader.SIZE_CAP)
            if (load.status != NativeBridge.OK || load.item?.mediaType != NativeBridge.MEDIA_JPEG) {
                load.close()
                continue
            }
            load.use { l ->
                val payload = l.payload ?: return@use
                val exif = ExifInterface(ByteBufferInputStream(payload.duplicate()))
                val present = tagNames.filter { exif.getAttribute(it) != null }
                val gps = present.filter { it.startsWith("GPS") }
                val latLong = FloatArray(2)
                val hasLatLong = exif.getLatLong(latLong)
                latLong.fill(0f)
                say("exif_tags ${e.hex}: ${present.size} of ${tagNames.size} known tags present, gps_tags=${gps.size} latLong=${if (hasLatLong) "PRESENT" else "none"} thumbnail=${exif.hasThumbnail()}")
                say("exif_tags ${e.hex}: tags ${present.joinToString(" ")}")
                if (gps.isNotEmpty()) say("exif_tags ${e.hex}: GPS tags ${gps.joinToString(" ")}")
            }
            seen++
        }
        say("exif_tags: $seen jpeg objects inspected")
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
