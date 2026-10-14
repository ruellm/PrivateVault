package com.privatevault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// T2: PIN stretching through JNI against the recorded vector answer, the
// parameter bounds, salt generation, and the delay arithmetic.
@RunWith(AndroidJUnit4::class)
class PinTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    // key value lines of tests/fixtures/v1/envelope3.txt (a test asset).
    private fun vectorFields(): Map<String, String> =
        assets.open("envelope3.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { l -> l.substringBefore(' ') to l.substringAfter(' ').trim() }

    @Test
    fun stretchMatchesTheRecordedAnswer() {
        val f = vectorFields()
        val pin = f.getValue("pin").toByteArray(Charsets.US_ASCII)
        val salt = NativeBridge.fromHex(f.getValue("salt_hex"))
        val out = ByteArray(NativeBridge.KEK_BYTES)
        assertEquals(
            NativeBridge.OK,
            NativeBridge.nativePinStretch(pin, salt, f.getValue("opslimit").toLong(), f.getValue("memlimit").toLong(), out)
        )
        assertEquals(f.getValue("k_pin_hex"), NativeBridge.hex(out))
        // Another PIN or salt gives something else.
        val other = ByteArray(NativeBridge.KEK_BYTES)
        assertEquals(NativeBridge.OK, NativeBridge.nativePinStretch("000001".toByteArray(), salt, 2L, 64L shl 20, other))
        assertFalse(out.contentEquals(other))
    }

    @Test
    fun boundsAreRefusedBeforeTheKdf() {
        val pin = "123456".toByteArray()
        val salt = ByteArray(NativeBridge.PIN_SALT_BYTES)
        val out = ByteArray(NativeBridge.KEK_BYTES)
        val t0 = System.nanoTime()
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, NativeBridge.nativePinStretch(pin, salt, 1L, 64L shl 20, out))
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, NativeBridge.nativePinStretch(pin, salt, 4L, 64L shl 20, out))
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, NativeBridge.nativePinStretch(pin, salt, 2L, (64L shl 20) - 1, out))
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, NativeBridge.nativePinStretch(pin, salt, 2L, (256L shl 20) + 1, out))
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, NativeBridge.nativePinStretch(pin, salt, 2L, 1L shl 40, out))
        assertTrue("bounds checks took too long", (System.nanoTime() - t0) < 100_000_000L)
        assertEquals(NativeBridge.ERR_INVALID_ARGUMENT, NativeBridge.nativePinStretch(ByteArray(0), salt, 2L, 64L shl 20, out))
        assertEquals(NativeBridge.ERR_INVALID_ARGUMENT, NativeBridge.nativePinStretch(pin, ByteArray(15), 2L, 64L shl 20, out))
        assertEquals(NativeBridge.ERR_INVALID_ARGUMENT, NativeBridge.nativePinStretch(pin, salt, 2L, 64L shl 20, ByteArray(31)))
        assertTrue(out.all { it == 0.toByte() })
        assertEquals(2L, NativeBridge.PIN_OPSLIMIT_DEFAULT)
        assertEquals(256L shl 20, NativeBridge.PIN_MEMLIMIT_DEFAULT)
    }

    @Test
    fun saltsAreFreshAndDelaysFollowTheTable() {
        val a = ByteArray(NativeBridge.PIN_SALT_BYTES)
        val b = ByteArray(NativeBridge.PIN_SALT_BYTES)
        assertEquals(NativeBridge.OK, NativeBridge.nativePinSaltGenerate(a))
        assertEquals(NativeBridge.OK, NativeBridge.nativePinSaltGenerate(b))
        assertFalse(a.contentEquals(b))
        assertEquals(NativeBridge.ERR_INVALID_ARGUMENT, NativeBridge.nativePinSaltGenerate(ByteArray(8)))
        val expect = longArrayOf(0, 0, 0, 0, 0, 30_000, 60_000, 120_000, 240_000, 480_000, 960_000, 1_920_000, 3_600_000, 3_600_000)
        for (i in expect.indices) assertEquals("failures $i", expect[i], NativeBridge.nativePinDelayMs(i))
        assertEquals(3_600_000L, NativeBridge.nativePinDelayMs(100))
        // Remaining time: same boot takes the larger estimate; a reboot restarts.
        assertEquals(20_000L, NativeBridge.nativePinRemainingMs(30_000, 100_000, 110_000, 1_000_000, 1_010_000, false))
        assertEquals(20_000L, NativeBridge.nativePinRemainingMs(30_000, 100_000, 110_000, 1_000_000, 9_000_000, false))
        assertEquals(30_000L, NativeBridge.nativePinRemainingMs(30_000, 100_000, 110_000, 1_000_000, 500_000, false))
        assertEquals(0L, NativeBridge.nativePinRemainingMs(30_000, 100_000, 200_000, 1_000_000, 1_100_000, false))
        assertEquals(30_000L, NativeBridge.nativePinRemainingMs(30_000, 100_000, 200_000, 1_000_000, 1_100_000, true))
        assertEquals(0L, NativeBridge.nativePinRemainingMs(0, 0, 0, 0, 0, true))
    }
}
