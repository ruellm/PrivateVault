package com.privatevault.app

import android.content.Context
import android.graphics.ImageFormat
import android.os.Debug
import android.os.StatFs
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Takes captured images from CameraX and turns each one into a vault object.
// Encryption runs on a single process-scoped thread. At most MAX_PENDING
// images wait for that thread; a capture is only started when a slot was
// reserved first, so no image is held without a bound.
class CaptureSession(private val context: Context, private val vaults: VaultSession) {

    companion object {
        const val TAG = "PrivateVault"
        const val MAX_PENDING = 2
        // Captures are refused below this much free space in filesDir.
        const val LOW_SPACE_BYTES = 64L shl 20
    }

    class Result(
        val idHex: String?,
        val status: String,
        val jpegLen: Int,
        val objectLen: Long,
        val rotation: Int,
        val width: Int,
        val height: Int,
        val bufferPath: String,
        val encryptMs: Long,
        val totalMs: Long,
        val verify: String,
        val payloadHashHex: String?,
        val nativeHeapPeak: Long,
        val javaHeapPeak: Long
    ) {
        val ok: Boolean get() = status == "OK"
        fun line(): String = if (ok) {
            "captured $idHex jpeg=$jpegLen object=$objectLen rotation=$rotation ${width}x$height " +
                "buffer=$bufferPath encrypt=${encryptMs}ms total=${totalMs}ms verify=$verify " +
                "native_heap_peak=$nativeHeapPeak java_heap_peak=$javaHeapPeak"
        } else {
            "capture failed: $status"
        }
    }

    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pv-encrypt") }
    private val pending = AtomicInteger(0)
    private val history = ArrayList<String>()
    private val listeners = ArrayList<(String) -> Unit>()
    @Volatile var lastResult: Result? = null
        private set
    @Volatile var captureCount = 0
        private set

    val pendingCount: Int get() = pending.get()

    // Reserves a queue slot before takePicture. False means QUEUE_FULL.
    fun reserve(): Boolean {
        while (true) {
            val n = pending.get()
            if (n >= MAX_PENDING) return false
            if (pending.compareAndSet(n, n + 1)) return true
        }
    }

    fun release() {
        pending.decrementAndGet()
    }

    fun freeSpaceBytes(): Long = StatFs(vaults.filesDir.path).availableBytes

    fun lowSpace(): Boolean = freeSpaceBytes() < LOW_SPACE_BYTES

    @Synchronized
    fun addListener(l: (String) -> Unit): List<String> {
        listeners.add(l)
        return ArrayList(history)
    }

    @Synchronized
    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    fun say(line: String) {
        Log.i(TAG, line)
        val copy: List<(String) -> Unit>
        synchronized(this) {
            history.add(line)
            if (history.size > 200) history.removeAt(0)
            copy = ArrayList(listeners)
        }
        for (l in copy) l(line)
    }

    // Hands a captured image to the encrypt thread. The slot reserved for it
    // is released when the work is done. The proxy is closed on every path.
    fun submit(proxy: ImageProxy, shutterNanos: Long, onDone: (Result) -> Unit) {
        val createdMs = System.currentTimeMillis()
        executor.execute {
            val result = try {
                encryptOne(proxy, shutterNanos, createdMs)
            } finally {
                release()
            }
            lastResult = result
            if (result.ok) captureCount += 1
            say(result.line())
            onDone(result)
        }
    }

    private fun encryptOne(proxy: ImageProxy, shutterNanos: Long, createdMs: Long): Result {
        var jpegLen = 0
        var rotation = 0
        var width = 0
        var height = 0
        var bufferPath = "none"
        var stored: Vault.Stored? = null
        var preHash: ByteArray? = null
        var encryptMs = 0L
        var status = "OK"
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val javaBefore = usedJavaHeap()
        var nativePeak = nativeBefore
        var javaPeak = javaBefore
        val vault = vaults.vault
        try {
            if (vault == null) {
                status = "VAULT_CLOSED"
            } else if (proxy.format != ImageFormat.JPEG) {
                status = "BAD_FORMAT(${proxy.format})"
            } else {
                rotation = proxy.imageInfo.rotationDegrees
                width = proxy.width
                height = proxy.height
                val buffer = proxy.planes[0].buffer
                buffer.rewind()
                jpegLen = buffer.remaining()
                if (BuildConfig.DEBUG && buffer.isDirect) {
                    val h = ByteArray(NativeBridge.HASH_BYTES)
                    if (NativeBridge.nativeHashDirectBuffer(buffer, buffer.position(), jpegLen, h) == NativeBridge.OK) {
                        preHash = h
                    }
                }
                val meta = Vault.ItemMeta(NativeBridge.MEDIA_JPEG, rotation, width, height, createdMs)
                val sampler = PeakSampler()
                sampler.start()
                val t0 = System.nanoTime()
                try {
                    stored = vault.encryptItem(buffer, meta)
                } finally {
                    encryptMs = (System.nanoTime() - t0) / 1_000_000
                    sampler.finish()
                    nativePeak = maxOf(nativePeak, sampler.nativePeak)
                    javaPeak = maxOf(javaPeak, sampler.javaPeak)
                }
                bufferPath = stored!!.path.name
            }
        } catch (e: Vault.CoreException) {
            status = e.message ?: NativeBridge.errorName(e.code)
        } catch (e: Exception) {
            status = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                proxy.close()
            } catch (e: Exception) {
                Log.w(TAG, "ImageProxy.close: ${e.javaClass.simpleName}")
            }
        }
        val s = stored
        if (s == null || vault == null) {
            return Result(null, status, jpegLen, 0, rotation, width, height, bufferPath, encryptMs,
                (System.nanoTime() - shutterNanos) / 1_000_000, "skipped", null, nativePeak, javaPeak)
        }
        // Verify the new object the way the app always does: decrypt into a hash.
        val (rc, verified) = vault.decryptVerifyItem(s.id)
        val verify = when {
            rc != NativeBridge.OK -> NativeBridge.errorName(rc)
            verified == null -> "no result"
            verified.item == null -> "item ${NativeBridge.errorName(verified.itemStatus)}"
            verified.item.payloadLen != jpegLen.toLong() -> "payload_len mismatch ${verified.item.payloadLen}"
            verified.plaintextLen != s.plaintextLen -> "plaintext_len mismatch"
            preHash != null && !preHash.contentEquals(verified.payloadHash) -> "hash mismatch"
            else -> "ok"
        }
        val objectLen = vault.objectPath(s.id).length()
        val hashHex = if (BuildConfig.DEBUG) verified?.payloadHash?.let { NativeBridge.hex(it) } else null
        return Result(
            NativeBridge.hex(s.id), status, jpegLen, objectLen, rotation, width, height, bufferPath,
            encryptMs, (System.nanoTime() - shutterNanos) / 1_000_000, verify, hashHex, nativePeak, javaPeak
        )
    }

    private fun usedJavaHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    // Samples the heaps every 20 ms while an encrypt runs.
    private inner class PeakSampler : Thread("pv-peak") {
        @Volatile var nativePeak = 0L
        @Volatile var javaPeak = 0L
        @Volatile private var running = true
        override fun run() {
            while (running) {
                nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                javaPeak = maxOf(javaPeak, usedJavaHeap())
                try {
                    sleep(20)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
        fun finish() {
            running = false
            interrupt()
            try {
                join(200)
            } catch (e: InterruptedException) {
            }
        }
    }
}
