package com.privatevault.app

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider

// Seam for the video feasibility prototype, which exists only in the debug
// source set. The release variant has no implementation and reports the
// action as unavailable.
interface VideoProbeRunner {
    fun run(
        name: String, activity: CaptureActivity, imageCapture: ImageCapture?, camera: Camera?,
        provider: ProcessCameraProvider?, lensFacing: Int, vaults: VaultSession, say: (String) -> Unit
    )
}

object VideoProbes {
    @Volatile private var runner: VideoProbeRunner? = null

    fun install(r: VideoProbeRunner) {
        runner = r
    }

    fun get(): VideoProbeRunner? = runner

    @Suppress("UNUSED_PARAMETER")
    fun available(context: Context): Boolean = runner != null
}
