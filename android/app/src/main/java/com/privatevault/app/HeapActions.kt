package com.privatevault.app

import android.os.Debug

// Heap figures for the capture burst investigation (docs/PHASE3B_DESIGN.md
// Section 10): a forced collection with the Java heap before and after, and
// the native heap. Numbers only.
object HeapActions {

    fun gcLine(): String {
        val rt = Runtime.getRuntime()
        val before = (rt.totalMemory() - rt.freeMemory()) shr 10
        rt.gc()
        System.runFinalization()
        rt.gc()
        val after = (rt.totalMemory() - rt.freeMemory()) shr 10
        return "gc: java_used ${before}KiB -> ${after}KiB, native_heap ${Debug.getNativeHeapAllocatedSize() shr 10}KiB"
    }
}
