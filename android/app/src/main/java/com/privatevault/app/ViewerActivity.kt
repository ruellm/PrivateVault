package com.privatevault.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// One photo, fit to the screen, zoom to 2x, with Delete behind an in-activity
// confirmation (docs/PHASE3B_DESIGN.md Sections 4 and 6). The bitmap is
// decoded at the view size on the viewer thread and erased when the
// activity stops or the vault locks. Not exported; the only extra is the
// object ID.
class ViewerActivity : Activity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        const val EXTRA_ID = "id"
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock: LockController get() = PvApp.of(this).lock
    private val vaults: VaultSession get() = PvApp.of(this).vaultSession
    private lateinit var photo: PhotoView
    private lateinit var label: TextView
    private lateinit var status: TextView
    private lateinit var confirm: LinearLayout
    private lateinit var deleteButton: Button
    private var id: ByteArray = ByteArray(0)
    private var hex = ""
    private var started = false
    private var generation = 0

    // Tests read these.
    var lastResult: PhotoLoader.Loaded? = null
        private set
    val hasBitmap: Boolean get() = this::photo.isInitialized && photo.hasPhoto

    private fun routeIfLocked(): Boolean {
        if (lock.state == LockState.UNLOCKED) return false
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return true
    }

    override fun onLockStateChanged(state: LockState) {
        if (state != LockState.UNLOCKED && !isFinishing) {
            dropPhoto()
            routeIfLocked()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (routeIfLocked()) return
        hex = intent?.getStringExtra(EXTRA_ID) ?: ""
        if (hex.length != 32 || hex.any { it !in "0123456789abcdef" }) {
            finish()
            return
        }
        id = NativeBridge.fromHex(hex)
        lock.addListener(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val frame = FrameLayout(this)
        photo = PhotoView(this)
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "loading"
        }
        frame.addView(photo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })
        deleteButton = Button(this).apply {
            text = "Delete"
            setOnClickListener { confirm.visibility = View.VISIBLE }
        }
        buttons.addView(deleteButton)
        root.addView(buttons)
        confirm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(this@ViewerActivity).apply {
                text = "Delete this photo? The object is removed from the vault."
                setTextColor(Color.WHITE)
            })
            val row = LinearLayout(this@ViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this@ViewerActivity).apply {
                text = "Delete"
                setOnClickListener { runDelete() }
            })
            row.addView(Button(this@ViewerActivity).apply {
                text = "Cancel"
                setOnClickListener { confirm.visibility = View.GONE }
            })
            addView(row)
        }
        root.addView(confirm)
        status = TextView(this).apply { setTextColor(Color.LTGRAY) }
        root.addView(status)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (routeIfLocked()) return
        photo.post { loadPhoto() }
    }

    override fun onStop() {
        started = false
        generation++
        dropPhoto()
        super.onStop()
    }

    override fun onDestroy() {
        lock.removeListener(this)
        super.onDestroy()
    }

    private fun dropPhoto() {
        if (this::photo.isInitialized) PhotoLoader.dispose(photo.takePhoto())
    }

    private fun loadPhoto() {
        if (!started) return
        val w = if (photo.width > 0) photo.width else resources.displayMetrics.widthPixels
        val h = if (photo.height > 0) photo.height else resources.displayMetrics.heightPixels
        generation++
        val g = generation
        label.text = "loading"
        label.visibility = View.VISIBLE
        PhotoLoader.executor.execute {
            val v = vaults.vault
            val result = if (v == null) PhotoLoader.Loaded.Cancelled else PhotoLoader.load(v, id, w, h)
            main.post { onLoaded(g, result) }
        }
    }

    private fun onLoaded(g: Int, result: PhotoLoader.Loaded) {
        if (g != generation || !started || lock.state != LockState.UNLOCKED) {
            if (result is PhotoLoader.Loaded.Photo) PhotoLoader.dispose(result.bitmap)
            return
        }
        lastResult = result
        Log.i(TAG, "viewer: $hex ${result.label()}")
        if (result is PhotoLoader.Loaded.Photo) {
            PhotoLoader.dispose(photo.takePhoto())
            photo.setPhoto(result.bitmap, result.turnDegrees)
            label.visibility = View.GONE
            status.text = "${result.bitmap.width}x${result.bitmap.height} decoded, turn ${result.turnDegrees}"
        } else {
            label.text = result.label()
            label.visibility = View.VISIBLE
            status.text = ""
        }
    }

    private fun runDelete() {
        confirm.visibility = View.GONE
        deleteButton.isEnabled = false
        PhotoLoader.executor.execute {
            val v = vaults.vault
            val rc = v?.deleteObject(id) ?: NativeBridge.ERR_CANCELLED
            main.post {
                Log.i(TAG, "viewer: delete $hex ${NativeBridge.errorName(rc)}")
                if (isFinishing) return@post
                status.text = when (rc) {
                    NativeBridge.OK -> "deleted"
                    NativeBridge.ERR_NOT_FOUND -> "already gone"
                    else -> "delete failed: ${NativeBridge.errorName(rc)}"
                }
                if (rc == NativeBridge.OK || rc == NativeBridge.ERR_NOT_FOUND) {
                    dropPhoto()
                    finish()
                } else {
                    deleteButton.isEnabled = true
                }
            }
        }
    }
}
