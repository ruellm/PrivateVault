package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// T2: Keystore wrap and unwrap of a VMK, envelope tampering, and the full
// create/open path of the app's Vault class.
@RunWith(AndroidJUnit4::class)
class KeystoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = KeystoreKeyProvider(context)

    @Test
    fun sealAndOpenRoundTrip() {
        val vmk = ByteArray(32)
        assertEquals(NativeBridge.OK, NativeBridge.nativeVmkGenerate(vmk))
        val env = provider.seal(vmk)
        assertTrue(env.size > 8 + 2 + 2 + 2 + 2 + 12 + 2 + 48)
        assertEquals("PVKEYENV", String(env, 0, 8, Charsets.US_ASCII))
        val back = provider.open(env)
        assertTrue(vmk.contentEquals(back))
        // Two seals differ (fresh IV) and both open.
        val env2 = provider.seal(vmk)
        assertFalse(env.contentEquals(env2))
        assertTrue(vmk.contentEquals(provider.open(env2)))
        val level = provider.securityLevel()
        Log.i("PrivateVaultTest", provider.describe())
        assertTrue(level in listOf("STRONGBOX", "TRUSTED_ENVIRONMENT", "SOFTWARE", "SECURE_HARDWARE", "UNKNOWN_SECURE"))
    }

    private fun expectCode(env: ByteArray, code: Int) {
        try {
            provider.open(env)
            fail("expected failure $code")
        } catch (e: KeystoreKeyProvider.EnvelopeException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun tamperedEnvelopeFails() {
        val vmk = ByteArray(32) { 7 }
        val env = provider.seal(vmk)
        fun flipped(i: Int) = env.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
        expectCode(flipped(0), KeystoreKeyProvider.ERR_ENVELOPE_MALFORMED)       // magic
        expectCode(flipped(8), KeystoreKeyProvider.ERR_ENVELOPE_UNSUPPORTED)     // version
        expectCode(flipped(10), KeystoreKeyProvider.ERR_ENVELOPE_UNSUPPORTED)    // provider
        expectCode(flipped(14), KeystoreKeyProvider.ERR_ENVELOPE_UNWRAP_FAILED)  // alias byte (AAD)
        val ivOffset = 14 + KeystoreKeyProvider.ALIAS.length + 2
        expectCode(flipped(ivOffset), KeystoreKeyProvider.ERR_ENVELOPE_UNWRAP_FAILED)   // iv
        expectCode(flipped(env.size - 1), KeystoreKeyProvider.ERR_ENVELOPE_UNWRAP_FAILED) // tag
        expectCode(flipped(env.size - 48), KeystoreKeyProvider.ERR_ENVELOPE_UNWRAP_FAILED) // body
        expectCode(env.copyOf(env.size - 1), KeystoreKeyProvider.ERR_ENVELOPE_MALFORMED)  // short
        expectCode(env + byteArrayOf(0), KeystoreKeyProvider.ERR_ENVELOPE_MALFORMED)     // long
        expectCode(ByteArray(0), KeystoreKeyProvider.ERR_ENVELOPE_MALFORMED)
    }

    @Test
    fun vaultCreateOpenAndPersist() {
        val files = File(context.filesDir, "keystore_test")
        files.deleteRecursively()
        files.mkdirs()
        val created = Vault.create(files, provider)
        val id = created.encryptSample(TestKeys.SEED, 50_000L)
        created.close()
        assertTrue(File(Vault.dirFor(files), Vault.KEY_FILE).exists())
        assertFalse(File(Vault.dirFor(files), Vault.KEY_FILE + ".partial").exists())
        try {
            Vault.create(files, provider)
            fail("second create must fail")
        } catch (e: Vault.CoreException) {
            assertEquals(NativeBridge.ERR_DEST_EXISTS, e.code)
        }
        val opened = Vault.open(files, provider)
        val (rc, result) = opened.decryptVerify(id)
        assertEquals(NativeBridge.OK, rc)
        assertEquals(50_000L, result!!.length)
        opened.close()
        files.deleteRecursively()
    }

    // The emulator has no secure lock screen, so a freshly generated key must
    // take the not-lock-bound path and the vault must still create and open.
    @Test
    fun noLockScreenPathCreatesUsableKey() {
        assertFalse("test expects an emulator without a screen lock", provider.deviceIsSecure())
        provider.deleteKey()
        assertEquals("no key", provider.keyMode())
        provider.getOrCreateKey()
        assertEquals(KeystoreKeyProvider.MODE_NOT_LOCK_BOUND, provider.keyMode())
        Log.i("PrivateVaultTest", provider.describe())

        val files = File(context.filesDir, "nolock_test")
        files.deleteRecursively()
        files.mkdirs()
        val created = Vault.create(files, provider)
        val id = created.encryptSample(TestKeys.SEED, 10_000L)
        created.close()
        val opened = Vault.open(files, provider)
        assertEquals(NativeBridge.OK, opened.decryptVerify(id).first)
        opened.close()
        files.deleteRecursively()
    }

    @Test
    fun linkProbeIsLogged() {
        val rc = NativeBridge.nativeProbeLink(context.filesDir.absolutePath)
        Log.i("PrivateVaultTest", "link() in filesDir: " + if (rc == 0) "allowed" else "denied errno=$rc")
    }
}
