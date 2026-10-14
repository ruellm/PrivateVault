package com.privatevault.app

import android.annotation.SuppressLint
// The platform class over an in-memory stream, by decision D-026.
//noinspection ExifInterface
import android.media.ExifInterface
import java.nio.ByteBuffer

// EXIF orientation of a JPEG held in memory (docs/PHASE3B_DESIGN.md Section
// 5.3, D-026). The platform class reads from an in-memory stream; no file.
object JpegExif {

    // The orientation tag value (1 to 8), or 0 when the tag is absent or the
    // bytes do not parse. The buffer position is left as it was.
    @SuppressLint("ExifInterface")
    fun orientation(payload: ByteBuffer): Int = try {
        ExifInterface(ByteBufferInputStream(payload.duplicate()))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
    } catch (e: Exception) {
        0
    }

    // The clockwise turn the tag asks for; mirrored values map to the same turn.
    fun degrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }
}
