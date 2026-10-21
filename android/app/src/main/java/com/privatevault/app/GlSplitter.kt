package com.privatevault.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// One camera stream drawn to several surfaces (docs/PHASE3E_DESIGN.md
// Section 4.2): the camera renders into a SurfaceTexture owned here,
// and every frame is drawn once per output (the on-screen viewfinder, the
// encoder input surface) through OpenGL ES 2 on an own thread. Each output
// has its own texture transform, so the viewfinder can be turned upright
// while the encoder receives the frame as the camera delivered it. The
// encoder surface gets the camera timestamp as its presentation time.
// Frame counts per output are the measurement; no frame is read back.
class GlSplitter(private val say: (String) -> Unit) : SurfaceTexture.OnFrameAvailableListener {

    class Output(
        val name: String,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        // Turns the camera texture matrix into the one this output draws
        // with; null skips the frame for this output.
        val adjust: (FloatArray) -> FloatArray?,
        val presentationTime: Boolean
    ) {
        val frames = AtomicInteger(0)
        @Volatile var lastError = 0
    }

    private val thread = HandlerThread("pv-gl-split").also { it.start() }
    val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val outputs = ArrayList<Output>()
    private val texMatrix = FloatArray(16)
    val cameraFrames = AtomicInteger(0)
    @Volatile var firstFrameNs = 0L
    @Volatile var lastFrameNs = 0L
    @Volatile var released = false

    private val vertices: java.nio.FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }
    private val texCoords: java.nio.FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        position(0)
    }

    // Runs a block on the GL thread and waits for it.
    private fun <T> sync(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("gl thread did not answer")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // Makes the EGL context and the camera input surface at the given size.
    fun start(width: Int, height: Int): Surface = sync {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) throw IllegalStateException("no EGL config")
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed ${EGL14.eglGetError()}")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) throw IllegalStateException("eglMakeCurrent failed ${EGL14.eglGetError()}")
        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener(this, handler)
        surfaceTexture = st
        val s = Surface(st)
        inputSurface = s
        say("gl_split: context ready, input ${width}x$height, vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}")
        s
    }

    // Adds a window surface to draw every frame into.
    fun addOutput(name: String, surface: Surface, width: Int, height: Int, presentationTime: Boolean, adjust: (FloatArray) -> FloatArray?): Output = sync {
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface($name) failed ${EGL14.eglGetError()}")
        val o = Output(name, surface, egl, width, height, adjust, presentationTime)
        outputs.add(o)
        say("gl_split: output $name ${width}x$height added")
        o
    }

    fun removeOutput(o: Output) = sync {
        if (outputs.remove(o)) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, o.eglSurface)
            say("gl_split: output ${o.name} removed after ${o.frames.get()} frames")
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        val now = System.nanoTime()
        if (cameraFrames.getAndIncrement() == 0) firstFrameNs = now
        lastFrameNs = now
        try {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            if (cameraFrames.get() == 1) {
                // The camera transform of this device, once per stream.
                say("gl_split: texture matrix ${texMatrix.joinToString(",") { "%.2f".format(it) }}")
            }
            val ts = st.timestamp
            for (o in outputs) {
                val matrix = o.adjust(texMatrix.copyOf()) ?: continue
                if (!EGL14.eglMakeCurrent(display, o.eglSurface, o.eglSurface, context)) {
                    o.lastError = EGL14.eglGetError()
                    continue
                }
                GLES20.glViewport(0, 0, o.width, o.height)
                draw(matrix)
                if (o.presentationTime) EGLExt.eglPresentationTimeANDROID(display, o.eglSurface, ts)
                if (!EGL14.eglSwapBuffers(display, o.eglSurface)) {
                    o.lastError = EGL14.eglGetError()
                    continue
                }
                o.frames.incrementAndGet()
            }
        } catch (e: Exception) {
            say("gl_split: frame failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun draw(matrix: FloatArray) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    // Frames per second the camera delivered, rounded; 0 when too few.
    fun cameraFps(): Int {
        val n = cameraFrames.get()
        if (n < 5 || lastFrameNs <= firstFrameNs) return 0
        return Math.round((n - 1) * 1_000_000_000.0 / (lastFrameNs - firstFrameNs)).toInt()
    }

    fun release() {
        if (released) return
        released = true
        try {
            sync {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                for (o in outputs) EGL14.eglDestroySurface(display, o.eglSurface)
                outputs.clear()
                inputSurface?.release()
                surfaceTexture?.release()
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        } catch (e: Exception) {
            say("gl_split: release: ${e.javaClass.simpleName}: ${e.message}")
        }
        thread.quitSafely()
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("program link: ${GLES20.glGetProgramInfoLog(p)}")
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // A texture matrix that turns the picture by the given degrees about
        // its centre, applied after the camera matrix.
        fun rotated(camera: FloatArray, degrees: Int): FloatArray {
            if (degrees == 0) return camera
            val r = FloatArray(16)
            Matrix.setIdentityM(r, 0)
            Matrix.translateM(r, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(r, 0, degrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(r, 0, -0.5f, -0.5f, 0f)
            val out = FloatArray(16)
            Matrix.multiplyMM(out, 0, camera, 0, r, 0)
            return out
        }
    }
}
