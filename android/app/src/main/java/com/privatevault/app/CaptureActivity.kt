package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Capture screen: preview, shutter, status. Each photo goes straight from the
// CameraX image buffer into an encrypted vault object through CaptureSession.
// FLAG_SECURE keeps the preview out of screenshots, screen recordings, and the
// recents thumbnail. Scripted actions (debug use) arrive in the "actions"
// extra and run once the camera is bound.
class CaptureActivity : ComponentActivity() {

    companion object {
        const val TAG = "PrivateVault"
        const val EXTRA_ACTIONS = "actions"
        private const val PERMISSION_REQUEST = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var previewView: PreviewView
    private lateinit var shutter: Button
    private lateinit var grant: Button
    private lateinit var flip: Button
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var session: CaptureSession
    private lateinit var vaults: VaultSession
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraReady = false
    private var cameraError: String? = null
    private var orientationListener: OrientationEventListener? = null
    private val listener: (String) -> Unit = { line -> main.post { show(line) } }
    private val scriptStarted = AtomicBoolean(false)
    private var pendingActions: List<String> = emptyList()
    private val scriptThread by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        session = PvApp.of(this).captureSession
        vaults = PvApp.of(this).vaultSession

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewView = PreviewView(this)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shutter = Button(this).apply {
            text = "Capture"
            isEnabled = false
            setOnClickListener { capture { } }
        }
        grant = Button(this).apply {
            text = "Grant camera"
            visibility = View.GONE
            setOnClickListener { requestCamera() }
        }
        flip = Button(this).apply {
            text = "Flip"
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
                bindCamera()
            }
        }
        buttons.addView(shutter)
        buttons.addView(flip)
        buttons.addView(grant)
        root.addView(buttons)
        status = TextView(this).apply { setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(status) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        for (line in session.addListener(listener)) status.append(line + "\n")
        pendingActions = intent?.getStringExtra(EXTRA_ACTIONS)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        if (vaults.vault == null) {
            try {
                say(vaults.openOrCreate())
            } catch (e: Exception) {
                say("vault open failed: ${e.message}")
            }
        }
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation >= 225 -> Surface.ROTATION_90
                    orientation >= 135 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_270
                }
                imageCapture?.targetRotation = rotation
            }
        }
        if (hasCameraPermission()) bindCamera() else requestCamera()
    }

    override fun onStart() {
        super.onStart()
        orientationListener?.enable()
        if (hasCameraPermission() && provider != null && camera == null) bindCamera()
    }

    override fun onStop() {
        orientationListener?.disable()
        super.onStop()
    }

    override fun onDestroy() {
        session.removeListener(listener)
        scriptThread.shutdownNow()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        main.post { show(line) }
    }

    private fun show(line: String) {
        status.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            grant.visibility = View.GONE
            bindCamera()
        } else {
            say("camera: PERMISSION_DENIED")
            grant.visibility = View.VISIBLE
            shutter.isEnabled = false
            cameraError = "PERMISSION_DENIED"
            maybeRunScript()
        }
    }

    private fun bindCamera() {
        if (!hasCameraPermission()) {
            say("camera: PERMISSION_DENIED")
            cameraError = "PERMISSION_DENIED"
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                bindUseCases(p)
            } catch (e: Exception) {
                cameraError = "${e.javaClass.simpleName}: ${e.message}"
                say("camera bind failed: $cameraError")
                shutter.isEnabled = false
                maybeRunScript()
            }
        }, mainExecutor)
    }

    private fun bindUseCases(p: ProcessCameraProvider) {
        p.unbindAll()
        camera = null
        cameraReady = false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!p.hasCamera(selector)) {
            cameraError = "NO_CAMERA(facing=$lensFacing)"
            say("camera: $cameraError")
            shutter.isEnabled = false
            maybeRunScript()
            return
        }
        val preview = Preview.Builder().build()
        preview.surfaceProvider = previewView.surfaceProvider
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setTargetRotation(@Suppress("DEPRECATION") windowManager.defaultDisplay.rotation)
            .build()
        try {
            val cam = p.bindToLifecycle(this, selector, preview, capture)
            camera = cam
            imageCapture = capture
            cam.cameraInfo.cameraState.observe(this) { state -> onCameraState(state) }
        } catch (e: Exception) {
            cameraError = "${e.javaClass.simpleName}: ${e.message}"
            say("camera bind failed: $cameraError")
            shutter.isEnabled = false
            maybeRunScript()
        }
    }

    private fun onCameraState(state: CameraState) {
        val err = state.error
        if (err != null) {
            cameraError = cameraStateErrorName(err.code)
            say("camera: $cameraError (${state.type})")
            shutter.isEnabled = false
            if (state.type == CameraState.Type.CLOSED) cameraReady = false
            maybeRunScript()
            return
        }
        when (state.type) {
            CameraState.Type.OPEN -> {
                cameraError = null
                cameraReady = true
                shutter.isEnabled = true
                val res = imageCapture?.resolutionInfo?.resolution
                say("camera open, facing=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"}, capture resolution=${res?.width}x${res?.height}")
                maybeRunScript()
            }
            CameraState.Type.CLOSED, CameraState.Type.CLOSING -> {
                cameraReady = false
                shutter.isEnabled = false
            }
            else -> {}
        }
    }

    private fun cameraStateErrorName(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "ERROR_OTHER_RECOVERABLE_ERROR"
        CameraState.ERROR_STREAM_CONFIG -> "ERROR_STREAM_CONFIG"
        CameraState.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "ERROR_CAMERA_FATAL_ERROR"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "ERROR_DO_NOT_DISTURB_MODE_ENABLED"
        else -> "ERROR_$code"
    }

    private fun captureErrorName(code: Int): String = when (code) {
        ImageCapture.ERROR_CAMERA_CLOSED -> "ERROR_CAMERA_CLOSED"
        ImageCapture.ERROR_CAPTURE_FAILED -> "ERROR_CAPTURE_FAILED"
        ImageCapture.ERROR_FILE_IO -> "ERROR_FILE_IO"
        ImageCapture.ERROR_INVALID_CAMERA -> "ERROR_INVALID_CAMERA"
        else -> "ERROR_UNKNOWN($code)"
    }

    // Starts one capture. The callback runs when the object is complete or the
    // capture failed, with the status name.
    private fun capture(done: (String) -> Unit) {
        val ic = imageCapture
        if (vaults.vault == null) {
            say("capture refused: VAULT_CLOSED"); done("VAULT_CLOSED"); return
        }
        if (ic == null || !cameraReady) {
            val why = cameraError ?: "CAMERA_NOT_READY"
            say("capture refused: $why"); done(why); return
        }
        if (session.lowSpace()) {
            say("capture refused: LOW_STORAGE (free ${session.freeSpaceBytes() shr 20} MiB)")
            done("LOW_STORAGE"); return
        }
        if (!session.reserve()) {
            say("capture refused: QUEUE_FULL"); done("QUEUE_FULL"); return
        }
        val shutterNanos = System.nanoTime()
        ic.takePicture(session.executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                session.submit(image, shutterNanos) { r -> done(r.status) }
            }

            override fun onError(exception: ImageCaptureException) {
                session.release()
                val name = captureErrorName(exception.imageCaptureError)
                say("capture failed: $name (${exception.message})")
                done(name)
            }
        })
    }

    // Scripted actions, run once per activity after the first camera state.
    // Each capture waits for its object before the next action starts.
    private fun maybeRunScript() {
        if (pendingActions.isEmpty() || !scriptStarted.compareAndSet(false, true)) return
        val actions = pendingActions
        scriptThread.execute {
            for (name in actions) {
                runAction(name)
            }
            say("[scripted] all actions finished")
        }
    }

    private fun runAction(name: String) {
        val t0 = System.nanoTime()
        try {
            when {
                name == "open" -> say(vaults.openOrCreate())
                name == "capture_photo" -> captureAndWait(1)
                name.startsWith("capture_photo_n=") -> captureAndWait(name.substringAfter('=').toInt())
                name == "verify_all" || name == "verify" -> ObjectActions.verifyAll(vaults, ::say)
                name == "list" -> ObjectActions.list(vaults, ::say)
                name == "leak_scan" -> for (l in LeakScan.run(this).lines()) say(l)
                name == "flip" -> {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                    rebindAndWait()
                }
                name == "memory" -> say(memoryLine())
                name.startsWith("test_hook_slow=") -> {
                    // Debug variant only: slows every source read so a kill
                    // during encrypt can be forced from the host.
                    if (BuildConfig.DEBUG) NativeBridge.nativeSetTestHooks(name.substringAfter('=').toInt(), 0)
                    else say("test hooks are not in this build")
                }
                name == "test_hook_clear" -> if (BuildConfig.DEBUG) NativeBridge.nativeSetTestHooks(0, 0)
                name == "video_probe" || name == "v2_probe" -> {
                    val runner = VideoProbes.get()
                    if (runner == null) say("$name: not available in this build")
                    else runner.run(name, this, imageCapture, camera, provider, lensFacing, vaults, ::say)
                }
                else -> say("unknown action $name")
            }
            say("[$name] done in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Exception) {
            say("[$name] FAIL: ${e.javaClass.name}: ${e.message}")
        }
    }

    // Java heap, native heap, and PSS in KiB, for the memory measurements.
    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val mi = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(mi)
        return "memory: java_used=${(rt.totalMemory() - rt.freeMemory()) shr 10}KiB " +
            "native_heap=${android.os.Debug.getNativeHeapAllocatedSize() shr 10}KiB total_pss=${mi.totalPss}KiB"
    }

    // Used by the video prototype (debug source set) to rebind after a probe.
    internal fun previewSurfaceProvider(): Preview.SurfaceProvider = previewView.surfaceProvider

    internal fun cameraProvider(): ProcessCameraProvider? = provider

    internal fun rebindAndWait() {
        val latch = CountDownLatch(1)
        cameraReady = false
        main.post {
            provider?.let { bindUseCases(it) }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cameraReady && cameraError == null && System.nanoTime() < deadline) Thread.sleep(50)
    }

    private fun captureAndWait(count: Int) {
        for (i in 1..count) {
            val latch = CountDownLatch(1)
            var outcome = "NO_CALLBACK"
            main.post {
                capture { s -> outcome = s; latch.countDown() }
            }
            if (!latch.await(60, TimeUnit.SECONDS)) outcome = "TIMEOUT"
            say("[capture $i/$count] $outcome")
        }
    }
}
