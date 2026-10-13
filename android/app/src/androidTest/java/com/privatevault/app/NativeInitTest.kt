package com.privatevault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeInitTest {
    @Test
    fun nativeLibraryLoadsAndInitializes() {
        assertEquals(0, NativeBridge.nativeInit())
        assertTrue(NativeBridge.nativeSodiumVersion() >= "1.0.21")
    }
}
