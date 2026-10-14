package com.privatevault.app

import android.graphics.Bitmap
import android.util.LruCache

// Thumbnails by object ID hex, bounded by entry count. An evicted or
// replaced bitmap is erased and freed; clear does the same for all of them.
// Nothing here touches storage.
class ThumbCache(entries: Int = ENTRIES) {

    companion object {
        const val ENTRIES = 32
    }

    private val cache = object : LruCache<String, Bitmap>(entries) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) PhotoLoader.dispose(oldValue)
        }
    }

    @Synchronized
    fun get(hex: String): Bitmap? {
        val b = cache.get(hex) ?: return null
        return if (b.isRecycled) null else b
    }

    @Synchronized
    fun put(hex: String, bitmap: Bitmap) {
        cache.put(hex, bitmap)
    }

    @Synchronized
    fun remove(hex: String) {
        cache.remove(hex)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    val size: Int
        @Synchronized get() = cache.size()
}
