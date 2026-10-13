package com.privatevault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

// T2: round trip in filesDir through JNI, tamper, truncation, wrong key, and
// partial-file cleanup, with the public TEST ONLY key and seed.
@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "test_vault")
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        dir.deleteRecursively()
        assertEquals(NativeBridge.OK, NativeBridge.nativeInit())
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        vault = Vault.openWithKey(dir, TestKeys.VMK)
    }

    @After
    fun tearDown() {
        vault.close()
        dir.deleteRecursively()
    }

    private fun expectedHash(length: Long): ByteArray {
        val h = ByteArray(NativeBridge.HASH_BYTES)
        assertEquals(NativeBridge.OK, NativeBridge.nativeHashDeterministic(TestKeys.SEED, length, h))
        return h
    }

    @Test
    fun roundTripOneMegabyte() {
        val length = 1L shl 20
        val id = vault.encryptSample(TestKeys.SEED, length)
        val file = vault.objectPath(id)
        assertTrue(file.exists())
        assertFalse(File(file.path + ".partial").exists())
        assertEquals(128 + length + 17 * (length / 65536 + 1), file.length())
        val (rc, result) = vault.decryptVerify(id)
        assertEquals(NativeBridge.OK, rc)
        assertNotNull(result)
        assertEquals(length, result!!.length)
        assertTrue(expectedHash(length).contentEquals(result.hash))
        val info = IntArray(3)
        assertEquals(NativeBridge.OK, NativeBridge.nativeInspect(vault.handleForTest(), id, info))
        assertEquals(1, info[0])
        assertEquals(1, info[1])
        assertEquals(65536, info[2])
    }

    @Test
    fun emptyAndBoundarySizes() {
        for (length in listOf(0L, 1L, 4095L, 4096L, 4097L, 3L * 4096 + 100)) {
            val id = vault.encryptSample(TestKeys.SEED, length, 4096)
            val (rc, result) = vault.decryptVerify(id)
            assertEquals("length $length", NativeBridge.OK, rc)
            assertEquals(length, result!!.length)
            assertTrue(expectedHash(length).contentEquals(result.hash))
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val id = vault.encryptSample(TestKeys.SEED, 200_000L)
        val file = vault.objectPath(id)
        RandomAccessFile(file, "rw").use { f ->
            f.seek(128 + 10)
            val b = f.read()
            f.seek(128 + 10)
            f.write(b xor 1)
        }
        assertEquals(NativeBridge.ERR_AUTH_FAILED, vault.decryptVerify(id).first)
    }

    @Test
    fun tamperedHeaderFails() {
        val id = vault.encryptSample(TestKeys.SEED, 1000L)
        val file = vault.objectPath(id)
        RandomAccessFile(file, "rw").use { f ->
            f.seek(60)  // inside wrapped_dek
            val b = f.read()
            f.seek(60)
            f.write(b xor 1)
        }
        assertEquals(NativeBridge.ERR_WRONG_VMK, vault.decryptVerify(id).first)
    }

    @Test
    fun truncationFails() {
        val id = vault.encryptSample(TestKeys.SEED, 200_000L)
        val file = vault.objectPath(id)
        RandomAccessFile(file, "rw").use { f -> f.setLength(128L + 65536 + 17) }
        assertEquals(NativeBridge.ERR_TRUNCATED, vault.decryptVerify(id).first)
        RandomAccessFile(file, "rw").use { f -> f.setLength(128L + 1000) }
        assertEquals(NativeBridge.ERR_AUTH_FAILED, vault.decryptVerify(id).first)
        RandomAccessFile(file, "rw").use { f -> f.setLength(100L) }
        assertEquals(5, vault.decryptVerify(id).first)  // PV_ERR_MALFORMED_HEADER
    }

    @Test
    fun wrongKeyFails() {
        val id = vault.encryptSample(TestKeys.SEED, 100_000L)
        val other = Vault.openWithKey(dir, TestKeys.OTHER_VMK)
        try {
            assertEquals(NativeBridge.ERR_WRONG_VMK, other.decryptVerify(id).first)
        } finally {
            other.close()
        }
        assertEquals(NativeBridge.OK, vault.decryptVerify(id).first)
    }

    @Test
    fun stalePartialIsRemovedOnOpen() {
        val partial = File(dir, "objects/00000000000000000000000000000000.pvo.partial")
        partial.writeBytes(ByteArray(10))
        val again = Vault.openWithKey(dir, TestKeys.VMK)
        again.close()
        assertFalse(partial.exists())
    }

    @Test
    fun missingObjectIsNotFound() {
        assertEquals(NativeBridge.ERR_NOT_FOUND, vault.decryptVerify(ByteArray(16)).first)
    }
}
