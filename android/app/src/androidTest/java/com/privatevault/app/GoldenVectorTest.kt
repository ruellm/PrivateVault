package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// T2: decrypts the committed golden vectors (packaged as test assets) and
// writes a vector produced on Android into app-private storage for the
// Windows side to decrypt (docs/PHASE1_PROGRESS.md, Milestone E).
@RunWith(AndroidJUnit4::class)
class GoldenVectorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    @Test
    fun committedVectorsDecrypt() {
        val dir = File(context.filesDir, "golden").also { it.deleteRecursively(); it.mkdirs() }
        val manifest = assets.open("manifest.txt").bufferedReader().readLines()
        var count = 0
        for (line in manifest) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(Regex("\\s+"))
            assertEquals(5, parts.size)
            val (name, lengthStr, chunkStr, hashHex, idHex) = parts
            val file = File(dir, "$name.pvo")
            assets.open("$name.pvo").use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash = ByteArray(NativeBridge.HASH_BYTES)
            val len = LongArray(1)
            val rc = NativeBridge.nativeDecryptFile(
                file.absolutePath, TestKeys.VMK, NativeBridge.fromHex(idHex), hash, len
            )
            assertEquals("$name: ${NativeBridge.errorName(rc)}", NativeBridge.OK, rc)
            assertEquals(lengthStr.toLong(), len[0])
            assertEquals(hashHex, NativeBridge.hex(hash))
            val expect = ByteArray(NativeBridge.HASH_BYTES)
            NativeBridge.nativeHashDeterministic(TestKeys.SEED, len[0], expect)
            assertTrue(expect.contentEquals(hash))
            assertEquals(
                NativeBridge.ERR_WRONG_VMK,
                NativeBridge.nativeDecryptFile(file.absolutePath, TestKeys.OTHER_VMK, null, hash, len)
            )
            chunkStr.toInt()
            count++
        }
        assertTrue(count >= 6)
        dir.deleteRecursively()
    }

    @Test
    fun exportVectorForWindows() {
        val dir = File(context.filesDir, "pv_export").also { it.deleteRecursively(); it.mkdirs() }
        val specs = listOf("android_empty" to 0L, "android_three_chunks" to 3L * 4096 + 100,
            "android_default_chunk" to 70_000L)
        val manifest = StringBuilder("# name length chunk_size blake2b(plaintext) object_id\n")
        for ((name, length) in specs) {
            val chunk = if (name == "android_default_chunk") 65536 else 4096
            val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
            val file = File(dir, "$name.pvo")
            val rc = NativeBridge.nativeEncryptSampleFile(
                file.absolutePath, TestKeys.VMK, TestKeys.SEED, length, chunk, id
            )
            assertEquals(NativeBridge.OK, rc)
            val hash = ByteArray(NativeBridge.HASH_BYTES)
            NativeBridge.nativeHashDeterministic(TestKeys.SEED, length, hash)
            // Local check before export.
            val back = ByteArray(NativeBridge.HASH_BYTES)
            val len = LongArray(1)
            assertEquals(NativeBridge.OK, NativeBridge.nativeDecryptFile(file.absolutePath, TestKeys.VMK, id, back, len))
            assertTrue(hash.contentEquals(back))
            manifest.append("$name $length $chunk ${NativeBridge.hex(hash)} ${NativeBridge.hex(id)}\n")
        }
        File(dir, "manifest.txt").writeText(manifest.toString())
        Log.i("PrivateVaultTest", "exported vectors to ${dir.absolutePath}")
    }
}
