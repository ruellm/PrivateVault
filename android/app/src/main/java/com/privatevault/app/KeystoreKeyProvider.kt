package com.privatevault.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

// Android key envelope (vault.key, provider 2). The vault master key is wrapped
// with a non-exportable AES-256-GCM key in Android Keystore. Layout in
// docs/PHASE1_DESIGN.md Section 5. The core never sees this file.
class KeystoreKeyProvider(private val context: Context) {

    class EnvelopeException(val code: Int) : Exception(NativeBridge.errorName(code))

    companion object {
        const val ALIAS = "privatevault.vmk.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private val MAGIC = "PVKEYENV".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val PROVIDER_ID = 2
        private const val GCM_TAG_BITS = 128

        const val ERR_ENVELOPE_MALFORMED = 14
        const val ERR_ENVELOPE_UNSUPPORTED = 15
        const val ERR_ENVELOPE_UNWRAP_FAILED = 17
    }

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    // Returns the existing key or generates one. StrongBox is tried first when
    // the device reports it; on StrongBoxUnavailableException the key is made
    // without it. User authentication is not required in Phase 1.
    fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val hasStrongBox =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (hasStrongBox) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // Fall through to the regular Keystore.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
        if (strongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun keyExists(): Boolean = keyStore.containsAlias(ALIAS)

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    // "STRONGBOX", "TRUSTED_ENVIRONMENT", "SOFTWARE", or "UNKNOWN" plus the
    // raw value, as reported by Keystore for the key.
    fun securityLevel(): String {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return "NO_KEY"
        val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (val level = info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
                else -> "UNKNOWN($level)"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "SECURE_HARDWARE" else "SOFTWARE"
        }
    }

    // Wraps vmk into an envelope. Keystore generates the IV; it is stored as returned.
    fun seal(vmk: ByteArray): ByteArray {
        require(vmk.size == NativeBridge.VMK_BYTES)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val alias = ALIAS.toByteArray(Charsets.UTF_8)
        val header = ByteArrayOutputStream()
        header.write(MAGIC)
        header.write(u16(VERSION))
        header.write(u16(PROVIDER_ID))
        header.write(u16(alias.size))
        header.write(alias)
        header.write(u16(iv.size))
        header.write(iv)
        header.write(u16(vmk.size + GCM_TAG_BITS / 8))
        val aad = header.toByteArray()
        cipher.updateAAD(aad)
        val wrapped = cipher.doFinal(vmk)
        return aad + wrapped
    }

    // Recovers the VMK. Throws EnvelopeException with a PV_ERR_ENVELOPE_* code.
    fun open(envelope: ByteArray): ByteArray {
        var pos = 0
        fun need(n: Int) {
            if (pos + n > envelope.size) throw EnvelopeException(ERR_ENVELOPE_MALFORMED)
        }
        need(MAGIC.size + 4)
        if (!envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw EnvelopeException(ERR_ENVELOPE_MALFORMED)
        }
        pos = MAGIC.size
        val version = readU16(envelope, pos); pos += 2
        val provider = readU16(envelope, pos); pos += 2
        if (version != VERSION || provider != PROVIDER_ID) {
            throw EnvelopeException(ERR_ENVELOPE_UNSUPPORTED)
        }
        need(2)
        val aliasLen = readU16(envelope, pos); pos += 2
        if (aliasLen < 1 || aliasLen > 64) throw EnvelopeException(ERR_ENVELOPE_MALFORMED)
        need(aliasLen)
        val alias = String(envelope, pos, aliasLen, Charsets.UTF_8); pos += aliasLen
        need(2)
        val ivLen = readU16(envelope, pos); pos += 2
        if (ivLen < 12 || ivLen > 16) throw EnvelopeException(ERR_ENVELOPE_MALFORMED)
        need(ivLen)
        val iv = envelope.copyOfRange(pos, pos + ivLen); pos += ivLen
        need(2)
        val wrappedLen = readU16(envelope, pos); pos += 2
        if (wrappedLen != NativeBridge.VMK_BYTES + GCM_TAG_BITS / 8) {
            throw EnvelopeException(ERR_ENVELOPE_MALFORMED)
        }
        val aad = envelope.copyOfRange(0, pos)
        need(wrappedLen)
        val wrapped = envelope.copyOfRange(pos, pos + wrappedLen); pos += wrappedLen
        if (pos != envelope.size) throw EnvelopeException(ERR_ENVELOPE_MALFORMED)

        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: throw EnvelopeException(ERR_ENVELOPE_UNWRAP_FAILED)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(wrapped)
        } catch (e: AEADBadTagException) {
            throw EnvelopeException(ERR_ENVELOPE_UNWRAP_FAILED)
        }
    }

    private fun u16(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun readU16(b: ByteArray, pos: Int): Int =
        (b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8)
}
