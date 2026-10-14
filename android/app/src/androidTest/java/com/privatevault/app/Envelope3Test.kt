package com.privatevault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

// T2: the provider 3 vault forms through JNI with a KEK supplied by the test
// (the Keystore step is covered by KeystoreHmacTest and LockTest): create,
// open, wrong KEK, rewrap, stale partial, tamper, destroy, and the committed
// envelope vectors opened through the vault form.
@RunWith(AndroidJUnit4::class)
class Envelope3Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val dir = File(context.filesDir, "env3_test")
    private val kek = ByteArray(32) { 0x11 }
    private val otherKek = ByteArray(32) { 0x22 }
    private val salt = ByteArray(NativeBridge.PIN_SALT_BYTES) { 7 }

    @Before
    fun setUp() {
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeInit())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun create(): Long {
        val h = LongArray(1)
        val rc = NativeBridge.nativeVaultCreateEnvelope3(
            dir.absolutePath, NativeBridge.MECHANISM_ANDROID_KEYSTORE_HMAC, "privatevault.pin.test",
            2L, 64L shl 20, salt, kek, h
        )
        assertEquals(NativeBridge.errorName(rc), NativeBridge.OK, rc)
        assertTrue(h[0] != 0L)
        return h[0]
    }

    private fun open(k: ByteArray): Pair<Int, Long> {
        val h = LongArray(1)
        return NativeBridge.nativeVaultOpenEnvelope3(dir.absolutePath, k, h) to h[0]
    }

    @Test
    fun createOpenRewrapAndDestroy() {
        val h = create()
        val keyFile = File(dir, Vault.KEY_FILE)
        assertTrue(keyFile.exists())
        assertFalse(File(dir, "vault.key.partial").exists())
        val info = PinEnvelope.parse(keyFile.readBytes())
        assertEquals("privatevault.pin.test", info.alias)
        assertEquals(1, info.slotCount)
        assertEquals(2L, info.opslimit)
        assertTrue(salt.contentEquals(info.salt))
        assertEquals(NativeBridge.ENVELOPE_PROVIDER_PIN, PinEnvelope.providerOf(keyFile.readBytes()))

        val v = Vault.fromHandle(dir, h)
        val id = v.encryptSample(TestKeys.SEED, 50_000L)
        v.close()
        // A second create is refused.
        val again = LongArray(1)
        assertEquals(NativeBridge.ERR_DEST_EXISTS, NativeBridge.nativeVaultCreateEnvelope3(
            dir.absolutePath, 1, "x", 2L, 64L shl 20, salt, kek, again))
        assertEquals(0L, again[0])

        // Wrong KEK, then the right one.
        val (bad, badHandle) = open(otherKek)
        assertEquals(NativeBridge.ERR_ENVELOPE_UNWRAP_FAILED, bad)
        assertEquals(0L, badHandle)
        val (ok, handle) = open(kek)
        assertEquals(NativeBridge.OK, ok)
        val opened = Vault.fromHandle(dir, handle)
        assertEquals(NativeBridge.OK, opened.decryptVerify(id).first)

        // Rewrap under the other KEK: the old envelope bytes change, the object stays.
        val before = keyFile.readBytes()
        val salt2 = ByteArray(NativeBridge.PIN_SALT_BYTES) { 9 }
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultWriteEnvelope3(
            opened.rawHandle, 1, "privatevault.pin.second", 3L, 64L shl 20, salt2, otherKek))
        assertFalse(File(dir, "vault.key.partial").exists())
        assertFalse(before.contentEquals(keyFile.readBytes()))
        assertEquals("privatevault.pin.second", PinEnvelope.parse(keyFile.readBytes()).alias)
        assertEquals(NativeBridge.OK, opened.decryptVerify(id).first)
        opened.close()
        assertEquals(NativeBridge.ERR_ENVELOPE_UNWRAP_FAILED, open(kek).first)
        val (ok2, h2) = open(otherKek)
        assertEquals(NativeBridge.OK, ok2)
        val v2 = Vault.fromHandle(dir, h2)
        assertEquals(NativeBridge.OK, v2.decryptVerify(id).first)
        v2.close()

        // A stale partial is removed by the open.
        File(dir, "vault.key.partial").writeBytes(ByteArray(7))
        val (ok3, h3) = open(otherKek)
        assertEquals(NativeBridge.OK, ok3)
        NativeBridge.nativeVaultClose(h3)
        assertFalse(File(dir, "vault.key.partial").exists())

        // One flipped byte in the envelope fails the unwrap; a flipped provider is unsupported.
        val good = keyFile.readBytes()
        RandomAccessFile(keyFile, "rw").use { f -> f.seek(30); val b = f.read(); f.seek(30); f.write(b xor 1) }
        assertEquals(NativeBridge.ERR_ENVELOPE_UNWRAP_FAILED, open(otherKek).first)
        keyFile.writeBytes(good)
        RandomAccessFile(keyFile, "rw").use { f -> f.seek(10); f.write(2) }
        assertEquals(NativeBridge.ERR_ENVELOPE_UNSUPPORTED, open(otherKek).first)
        keyFile.writeBytes(good)

        // Destroy: everything under the directory goes, twice is fine.
        File(dir, "objects/00000000000000000000000000000000.pvo.partial").writeBytes(ByteArray(3))
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultDestroyFiles(dir.absolutePath))
        assertFalse(dir.exists())
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultDestroyFiles(dir.absolutePath))
        assertEquals(NativeBridge.ERR_NOT_FOUND, open(otherKek).first)
    }

    @Test
    fun parseReportsErrorsWithoutRunningAnything() {
        val h = create()
        NativeBridge.nativeVaultClose(h)
        val bytes = File(dir, Vault.KEY_FILE).readBytes()
        assertEquals(NativeBridge.ERR_ENVELOPE_MALFORMED, parseCode(bytes.copyOf(bytes.size - 1)))
        assertEquals(NativeBridge.ERR_ENVELOPE_MALFORMED, parseCode(bytes + byteArrayOf(0)))
        assertEquals(NativeBridge.ERR_ENVELOPE_MALFORMED, parseCode(ByteArray(0)))
        val noSlot = bytes.copyOf().also { it[16] = 7 }
        assertEquals(NativeBridge.ERR_ENVELOPE_NO_SLOT, parseCode(noSlot))
        assertEquals("PV_ERR_ENVELOPE_NO_SLOT (24)", NativeBridge.errorName(NativeBridge.ERR_ENVELOPE_NO_SLOT))
        // opslimit sits after the 4-byte slot header, mechanism, alias_len, and the alias.
        val opsOff = 16 + 4 + 2 + 2 + "privatevault.pin.test".length
        val badKdf = bytes.copyOf().also { it[opsOff] = 1 }
        assertEquals(NativeBridge.ERR_ENVELOPE_KDF_PARAMS, parseCode(badKdf))
        assertEquals(2, PinEnvelope.providerOf(bytes.copyOf().also { it[10] = 2 }))
        assertEquals(-1, PinEnvelope.providerOf(ByteArray(12)))
    }

    private fun parseCode(b: ByteArray): Int = try {
        PinEnvelope.parse(b)
        NativeBridge.OK
    } catch (e: PinEnvelope.ParseException) {
        e.code
    }

    // The committed vectors: each envelope, placed as vault.key beside an
    // object vector, opens through the vault form with the KEK stand-in and
    // the object verifies with the VMK that came out (never seen here).
    @Test
    fun committedEnvelopeVectorsOpenThroughTheVaultForm() {
        val fields = assets.open("envelope3.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore(' ') to it.substringAfter(' ').trim() }
        val kekVec = NativeBridge.fromHex(fields.first { it.first == "kek_hex" }.second)
        val vectors = fields.filter { it.first == "vector" }.map { it.second.substringBefore(' ') }
        assertEquals(2, vectors.size)
        // one_byte vector: find its object id in the manifest.
        val manifestLine = assets.open("manifest.txt").bufferedReader().readLines().first { it.startsWith("one_byte ") }
        val idHex = manifestLine.trim().split(Regex("\\s+"))[4]
        for (name in vectors) {
            dir.deleteRecursively()
            File(dir, "objects").mkdirs()
            assets.open(name).use { i -> File(dir, Vault.KEY_FILE).outputStream().use { i.copyTo(it) } }
            assets.open("one_byte.pvo").use { i -> File(dir, "objects/$idHex.pvo").outputStream().use { i.copyTo(it) } }
            assertEquals(NativeBridge.ERR_ENVELOPE_UNWRAP_FAILED, open(otherKek).first)
            val (rc, h) = open(kekVec)
            assertEquals("$name: ${NativeBridge.errorName(rc)}", NativeBridge.OK, rc)
            val v = Vault.fromHandle(dir, h)
            val (vrc, r) = v.decryptVerify(NativeBridge.fromHex(idHex))
            assertEquals(NativeBridge.OK, vrc)
            assertEquals(1L, r!!.length)
            v.close()
        }
        assertNotEquals(0, vectors.size)
    }
}
