package com.privatevault.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

// Minimal proof-of-concept screen. Every action runs on a worker thread and
// prints a line with pass or fail. Nothing shown or logged is a key, a
// password, or sample content; object IDs and hashes of synthetic data are
// the only values printed.
class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private var vault: Vault? = null
    private val provider by lazy { KeystoreKeyProvider(this) }

    // Public test seed; the same stream the host tests use.
    private val seed = "PRIVATEVAULT TEST ONLY SEED 0001".toByteArray(Charsets.US_ASCII)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                setOnClickListener { run(label, action) }
            })
        }
        button("Create or open vault") { createOrOpen() }
        button("Encrypt 1 MB sample") { encrypt(1L shl 20) }
        button("Encrypt 100 MB sample") { encrypt(100L shl 20) }
        button("Decrypt and verify all objects") { verifyAll() }
        button("Tamper test") { tamperTest() }
        button("Truncation test") { truncationTest() }
        button("Wrong key test") { wrongKeyTest() }
        button("Keystore security level") { securityLevel() }
        button("Close vault") { closeVault() }

        log = TextView(this).apply { setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(ScrollView(this).apply {
            addView(buttons)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.5f))
        setContentView(root)

        val init = NativeBridge.nativeInit()
        say(
            "core ${NativeBridge.nativeVersion()}, libsodium ${NativeBridge.nativeSodiumVersion()}, " +
                "pv_init ${NativeBridge.errorName(init)}"
        )
        say("vault present: ${Vault.exists(filesDir)}; Keystore key present: ${provider.keyExists()}")
        say("secure lock screen: ${provider.deviceIsSecure()}; key mode: ${provider.keyMode()}")
        say("Uninstall, Clear Data, or a factory reset destroys the Keystore key and the vault.")
        handleScriptedActions(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        handleScriptedActions(intent)
    }

    // Scripted driving for the T3 device steps (debug proof of concept only):
    //   adb shell am start -n com.privatevault.app/.MainActivity --es actions open,encrypt_1mb,verify
    // Actions run in order on the worker thread and print to the log and logcat.
    private fun handleScriptedActions(intent: android.content.Intent?) {
        val list = intent?.getStringExtra("actions") ?: return
        for (name in list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val action: () -> Unit = when (name) {
                "open" -> ::createOrOpen
                "encrypt_1mb" -> { { encrypt(1L shl 20) } }
                "encrypt_100mb" -> { { encrypt(100L shl 20) } }
                "verify" -> ::verifyAll
                "tamper" -> ::tamperTest
                "truncation" -> ::truncationTest
                "wrong_key" -> ::wrongKeyTest
                "security_level" -> ::securityLevel
                "close" -> ::closeVault
                else -> { { say("unknown action $name") } }
            }
            run(name, action)
        }
        executor.execute { say("[scripted] all actions finished") }
    }

    override fun onDestroy() {
        super.onDestroy()
        vault?.close()
        executor.shutdown()
    }

    private fun say(line: String) {
        Log.i("PrivateVault", line)
        main.post {
            log.append(line + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun run(label: String, action: () -> Unit) {
        executor.execute {
            val t0 = System.nanoTime()
            try {
                action()
                say("[$label] done in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (e: Vault.CoreException) {
                say("[$label] FAIL: ${e.message}")
            } catch (e: KeystoreKeyProvider.EnvelopeException) {
                say("[$label] FAIL: envelope: ${NativeBridge.errorName(e.code)}")
            } catch (e: Exception) {
                say("[$label] FAIL: ${describeChain(e)}")
            }
        }
    }

    // Class and message of the exception and of each cause. Messages from the
    // platform name the failing API; they carry no key material.
    private fun describeChain(e: Throwable): String {
        val parts = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            parts.add("${t.javaClass.name}: ${t.message ?: "(no message)"}")
            t = t.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun requireVault(): Vault = vault ?: throw IllegalStateException("vault not open")

    private fun createOrOpen() {
        vault?.close()
        vault = if (Vault.exists(filesDir)) {
            Vault.open(filesDir, provider).also { say("opened existing vault") }
        } else {
            Vault.create(filesDir, provider).also { say("created new vault") }
        }
        say(provider.describe())
        say("objects in vault: ${requireVault().listObjectIds().size}")
    }

    private fun encrypt(length: Long) {
        val id = requireVault().encryptSample(seed, length)
        say("encrypted $length bytes -> ${NativeBridge.hex(id)}")
    }

    private fun verifyAll() {
        val v = requireVault()
        val ids = v.listObjectIds()
        if (ids.isEmpty()) say("no objects")
        for (id in ids) {
            val (rc, result) = v.decryptVerify(id)
            if (rc != NativeBridge.OK || result == null) {
                say("${NativeBridge.hex(id)}: FAIL ${NativeBridge.errorName(rc)}")
                continue
            }
            val expect = ByteArray(NativeBridge.HASH_BYTES)
            NativeBridge.nativeHashDeterministic(seed, result.length, expect)
            val ok = expect.contentEquals(result.hash)
            say("${NativeBridge.hex(id)}: ${result.length} bytes, hash ${if (ok) "matches" else "MISMATCH"}")
        }
    }

    // Encrypts a small object, damages one ciphertext byte, expects AUTH_FAILED,
    // then deletes the damaged object.
    private fun tamperTest() {
        val v = requireVault()
        val id = v.encryptSample(seed, 200_000L)
        val file = v.objectPath(id)
        RandomAccessFile(file, "rw").use { f ->
            f.seek(128 + 10)
            val b = f.read()
            f.seek(128 + 10)
            f.write(b xor 1)
        }
        val (rc, _) = v.decryptVerify(id)
        report("tamper", rc, NativeBridge.ERR_AUTH_FAILED)
        file.delete()
    }

    // Cuts the object exactly after its first full chunk, expects TRUNCATED.
    private fun truncationTest() {
        val v = requireVault()
        val id = v.encryptSample(seed, 200_000L)
        val file = v.objectPath(id)
        RandomAccessFile(file, "rw").use { f -> f.setLength(128L + 65536 + 17) }
        val (rc, _) = v.decryptVerify(id)
        report("truncation", rc, NativeBridge.ERR_TRUNCATED)
        file.delete()
    }

    // Opens the same directory with a random key and expects WRONG_VMK.
    private fun wrongKeyTest() {
        val v = requireVault()
        val id = v.encryptSample(seed, 100_000L)
        val random = ByteArray(NativeBridge.VMK_BYTES)
        NativeBridge.nativeVmkGenerate(random)
        val other = Vault.openWithKey(v.dir, random)
        random.fill(0)
        try {
            val (rc, _) = other.decryptVerify(id)
            report("wrong key", rc, NativeBridge.ERR_WRONG_VMK)
        } finally {
            other.close()
        }
        v.objectPath(id).delete()
    }

    private fun report(name: String, got: Int, expected: Int) {
        val verdict = if (got == expected) "PASS" else "FAIL"
        say("$name test: $verdict (expected ${NativeBridge.errorName(expected)}, got ${NativeBridge.errorName(got)})")
    }

    private fun securityLevel() {
        provider.getOrCreateKey()
        say("secure lock screen: ${provider.deviceIsSecure()}")
        say(provider.describe())
        say("link() in filesDir: ${NativeBridge.nativeProbeLink(filesDir.absolutePath).let { if (it == 0) "allowed" else "denied (errno $it)" }}")
    }

    private fun closeVault() {
        vault?.close()
        vault = null
        say("vault closed")
    }
}
