package com.privatevault.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
// Video mode (docs/PHASE3E_DESIGN.md Section 4.1) records through the
// process-scoped VideoRecorder with the preview kept live; the shutter
// becomes Record and Stop, an elapsed label sits over the preview. Audio is
// on by default with a Mute toggle that resets whenever video mode opens;
// the microphone is asked for once per process on entering video mode, and
// a denial gives recordings without audio and a Microphone button for the
// retry (docs/PHASE3F_DESIGN.md Section 4.1). FLAG_SECURE
// keeps the preview out of screenshots, screen recordings, and the recents
// thumbnail. Scripted actions (debug use) arrive in the "actions" extra and
// run once the camera is bound.
class CaptureActivity : ComponentActivity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        const val EXTRA_ACTIONS = "actions"
        private const val PERMISSION_REQUEST = 1
        private const val AUDIO_PERMISSION_REQUEST = 2
        private const val TICK_MS = 200L
        // The microphone is asked for once per process (D-048).
        @Volatile private var audioAskedThisProcess = false
    }

    @Volatile private var audioPermissionLatch: CountDownLatch? = null
    private lateinit var muteButton: Button
    private lateinit var micButton: Button
    // Audio off for the next recording; reset whenever video mode opens.
    var muted = false
        private set
    var audioDenied = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private lateinit var previewView: PreviewView
    private lateinit var previewHost: FrameLayout
    private lateinit var shutter: Button
    private lateinit var grant: Button
    private lateinit var flip: Button
    private lateinit var modeButton: Button
    private lateinit var elapsedLabel: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var session: CaptureSession
    private lateinit var recorder: VideoRecorder
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
    private val ticker = Runnable { tick() }

    private val lock: LockController get() = PvApp.of(this).lock
    private var started = false

    // Video mode: the shutter records; no ImageCapture is bound.
    var videoMode = false
        private set
    val isRecording: Boolean get() = recorder.isRecording

    // Routes to the lock screen unless the vault is unlocked. Returns true
    // when this activity is finishing.
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
        session = PvApp.of(this).captureSession
        recorder = PvApp.of(this).videoRecorder
        vaults = PvApp.of(this).vaultSession

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewView = PreviewView(this)
        previewHost = FrameLayout(this)
        previewHost.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        elapsedLabel = TextView(this).apply {
            text = "● 0:00"
            setTextColor(Color.RED)
            setBackgroundColor(0x80000000.toInt())
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        previewHost.addView(elapsedLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(previewHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shutter = Button(this).apply {
            text = "Capture"
            isEnabled = false
            setOnClickListener { if (videoMode) toggleRecording() else capture { } }
        }
        modeButton = Button(this).apply {
            text = "Video"
            setOnClickListener { setVideoMode(!videoMode) }
        }
        val lockButton = Button(this).apply {
            text = "Lock"
            setOnClickListener { lock.requestLock("manual") }
        }
        grant = Button(this).apply {
            text = "Grant camera"
            visibility = View.GONE
            setOnClickListener { requestCamera() }
        }
        muteButton = Button(this).apply {
            text = "Mute"
            visibility = View.GONE
            setOnClickListener { setMuted(!muted) }
        }
        micButton = Button(this).apply {
            text = "Microphone"
            visibility = View.GONE
            setOnClickListener { askAudioAgain() }
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
        buttons.addView(modeButton)
        buttons.addView(muteButton)
        buttons.addView(flip)
        buttons.addView(lockButton)
        buttons.addView(grant)
        buttons.addView(micButton)
        root.addView(buttons)
        status = TextView(this).apply { setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(status) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        for (line in session.addListener(listener)) status.append(line + "\n")
        pendingActions = intent?.getStringExtra(EXTRA_ACTIONS)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

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
        started = true
        if (routeIfLocked()) return
        orientationListener?.enable()
        if (recorder.isRecording) {
            showRecording(true)
        } else if (hasCameraPermission() && provider != null && camera == null) {
            bindCamera()
        }
    }

    override fun onStop() {
        started = false
        orientationListener?.disable()
        main.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        lock.removeListener(this)
        if (this::session.isInitialized) session.removeListener(listener)
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

    // Asks for the microphone and waits for the answer; used by the Stage 3F
    // audio probes on the script thread.
    private fun ensureAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val latch = CountDownLatch(1)
        audioPermissionLatch = latch
        main.post { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST) }
        latch.await(120, TimeUnit.SECONDS)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        say("audio: permission ${if (granted) "granted" else "denied"}")
        return granted
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            audioDenied = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            say("audio: permission ${if (audioDenied) "denied" else "granted"}")
            updateAudioLabels()
            audioPermissionLatch?.countDown()
            return
        }
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

    // Photo mode binds the preview and ImageCapture; video mode the preview
    // only (the recorder rebinds with its effect while recording).
    private fun bindUseCases(p: ProcessCameraProvider) {
        if (recorder.isRecording) return
        p.unbindAll()
        camera = null
        imageCapture = null
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
        try {
            val cam = if (videoMode) {
                p.bindToLifecycle(this, selector, preview)
            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setTargetRotation(@Suppress("DEPRECATION") windowManager.defaultDisplay.rotation)
                    .build()
                imageCapture = capture
                p.bindToLifecycle(this, selector, preview, capture)
            }
            camera = cam
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
            if (recorder.isRecording) recorder.stop("CAMERA_ERROR")
            maybeRunScript()
            return
        }
        when (state.type) {
            CameraState.Type.OPEN -> {
                cameraError = null
                cameraReady = true
                shutter.isEnabled = true
                val res = imageCapture?.resolutionInfo?.resolution
                say("camera open, facing=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"}, mode=${if (videoMode) "video" else "photo"}, capture resolution=${res?.width}x${res?.height}")
                maybeRunScript()
            }
            CameraState.Type.CLOSED, CameraState.Type.CLOSING -> {
                cameraReady = false
                if (!recorder.isRecording) shutter.isEnabled = false
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

    // Switches between photo and video mode; refused while recording. Video
    // mode starts with audio on and asks for the microphone once per process.
    fun setVideoMode(video: Boolean) {
        if (recorder.isRecording || video == videoMode) return
        videoMode = video
        modeButton.text = if (video) "Photo" else "Video"
        if (video) {
            muted = false
            muteButton.text = "Mute"
            askAudioOnce()
        }
        updateAudioLabels()
        provider?.let { bindUseCases(it) }
    }

    fun setMuted(on: Boolean) {
        if (recorder.isRecording) return
        muted = on
        muteButton.text = if (on) "Unmute" else "Mute"
        say("audio: ${if (on) "muted" else "on"} for the next recording")
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askAudioOnce() {
        if (hasAudioPermission()) {
            audioDenied = false
            return
        }
        audioDenied = true
        if (audioAskedThisProcess) return
        audioAskedThisProcess = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
    }

    // The Microphone button: asks again while the system still shows the
    // dialog, else opens the app page of the system settings.
    private fun askAudioAgain() {
        if (hasAudioPermission()) {
            audioDenied = false
            updateAudioLabels()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun recordLabel(): String = if (!videoMode) "Capture" else if (audioDenied) "Record (no audio)" else "Record"

    private fun updateAudioLabels() {
        if (!recorder.isRecording) shutter.text = recordLabel()
        muteButton.visibility = if (videoMode) View.VISIBLE else View.GONE
        micButton.visibility = if (videoMode && audioDenied) View.VISIBLE else View.GONE
    }

    private fun toggleRecording() {
        if (recorder.isRecording) recorder.stop("user") else startRecording()
    }

    // Starts the recorder over this screen's preview. The result line shows
    // when the recording has ended and the camera is bound again.
    fun startRecording(): String? {
        val p = provider ?: return "CAMERA_NOT_READY"
        if (vaults.vault == null) return "VAULT_CLOSED"
        if (!videoMode) return "PHOTO_MODE"
        audioDenied = !hasAudioPermission()
        val refused = recorder.start(p, this, lensFacing, previewView.surfaceProvider, !muted) { result ->
            main.post { onRecordingDone(result) }
        }
        if (refused != null) {
            say("record refused: $refused")
            return refused
        }
        camera = null
        imageCapture = null
        showRecording(true)
        return null
    }

    private fun showRecording(on: Boolean) {
        shutter.text = if (on) "Stop" else recordLabel()
        shutter.isEnabled = on || cameraReady
        flip.isEnabled = !on
        modeButton.isEnabled = !on
        muteButton.isEnabled = !on
        micButton.visibility = if (videoMode && audioDenied) View.VISIBLE else View.GONE
        elapsedLabel.visibility = if (on) View.VISIBLE else View.GONE
        main.removeCallbacks(ticker)
        if (on) main.post(ticker)
    }

    private fun tick() {
        if (!recorder.isRecording) return
        val audio = when (recorder.currentAudio) {
            "on" -> "mic"
            "muted" -> "muted"
            else -> "no audio"
        }
        elapsedLabel.text = "● " + VideoItems.durationLabel(recorder.elapsedMs * 1000) + " " + audio
        main.postDelayed(ticker, TICK_MS)
    }

    private fun onRecordingDone(result: VideoRecorder.Result) {
        showRecording(false)
        if (isFinishing) return
        if (started && lock.state == LockState.UNLOCKED) provider?.let { bindUseCases(it) }
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
                name == "open" -> say("vault open: ${vaults.vault != null}, lock state ${lock.state}")
                name == "lock" -> {
                    lock.lock("script")
                    say("lock state ${lock.state}")
                }
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
                // Heap investigation steps: a pause for a heap dump from the host, a forced collection.
                name.startsWith("wait=") -> Thread.sleep(name.substringAfter('=').toLong())
                name == "gc" -> say(HeapActions.gcLine())
                name.startsWith("test_hook_slow=") -> {
                    // Debug variant only: slows every source read so a kill
                    // during encrypt can be forced from the host.
                    if (BuildConfig.DEBUG) NativeBridge.nativeSetTestHooks(name.substringAfter('=').toInt(), 0)
                    else say("test hooks are not in this build")
                }
                name == "test_hook_clear" -> if (BuildConfig.DEBUG) NativeBridge.nativeSetTestHooks(0, 0)
                // Stage 3E: a recording of the given length from the feature
                // path, and a forced stop reason for the next recording.
                name.startsWith("record=") -> recordAndWait(name.substringAfter('=').toLong())
                // Stage 3F: mute=<0|1> for the next recording (video mode opened first).
                name.startsWith("mute=") -> {
                    val on = name.substringAfter('=') == "1"
                    val latch = CountDownLatch(1)
                    main.post {
                        setVideoMode(true)
                        setMuted(on)
                        latch.countDown()
                    }
                    latch.await(5, TimeUnit.SECONDS)
                }
                name.startsWith("record_hook=") -> {
                    val hook = name.substringAfter('=')
                    if (hook == "SKIP_INDEX") recorder.testHooks.skipIndex = true else recorder.testHooks.stopAt = hook
                    say("record hook $hook set")
                }
                name == "video_probe" || name.startsWith("video_probe=") || name.startsWith("audio_") -> {
                    val runner = VideoProbes.get()
                    if (runner == null) say("$name: not available in this build")
                    else if (name.startsWith("audio_") && !ensureAudioPermission()) say("$name: skipped, no microphone permission")
                    else runner.run(name, this, imageCapture, camera, provider, lensFacing, vaults, ::say)
                }
                else -> say("unknown action $name")
            }
            say("[$name] done in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Exception) {
            say("[$name] FAIL: ${e.javaClass.name}: ${e.message}")
        }
    }

    // Records for clipMs through the feature path and waits for the result.
    private fun recordAndWait(clipMs: Long) {
        val switched = CountDownLatch(1)
        main.post {
            setVideoMode(true)
            switched.countDown()
        }
        switched.await(5, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cameraReady && cameraError == null && System.nanoTime() < deadline) Thread.sleep(50)
        val done = CountDownLatch(1)
        var refused: String? = null
        main.post {
            refused = startRecording()
            if (refused != null) done.countDown()
        }
        val t0 = System.nanoTime()
        while (!recorder.isRecording && refused == null && System.nanoTime() - t0 < 5_000_000_000L) Thread.sleep(20)
        if (refused != null) {
            say("record: refused $refused")
            return
        }
        Thread.sleep(clipMs)
        recorder.stop("script")
        val t1 = System.nanoTime()
        while (recorder.isRecording && System.nanoTime() - t1 < 60_000_000_000L) Thread.sleep(50)
        say("record: scripted ${recorder.lastResult?.line() ?: "no result"}")
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
