package com.privatevault.app

import android.app.Application
import android.content.Context
import java.io.File

// Process-scoped owners. The open vault and the capture pipeline live here so
// an activity recreation (rotation, backgrounding) does not close the vault or
// cancel an encrypt in progress.
class PvApp : Application() {
    lateinit var vaultSession: VaultSession
        private set
    lateinit var captureSession: CaptureSession
        private set

    override fun onCreate() {
        super.onCreate()
        NativeBridge.nativeInit()
        vaultSession = VaultSession(this)
        captureSession = CaptureSession(this, vaultSession)
        if (BuildConfig.DEBUG) {
            // The video prototype lives in the debug source set only.
            try {
                Class.forName("com.privatevault.app.DebugVideoProbes").getDeclaredConstructor().newInstance()
            } catch (e: ClassNotFoundException) {
            }
        }
    }

    companion object {
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

    // Opens the vault, creating it on first use. Returns a line for the log.
    @Synchronized
    fun openOrCreate(): String {
        current?.close()
        current = null
        val existed = Vault.exists(filesDir)
        current = if (existed) Vault.open(filesDir, provider) else Vault.create(filesDir, provider)
        return if (existed) "opened existing vault" else "created new vault"
    }

    // Replaces the open vault with one opened by the caller (tests, wrong-key checks).
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
