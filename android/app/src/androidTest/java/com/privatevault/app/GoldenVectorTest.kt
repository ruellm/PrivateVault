package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// T2: decrypts the committed golden vectors (packaged as test assets), plain
// and item-wrapped, and writes vectors produced on Android into app-private
// storage for the Windows side to decrypt (docs/PHASE1_PROGRESS.md Milestone
// E, docs/PHASE2_PROGRESS.md Milestone D).
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
        var items = 0
        for (line in manifest) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(Regex("\\s+"))
            assertTrue("columns: ${parts.size}", parts.size == 5 || parts.size == 11)
            val name = parts[0]
            val length = parts[1].toLong()
            val hashHex = parts[3]
            val idHex = parts[4]
            val isItem = parts.size == 11 && parts[5] != "-"
            val file = File(dir, "$name.pvo")
            assets.open("$name.pvo").use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash = ByteArray(NativeBridge.HASH_BYTES)
            if (!isItem) {
                val len = LongArray(1)
                val rc = NativeBridge.nativeDecryptFile(
                    file.absolutePath, TestKeys.VMK, NativeBridge.fromHex(idHex), hash, len
                )
                assertEquals("$name: ${NativeBridge.errorName(rc)}", NativeBridge.OK, rc)
                assertEquals(length, len[0])
                assertEquals(hashHex, NativeBridge.hex(hash))
                val expect = ByteArray(NativeBridge.HASH_BYTES)
                NativeBridge.nativeHashDeterministic(TestKeys.SEED, len[0], expect)
                assertTrue(expect.contentEquals(hash))
                // The item path classifies a plain vector as an unknown item.
                val info = LongArray(8)
                val status = IntArray(1)
                assertEquals(NativeBridge.OK, NativeBridge.nativeDecryptFileItem(
                    file.absolutePath, TestKeys.VMK, NativeBridge.fromHex(idHex), hash, info, status
                ))
                assertEquals(NativeBridge.ERR_NOT_ITEM, status[0])
                assertEquals(length, info[7])
            } else {
                val info = LongArray(8)
                val status = IntArray(1)
                val rc = NativeBridge.nativeDecryptFileItem(
                    file.absolutePath, TestKeys.VMK, NativeBridge.fromHex(idHex), hash, info, status
                )
                assertEquals("$name: ${NativeBridge.errorName(rc)}", NativeBridge.OK, rc)
                assertEquals("$name: item ${NativeBridge.errorName(status[0])}", NativeBridge.OK, status[0])
                assertEquals(parts[5].toLong(), info[0])   // media_type
                assertEquals(parts[6].toLong(), info[1])   // rotation
                assertEquals(parts[7].toLong(), info[2])   // width
                assertEquals(parts[8].toLong(), info[3])   // height
                assertEquals(parts[9].toLong(), info[4])   // created_unix_ms
                assertEquals(parts[10].toLong(), info[5])  // payload_len
                assertEquals(48L, info[6])
                assertEquals(length, info[7])
                assertEquals(hashHex, NativeBridge.hex(hash))
                val expect = ByteArray(NativeBridge.HASH_BYTES)
                NativeBridge.nativeHashDeterministic(TestKeys.SEED, info[5], expect)
                assertTrue(expect.contentEquals(hash))
                items++
            }
            val len = LongArray(1)
            assertEquals(
                NativeBridge.ERR_WRONG_VMK,
                NativeBridge.nativeDecryptFile(file.absolutePath, TestKeys.OTHER_VMK, null, hash, len)
            )
            count++
        }
        assertTrue(count >= 12)
        assertTrue(items >= 4)
        dir.deleteRecursively()
    }

    @Test
    fun exportVectorForWindows() {
        val dir = File(context.filesDir, "pv_export").also { it.deleteRecursively(); it.mkdirs() }
        val specs = listOf("android_empty" to 0L, "android_three_chunks" to 3L * 4096 + 100,
            "android_default_chunk" to 70_000L)
        val manifest = StringBuilder("# name length chunk_size blake2b(plaintext) object_id media_type rotation width height created_unix_ms payload_len\n")
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
            manifest.append("$name $length $chunk ${NativeBridge.hex(hash)} ${NativeBridge.hex(id)} - - - - - -\n")
        }
        // Item-wrapped vectors made on Android (Phase 2).
        val itemSpecs = listOf(
            Triple("android_item_jpeg", 150_000L, longArrayOf(1, 90, 4000, 3000, 1791849600000L)),
            Triple("android_item_empty", 0L, longArrayOf(1, 0, 1, 1, 1791849600000L)),
            Triple("android_item_segment", 70_000L, longArrayOf(2, 270, 1280, 720, 1791849600000L))
        )
        for ((name, payloadLen, fields) in itemSpecs) {
            val id = ByteArray(NativeBridge.OBJECT_ID_BYTES)
            val file = File(dir, "$name.pvo")
            val rc = NativeBridge.nativeEncryptItemSampleFile(
                file.absolutePath, TestKeys.VMK, TestKeys.SEED, payloadLen, fields, id
            )
            assertEquals(NativeBridge.errorName(rc), NativeBridge.OK, rc)
            val hash = ByteArray(NativeBridge.HASH_BYTES)
            NativeBridge.nativeHashDeterministic(TestKeys.SEED, payloadLen, hash)
            val back = ByteArray(NativeBridge.HASH_BYTES)
            val info = LongArray(8)
            val status = IntArray(1)
            assertEquals(NativeBridge.OK, NativeBridge.nativeDecryptFileItem(file.absolutePath, TestKeys.VMK, id, back, info, status))
            assertEquals(NativeBridge.OK, status[0])
            assertEquals(payloadLen, info[5])
            assertTrue(hash.contentEquals(back))
            manifest.append(
                "$name ${48 + payloadLen} 65536 ${NativeBridge.hex(hash)} ${NativeBridge.hex(id)} " +
                    "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3]} ${fields[4]} $payloadLen\n"
            )
        }
        File(dir, "manifest.txt").writeText(manifest.toString())
        Log.i("PrivateVaultTest", "exported vectors to ${dir.absolutePath}")
    }
}
