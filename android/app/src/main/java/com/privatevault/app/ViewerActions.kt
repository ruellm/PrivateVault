package com.privatevault.app

// Scripted viewer session for the leak scan and the device runs: loads every
// object once at thumbnail size and once at a screen size through the same
// pipeline the gallery and viewer use, erases every bitmap, and prints one
// line per object with its tile kind, the decoded size, and the orientation
// values. No image data is kept or printed.
object ViewerActions {

    fun viewAll(vaults: VaultSession, say: (String) -> Unit, screenW: Int = 1080, screenH: Int = 1920) {
        val v = vaults.vault ?: throw IllegalStateException("vault not open")
        val entries = v.listObjects()
        var photos = 0
        var others = 0
        val freedBefore = Vault.buffersFreed.get()
        val erasedBefore = PhotoLoader.erased.get()
        for (e in entries) {
            if (!v.isOpen) {
                say("view_all stopped: vault locked")
                break
            }
            val t0 = System.nanoTime()
            val thumb = PhotoLoader.load(v, e.id, PhotoLoader.THUMB_PX, PhotoLoader.THUMB_PX)
            val t1 = System.nanoTime()
            val full = PhotoLoader.load(v, e.id, screenW, screenH)
            val t2 = System.nanoTime()
            val line = StringBuilder("view_all ${e.hex}: file ${e.size} bytes, ")
            if (full is PhotoLoader.Loaded.Photo) {
                photos++
                line.append("photo thumb=${(thumb as? PhotoLoader.Loaded.Photo)?.bitmap?.width}x${(thumb as? PhotoLoader.Loaded.Photo)?.bitmap?.height} ")
                line.append("full=${full.bitmap.width}x${full.bitmap.height} turn=${full.turnDegrees} exif=${full.exifOrientation} header_rotation=${full.headerRotation} ")
            } else {
                others++
                line.append("${full.label()} ")
            }
            line.append("thumb_ms=${(t1 - t0) / 1_000_000} full_ms=${(t2 - t1) / 1_000_000}")
            say(line.toString())
            if (thumb is PhotoLoader.Loaded.Photo) PhotoLoader.dispose(thumb.bitmap)
            if (full is PhotoLoader.Loaded.Photo) PhotoLoader.dispose(full.bitmap)
        }
        say("view_all: $photos photos, $others other objects, buffers freed ${Vault.buffersFreed.get() - freedBefore}, bitmaps erased ${PhotoLoader.erased.get() - erasedBefore}")
    }
}
