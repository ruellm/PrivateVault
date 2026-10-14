package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// T2: the Keystore HMAC key behind provider 3: creation, level and mode,
// deterministic MAC, a second key gives a different MAC, deletion, alias
// listing.
@RunWith(AndroidJUnit4::class)
class KeystoreHmacTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hmac = KeystoreHmacProvider(context)

    @Test
    fun keyCreatesMacsAndDeletes() {
        val a = KeystoreHmacProvider.newAlias()
        val b = KeystoreHmacProvider.newAlias()
        assertTrue(a.startsWith(KeystoreHmacProvider.ALIAS_PREFIX))
        assertEquals(KeystoreHmacProvider.ALIAS_PREFIX.length + 16, a.length)
        assertFalse(a == b)
        assertFalse(hmac.exists(a))
        assertNull(hmac.mac(a, ByteArray(32)))
        try {
            val (mode, strongBox) = hmac.createKey(a)
            Log.i("PrivateVaultTest", "hmac key: mode $mode, strongbox $strongBox, level ${hmac.securityLevel(a)}")
            assertTrue(hmac.exists(a))
            assertTrue(hmac.securityLevel(a) in listOf("STRONGBOX", "TRUSTED_ENVIRONMENT", "SOFTWARE", "SECURE_HARDWARE", "UNKNOWN_SECURE"))
            assertEquals(if (hmac.deviceIsSecure()) KeystoreKeyProvider.MODE_LOCK_BOUND else KeystoreKeyProvider.MODE_NOT_LOCK_BOUND, mode)
            val input = ByteArray(32) { 3 }
            val m1 = hmac.mac(a, input)!!
            val m2 = hmac.mac(a, input)!!
            assertEquals(KeystoreHmacProvider.MAC_BYTES, m1.size)
            assertTrue(m1.contentEquals(m2))
            assertFalse(m1.contentEquals(hmac.mac(a, ByteArray(32) { 4 })))
            hmac.create(b)
            assertFalse(m1.contentEquals(hmac.mac(b, input)))
            assertTrue(hmac.aliases().containsAll(listOf(a, b)))
        } finally {
            hmac.delete(a)
            hmac.delete(b)
        }
        assertFalse(hmac.exists(a))
        assertNull(hmac.mac(a, ByteArray(32)))
        assertEquals("NO_KEY", hmac.securityLevel(a))
        assertFalse(hmac.aliases().contains(a))
        assertEquals(NativeBridge.MECHANISM_ANDROID_KEYSTORE_HMAC, hmac.mechanism)
    }
}
