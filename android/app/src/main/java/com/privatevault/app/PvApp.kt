package com.privatevault.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File

// Process-scoped owners. The open vault and the capture pipeline live here so
// an activity recreation (rotation, backgrounding) does not close the vault or
// cancel an encrypt in progress.
class PvApp : Application() {
    lateinit var vaultSession: VaultSession
        private set
    lateinit var captureSession: CaptureSession
        private set
    lateinit var lock: LockController
        private set

    override fun onCreate() {
        super.onCreate()
        NativeBridge.nativeInit()
        vaultSession = VaultSession(this)
        captureSession = CaptureSession(this, vaultSession)
        lock = LockController(this, vaultSession, captureSession)
        lock.start()
        // Lock triggers (docs/PHASE3A_DESIGN.md Section 5): the whole app
        // going to the background, and the screen turning off. Background
        // means no activity has been started for 700 ms after the last one
        // stopped; the delay lets a rotation or a permission dialog pass.
        // Counted here rather than through ProcessLifecycleOwner, whose
        // androidx.startup initializer the manifest removes (D-013, D-020).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private val handler = Handler(Looper.getMainLooper())
            private val check = Runnable { if (started == 0) lock.requestLock("background") }
            override fun onActivityStarted(activity: Activity) {
                started++
                handler.removeCallbacks(check)
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0) handler.postDelayed(check, BACKGROUND_DELAY_MS)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) lock.requestLock("screen off")
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOff, filter)
        }
        if (BuildConfig.DEBUG) {
            // The video prototype lives in the debug source set only.
            try {
                Class.forName("com.privatevault.app.DebugVideoProbes").getDeclaredConstructor().newInstance()
            } catch (e: ClassNotFoundException) {
            }
        }
    }

    companion object {
        const val BACKGROUND_DELAY_MS = 700L
        fun of(context: Context): PvApp = context.applicationContext as PvApp
    }
}

// The one open vault of the process.
class VaultSession(private val context: Context) {
    val provider: KeystoreKeyProvider by lazy { KeystoreKeyProvider(context) }
    private var current: Vault? = null

    val vault: Vault?
        @Synchronized get() = current

    val filesDir: File get() = context.filesDir

    // Replaces the open vault with one opened by the caller: the lock
    // controller after an unlock, or a test.
    @Synchronized
    fun adopt(v: Vault) {
        current?.close()
        current = v
    }

    @Synchronized
    fun close() {
        current?.close()
        current = null
    }
}
