package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

// The lock screen (docs/PHASE3A_DESIGN.md Section 6) and the only exported
// activity. Shows setup (new vault or provider 2 migration), unlock, PIN
// change, and the erase confirmation. Digits live in PinBuffer objects that
// are zeroed on submit, on stop, and on destroy. FLAG_SECURE keeps the
// window out of screenshots and the recents card. No extras are read.
class LockActivity : Activity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        const val EXTRA_MODE = "mode"
        const val MODE_CHANGE = "change"
        const val MODE_ERASE = "erase"
        private const val ERASE_WORD = "ERASE"
    }

    private enum class Phase { ENTER, SETUP_FIRST, SETUP_CONFIRM, CHANGE_CURRENT, CHANGE_NEW, CHANGE_CONFIRM, ERASE, UNOPENABLE, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-pin") }
    private val lock: LockController get() = PvApp.of(this).lock
    private lateinit var title: TextView
    private lateinit var dots: TextView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var pad: PinPad
    private lateinit var forgot: Button
    private lateinit var cancel: Button
    private lateinit var eraseBox: LinearLayout
    private lateinit var eraseInput: EditText
    private lateinit var eraseButton: Button
    private lateinit var diag: TextView
    private val entry = PinBuffer()
    private val first = PinBuffer()
    private val current = PinBuffer()
    private var phase = Phase.ENTER
    private var mode: String? = null
    private var unopenableReason: String? = null
    private val countdown = object : Runnable {
        override fun run() {
            val remaining = lock.attempts.remainingMs()
            if (remaining > 0) {
                message.text = "Wrong PIN. Try again in ${(remaining + 999) / 1000} s"
                pad.setKeysEnabled(false)
                main.postDelayed(this, 500)
            } else {
                if (phase == Phase.ENTER || phase == Phase.CHANGE_CURRENT) message.text = ""
                pad.setKeysEnabled(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        mode = intent?.getStringExtra(EXTRA_MODE)
        if (mode == null && lock.state == LockState.UNLOCKED) {
            // Already unlocked in this process: straight to the vault screen.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (mode != null && lock.state != LockState.UNLOCKED) {
            // Change and erase from the vault screen need the unlocked state;
            // otherwise this is a plain lock screen.
            mode = null
        }
        buildViews()
        lock.addListener(this)
        choosePhase()
    }

    private fun buildViews() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        title = TextView(this).apply { textSize = 22f }
        dots = TextView(this).apply { textSize = 30f; setPadding(0, 24, 0, 24) }
        message = TextView(this).apply { textSize = 16f }
        pad = PinPad(this, ::onDigit, ::onDelete, ::onOk)
        forgot = Button(this).apply {
            text = "Forgot PIN: erase vault"
            setOnClickListener { enterPhase(Phase.ERASE) }
        }
        cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { onCancel() }
        }
        eraseInput = EditText(this).apply {
            hint = "Type $ERASE_WORD to confirm"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    eraseButton.isEnabled = s?.toString() == ERASE_WORD
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        eraseButton = Button(this).apply {
            text = "Erase vault"
            isEnabled = false
            setOnClickListener { runErase() }
        }
        eraseBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@LockActivity).apply {
                text = "This deletes the vault key and every object on this device. Nothing can be recovered afterwards."
            })
            addView(eraseInput)
            addView(eraseButton)
            visibility = View.GONE
        }
        status = TextView(this).apply { textSize = 12f; setPadding(0, 24, 0, 0) }
        diag = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        val diagButton = Button(this).apply {
            text = "Diagnostics (KDF timing, Keystore HMAC)"
            setOnClickListener { runDiagnostics() }
        }
        root.addView(title)
        root.addView(dots)
        root.addView(message)
        root.addView(pad)
        root.addView(eraseBox)
        root.addView(forgot)
        root.addView(cancel)
        root.addView(status)
        root.addView(diagButton)
        root.addView(diag)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun choosePhase() {
        when (mode) {
            MODE_CHANGE -> enterPhase(Phase.CHANGE_CURRENT)
            MODE_ERASE -> enterPhase(Phase.ERASE)
            else -> when (lock.state) {
                LockState.NO_VAULT -> enterPhase(Phase.SETUP_FIRST)
                LockState.LOCKED -> when (lock.vaultKind()) {
                    LockController.VaultKind.PROVIDER2 -> enterPhase(Phase.SETUP_FIRST)
                    LockController.VaultKind.PROVIDER3 -> enterPhase(Phase.ENTER)
                    else -> {
                        unopenableReason = "UNKNOWN_ENVELOPE"
                        enterPhase(Phase.UNOPENABLE)
                    }
                }
                LockState.UNLOCKED -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                LockState.LOCKING -> enterPhase(Phase.BUSY)
            }
        }
        status.text = statusLine()
    }

    private fun statusLine(): String {
        val kind = lock.vaultKind()
        val keyLine = if (kind == LockController.VaultKind.PROVIDER3) lock.describeKey()
        else "secure lock screen ${lock.hmac.deviceIsSecure()}"
        return "vault: $kind; $keyLine\nUninstall, Clear Data, or a factory reset destroys the Keystore keys and the vault."
    }

    private fun enterPhase(p: Phase) {
        phase = p
        entry.clear()
        refreshDots()
        pad.visibility = View.VISIBLE
        pad.setKeysEnabled(true)
        eraseBox.visibility = View.GONE
        forgot.visibility = View.GONE
        cancel.visibility = View.GONE
        message.text = ""
        when (p) {
            Phase.ENTER -> {
                title.text = "Enter PIN"
                forgot.visibility = View.VISIBLE
                countdown.run()
            }
            Phase.SETUP_FIRST -> {
                first.clear()
                title.text = if (lock.vaultKind() == LockController.VaultKind.PROVIDER2)
                    "Set a PIN for this vault (6 to 12 digits)" else "Choose a PIN (6 to 12 digits)"
                if (lock.vaultKind() == LockController.VaultKind.PROVIDER2) forgot.visibility = View.VISIBLE
            }
            Phase.SETUP_CONFIRM -> title.text = "Confirm PIN"
            Phase.CHANGE_CURRENT -> {
                current.clear()
                title.text = "Current PIN"
                cancel.visibility = View.VISIBLE
                countdown.run()
            }
            Phase.CHANGE_NEW -> {
                first.clear()
                title.text = "New PIN (6 to 12 digits)"
                cancel.visibility = View.VISIBLE
            }
            Phase.CHANGE_CONFIRM -> {
                title.text = "Confirm new PIN"
                cancel.visibility = View.VISIBLE
            }
            Phase.ERASE -> {
                title.text = "Erase vault"
                pad.visibility = View.GONE
                eraseInput.setText("")
                eraseBox.visibility = View.VISIBLE
                cancel.visibility = View.VISIBLE
            }
            Phase.UNOPENABLE -> {
                title.text = "Vault cannot be opened"
                message.text = unopenableReason ?: ""
                pad.visibility = View.GONE
                forgot.visibility = View.VISIBLE
            }
            Phase.BUSY -> {
                title.text = "Working"
                pad.setKeysEnabled(false)
            }
        }
    }

    private fun refreshDots() {
        dots.text = "● ".repeat(entry.length).trim()
    }

    private fun onDigit(d: Int) {
        if (entry.push(d)) refreshDots()
    }

    private fun onDelete() {
        entry.pop()
        refreshDots()
    }

    private fun onCancel() {
        entry.clear(); first.clear(); current.clear()
        if (mode != null) {
            finish()
        } else {
            choosePhase()
        }
    }

    private fun onOk() {
        if (entry.length < LockController.PIN_MIN) {
            message.text = "At least ${LockController.PIN_MIN} digits"
            return
        }
        when (phase) {
            Phase.ENTER -> submit { lock.unlock(it) }
            Phase.SETUP_FIRST -> {
                copyInto(first)
                enterPhase(Phase.SETUP_CONFIRM)
            }
            Phase.SETUP_CONFIRM -> {
                if (!entry.sameAs(first)) {
                    first.clear()
                    enterPhase(Phase.SETUP_FIRST)
                    message.text = "The PINs did not match"
                    return
                }
                first.clear()
                submit { lock.setup(it) }
            }
            Phase.CHANGE_CURRENT -> {
                copyInto(current)
                enterPhase(Phase.CHANGE_NEW)
            }
            Phase.CHANGE_NEW -> {
                copyInto(first)
                enterPhase(Phase.CHANGE_CONFIRM)
            }
            Phase.CHANGE_CONFIRM -> {
                if (!entry.sameAs(first)) {
                    first.clear()
                    enterPhase(Phase.CHANGE_NEW)
                    message.text = "The PINs did not match"
                    return
                }
                val old = current.copy()
                first.clear()
                current.clear()
                submit { new ->
                    try {
                        lock.changePin(old, new)
                    } finally {
                        old.fill(0)
                    }
                }
            }
            else -> {}
        }
    }

    // Moves the entered digits into target and clears the entry.
    private fun copyInto(target: PinBuffer) {
        target.clear()
        val bytes = entry.copy()
        for (b in bytes) target.push(b - '0'.code.toByte())
        bytes.fill(0)
        entry.clear()
        refreshDots()
    }

    // Runs a controller call with a copy of the entered PIN on the worker
    // thread; both copies are zeroed when it returns.
    private fun submit(call: (ByteArray) -> LockController.Result) {
        val pin = entry.copy()
        entry.clear()
        refreshDots()
        val before = phase
        enterPhase(Phase.BUSY)
        message.text = "Checking"
        worker.execute {
            val result = try {
                call(pin)
            } catch (e: Exception) {
                LockController.Result.Failed("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pin.fill(0)
            }
            main.post { onResult(before, result) }
        }
    }

    private fun onResult(before: Phase, r: LockController.Result) {
        if (isFinishing) return
        Log.i(TAG, "lock screen: ${before.name.lowercase()} ${r.javaClass.simpleName}")
        when (r) {
            is LockController.Result.Ok -> {
                if (mode == MODE_CHANGE) {
                    finish()
                } else {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
            is LockController.Result.WrongPin, is LockController.Result.Delayed -> {
                enterPhase(if (before == Phase.CHANGE_CURRENT || before == Phase.CHANGE_CONFIRM) Phase.CHANGE_CURRENT else Phase.ENTER)
                message.text = r.describe()
                countdown.run()
            }
            is LockController.Result.Unopenable -> {
                unopenableReason = r.reason
                enterPhase(Phase.UNOPENABLE)
            }
            is LockController.Result.Failed -> {
                enterPhase(if (before == Phase.BUSY) Phase.ENTER else before)
                if (phase == Phase.SETUP_CONFIRM) enterPhase(Phase.SETUP_FIRST)
                message.text = r.describe()
            }
        }
        status.text = statusLine()
    }

    private fun runErase() {
        eraseButton.isEnabled = false
        message.text = "Erasing"
        worker.execute {
            val rc = lock.erase()
            main.post {
                if (isFinishing) return@post
                message.text = "Vault erased (${NativeBridge.errorName(rc)})"
                mode = null
                choosePhase()
            }
        }
    }

    private fun runDiagnostics() {
        diag.text = ""
        val say: (String) -> Unit = { line ->
            Log.i(TAG, line)
            main.post { diag.append(line + "\n") }
        }
        worker.execute {
            try {
                Diagnostics.kdfBench(say)
                Diagnostics.hmacProbe(this, say)
            } catch (e: Exception) {
                say("diagnostics failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun onLockStateChanged(state: LockState) {
        if (isFinishing) return
        if (state == LockState.UNLOCKED && mode == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else if (state != LockState.UNLOCKED && mode != null) {
            finish()
        } else if (state == LockState.LOCKED && phase == Phase.BUSY) {
            choosePhase()
        }
    }

    override fun onStop() {
        entry.clear(); first.clear(); current.clear()
        refreshDots()
        main.removeCallbacks(countdown)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (this::pad.isInitialized && (phase == Phase.ENTER || phase == Phase.CHANGE_CURRENT)) countdown.run()
    }

    override fun onDestroy() {
        entry.clear(); first.clear(); current.clear()
        lock.removeListener(this)
        worker.shutdown()
        super.onDestroy()
    }
}
