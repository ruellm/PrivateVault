package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.RandomAccessFile
import java.util.concurrent.Executors

// The vault screen, reachable only while the vault is unlocked. Every action
// runs on a worker thread and prints a line with pass or fail. Nothing shown
// or logged is a key, a PIN, or capture content; object IDs, item fields,
// and hashes of synthetic data are the only values printed. Not exported:
// scripted actions reach it through the debug-only ScriptActivity.
class MainActivity : Activity(), LockController.Listener {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private val vaults: VaultSession by lazy { PvApp.of(this).vaultSession }
    private val provider: KeystoreKeyProvider get() = vaults.provider
    private val lock: LockController get() = PvApp.of(this).lock
    private var started = false

    // Public test seed; the same stream the host tests use.
    private val seed = "PRIVATEVAULT TEST ONLY SEED 0001".toByteArray(Charsets.US_ASCII)

    // Actions that need the capture screen; the rest of the list goes with them.
    private val captureActions = setOf(
        "capture_photo", "video_probe", "record", "record_hook", "flip", "memory", "test_hook_slow", "test_hook_clear", "wait"
    )

    private fun routeIfLocked(): Boolean {
        if (lock.state == LockState.UNLOCKED) return false
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return true
    }

    override fun onLockStateChanged(state: LockState) {
        if (started && state != LockState.UNLOCKED && !isFinishing) routeIfLocked()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (routeIfLocked()) return
        lock.addListener(this)
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
        button("Capture (camera)") { main.post { openCapture(emptyList()) } }
        button("Gallery") { main.post { startActivity(Intent(this, GalleryActivity::class.java)) } }
        button("Encrypt 1 MB sample") { encrypt(1L shl 20) }
        button("Encrypt 100 MB sample") { encrypt(100L shl 20) }
        button("Verify all objects") { ObjectActions.verifyAll(vaults, ::say) }
        button("List objects") { ObjectActions.list(vaults, ::say) }
        button("Leak scan") { for (l in LeakScan.run(this).lines()) say(l) }
        button("Tamper test") { tamperTest() }
        button("Truncation test") { truncationTest() }
        button("Wrong key test") { wrongKeyTest() }
        button("Keystore security level") { securityLevel() }
        button("Lock") { lock.lock("manual") }
        button("Change PIN") { main.post { startActivity(Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE)) } }
        button("Erase vault") { main.post { startActivity(Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_ERASE)) } }

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

        say(
            "core ${NativeBridge.nativeVersion()}, libsodium ${NativeBridge.nativeSodiumVersion()}, " +
                "lock state ${lock.state}, unlock took ${lock.lastUnlockMs} ms"
        )
        say(lock.describeKey())
        say("objects in vault: ${vaults.vault?.listObjectIds()?.size ?: -1}")
        handleScriptedActions(intent)
    }

    override fun onStart() {
        super.onStart()
        started = true
        routeIfLocked()
    }

    override fun onStop() {
        started = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleScriptedActions(intent)
    }

    // Scripted driving for the T2 and T3 steps (D-009, D-019). The list comes
    // from ScriptActivity in the debug variant, which has already unlocked.
    // Actions run in order on the worker thread and print to the log and
    // logcat. A capture action hands itself and everything after it to
    // CaptureActivity.
    private fun handleScriptedActions(intent: Intent?) {
        val list = intent?.getStringExtra("actions") ?: return
        val names = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val handoff = names.indexOfFirst { n -> captureActions.any { n == it || n.startsWith("${it}_n=") || n.startsWith("$it=") } }
        val local = if (handoff < 0) names else names.subList(0, handoff)
        for (name in local) {
            val action: () -> Unit = when {
                name == "open" -> ::status
                name == "encrypt_1mb" -> { { encrypt(1L shl 20) } }
                name == "encrypt_100mb" -> { { encrypt(100L shl 20) } }
                name == "verify" || name == "verify_all" -> { { ObjectActions.verifyAll(vaults, ::say) } }
                name == "list" -> { { ObjectActions.list(vaults, ::say) } }
                name == "leak_scan" -> { { for (l in LeakScan.run(this).lines()) say(l) } }
                name == "tamper" -> ::tamperTest
                name == "truncation" -> ::truncationTest
                name == "wrong_key" -> ::wrongKeyTest
                name == "security_level" -> ::securityLevel
                name == "kdf_bench" -> { { Diagnostics.kdfBench(::say) } }
                name == "hmac_probe" -> { { Diagnostics.hmacProbe(this, ::say) } }
                name == "lock" -> { { lock.lock("script"); say("lock state ${lock.state}") } }
                name == "erase" -> { { say("erase: ${NativeBridge.errorName(lock.erase())}, lock state ${lock.state}") } }
                // Phase 3B: the viewer pipeline over every object, the gallery
                // screen, delete by ID, and a forced collection for the heap steps.
                name == "view_all" -> { { ViewerActions.viewAll(vaults, ::say) } }
                name == "gallery" -> { { main.post { startActivity(Intent(this, GalleryActivity::class.java)) } } }
                name.startsWith("delete_object=") -> { { deleteObject(name.substringAfter('=')) } }
                name == "gc" -> { { forceGc() } }
                // Phase 3C: the playback probe, debug variant only.
                name.startsWith("video_play=") -> { { openPlayback(name.substringAfter('=')) } }
                // Phase 3D: the video viewer, delete of a clip, and the sweep.
                name.startsWith("video_open=") -> { { openVideo(name.substringAfter('=')) } }
                name.startsWith("delete_video=") -> { { deleteVideo(name.substringAfter('=')) } }
                name == "sweep" -> { { say("sweep: ${VideoItems.sweep(requireVault()).line()}") } }
                else -> { { say("unknown action $name") } }
            }
            run(name, action)
        }
        if (handoff >= 0) {
            val rest = names.subList(handoff, names.size)
            executor.execute { main.post { openCapture(rest) } }
        } else {
            executor.execute { say("[scripted] all actions finished") }
        }
    }

    private fun openCapture(actions: List<String>) {
        val i = Intent(this, CaptureActivity::class.java)
        if (actions.isNotEmpty()) i.putExtra(CaptureActivity.EXTRA_ACTIONS, actions.joinToString(","))
        startActivity(i)
    }

    override fun onDestroy() {
        lock.removeListener(this)
        executor.shutdown()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i("PrivateVault", line)
        main.post {
            if (this::log.isInitialized) {
                log.append(line + "\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
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

    private fun requireVault(): Vault = vaults.vault ?: throw IllegalStateException("vault not open")

    private fun status() {
        say("vault open: ${vaults.vault != null}, lock state ${lock.state}, kind ${lock.vaultKind()}")
        say(lock.describeKey())
        say("objects in vault: ${requireVault().listObjectIds().size}")
    }

    private fun encrypt(length: Long) {
        val id = requireVault().encryptSample(seed, length)
        say("encrypted $length bytes -> ${NativeBridge.hex(id)}")
    }

    private fun deleteObject(hex: String) {
        if (hex.length != 32) {
            say("delete_object: bad id")
            return
        }
        val rc = requireVault().deleteObject(NativeBridge.fromHex(hex))
        say("delete_object $hex: ${NativeBridge.errorName(rc)}")
    }

    private fun forceGc() {
        say(HeapActions.gcLine())
    }

    // The newest object that decodes as an index, or null.
    private fun latestIndex(v: Vault): String? {
        for (e in v.listObjects()) {
            if (e.size > VideoIndex.OBJECT_CAP + 4096) continue
            val d = VideoIndex.read(v, e.id)
            if (d.status == NativeBridge.OK && d.index != null) return e.hex
        }
        return null
    }

    // video_open=<index hex | latest>[:seeks=<n>]
    private fun openVideo(arg: String) {
        val parts = arg.split(':', limit = 2)
        val hex = if (parts[0] == "latest") latestIndex(requireVault()) else parts[0]
        if (hex == null || hex.length != 32) {
            say("video_open: no index")
            return
        }
        say("video_open: $hex")
        val i = Intent(this, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_ID, hex)
        if (parts.size > 1) i.putExtra(VideoActivity.EXTRA_SCRIPT, parts[1])
        main.post { startActivity(i) }
    }

    private fun deleteVideo(hex: String) {
        if (hex.length != 32) {
            say("delete_video: bad id")
            return
        }
        val v = requireVault()
        val id = NativeBridge.fromHex(hex)
        val d = VideoIndex.read(v, id)
        val r = VideoItems.delete(v, id, d.index)
        say("delete_video $hex: ${r.line()}")
    }

    private fun openPlayback(arg: String) {
        val launcher = VideoProbes.playback
        if (launcher == null) {
            say("video_play: not available in this build")
            return
        }
        main.post { launcher(this, arg) }
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
        say("secure lock screen: ${provider.deviceIsSecure()}")
        say(lock.describeKey())
        say("provider 2 key present: ${provider.keyExists()}")
        say("link() in filesDir: ${NativeBridge.nativeProbeLink(filesDir.absolutePath).let { if (it == 0) "allowed" else "denied (errno $it)" }}")
    }
}
