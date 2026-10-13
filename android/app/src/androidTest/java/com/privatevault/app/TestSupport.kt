package com.privatevault.app

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

// A lifecycle the tests drive by hand so CameraX can bind without an activity.
class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

// Binds an ImageCapture use case to a test lifecycle on the main thread and
// takes pictures into memory. Test-only; the app uses CaptureActivity.
class TestCamera(private val owner: TestLifecycleOwner) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    lateinit var provider: ProcessCameraProvider
    lateinit var imageCapture: ImageCapture

    fun bind(lensFacing: Int = CameraSelector.LENS_FACING_BACK, highest: Boolean = true) {
        provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        if (highest) {
            builder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            )
        }
        imageCapture = builder.build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        instrumentation.runOnMainSync {
            owner.start()
            provider.bindToLifecycle(owner, selector, imageCapture)
            owner.resume()
        }
    }

    fun unbind() {
        instrumentation.runOnMainSync {
            provider.unbindAll()
            owner.destroy()
        }
    }

    class Captured(val proxy: ImageProxy?, val error: ImageCaptureException?)

    // Takes one picture; the proxy is delivered on executor and must be closed by the caller.
    fun take(executor: Executor, timeoutSeconds: Long = 30): Captured {
        val latch = CountDownLatch(1)
        var proxy: ImageProxy? = null
        var error: ImageCaptureException? = null
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                proxy = image
                latch.countDown()
            }

            override fun onError(exception: ImageCaptureException) {
                error = exception
                latch.countDown()
            }
        })
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw AssertionError("capture timed out")
        }
        return Captured(proxy, error)
    }

    companion object {
        fun isJpeg(p: ImageProxy): Boolean = p.format == ImageFormat.JPEG
    }
}
