package com.privatevault.app

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

// The device-bound step of the PIN envelope (docs/PHASE3A_DESIGN.md Section
// 7): a key that cannot leave this device turns the stretched PIN into the
// key encryption key. Android implements it with a Keystore HMAC key; another
// platform would implement it with its own mechanism number.
interface DeviceBoundKey {
    val mechanism: Int
    fun create(alias: String)
    fun exists(alias: String): Boolean
    // 32-byte KEK, or null when the alias holds no key. Throws the platform
    // exception when the key exists but cannot be used.
    fun derive(alias: String, kPin: ByteArray): ByteArray?
    fun delete(alias: String)
}

// Provider 3 device-bound key: a non-exportable HMAC-SHA256 key in Android
// Keystore. The key never leaves Keystore, so the envelope can only be opened
// on the device that made it. Key mode and StrongBox handling follow
// KeystoreKeyProvider (D-010). Each rewrap creates a key under a fresh alias
// and deletes the previous one after the envelope is committed (D-017).
class KeystoreHmacProvider(private val context: Context) : DeviceBoundKey {

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val MAC_ALGORITHM = "HmacSHA256"
        const val MAC_BYTES = 32
        const val ALIAS_PREFIX = "privatevault.pin."

        // A fresh alias: the prefix plus 16 hex characters from 8 random bytes.
        fun newAlias(): String {
            val random = ByteArray(8)
            java.security.SecureRandom().nextBytes(random)
            return ALIAS_PREFIX + NativeBridge.hex(random)
        }
    }

    override val mechanism: Int get() = NativeBridge.MECHANISM_ANDROID_KEYSTORE_HMAC

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Volatile var lastMode: String = "unknown"
        private set
    @Volatile var lastStrongBox: Boolean = false
        private set

    fun deviceIsSecure(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isDeviceSecure
    }

    override fun exists(alias: String): Boolean = keyStore.containsAlias(alias)

    fun keyExists(alias: String): Boolean = exists(alias)

    // Creates the key under alias. The caller checks that the alias is free.
    override fun create(alias: String) {
        createKey(alias)
    }

    // Returns the key mode used ("lock-bound" or "not lock-bound") and whether
    // StrongBox was used.
    fun createKey(alias: String): Pair<String, Boolean> {
        val lockBound = deviceIsSecure()
        val hasStrongBox =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        var strongBox = false
        if (hasStrongBox) {
            try {
                generate(alias, strongBox = true, lockBound = lockBound)
                strongBox = true
            } catch (e: ProviderException) {
                generate(alias, strongBox = false, lockBound = lockBound)
            }
        } else {
            generate(alias, strongBox = false, lockBound = lockBound)
        }
        val mode = if (lockBound) KeystoreKeyProvider.MODE_LOCK_BOUND
        else KeystoreKeyProvider.MODE_NOT_LOCK_BOUND
        lastMode = mode
        lastStrongBox = strongBox
        return mode to strongBox
    }

    private fun generate(alias: String, strongBox: Boolean, lockBound: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (lockBound) {
            builder.setUnlockedDeviceRequired(true)
        }
        if (strongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(builder.build())
        generator.generateKey()
    }

    override fun delete(alias: String) {
        deleteKey(alias)
    }

    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    // Every alias of this provider present in Keystore.
    fun aliases(): List<String> {
        val out = ArrayList<String>()
        val e = keyStore.aliases()
        while (e.hasMoreElements()) {
            val a = e.nextElement()
            if (a.startsWith(ALIAS_PREFIX)) out.add(a)
        }
        return out
    }

    override fun derive(alias: String, kPin: ByteArray): ByteArray? = mac(alias, kPin)

    // HMAC-SHA256 of input under the key. Null when the alias has no key.
    // Throws the Keystore exception when the key exists but cannot be used
    // (device locked, key invalidated).
    fun mac(alias: String, input: ByteArray): ByteArray? {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        val m = Mac.getInstance(MAC_ALGORITHM)
        m.init(key)
        return m.doFinal(input)
    }

    // Security level as Keystore reports it for the key, or NO_KEY.
    fun securityLevel(alias: String): String {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return "NO_KEY"
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
}
