package com.privatevault.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // Phase 3E: the segment writer over the committed inputs gives the
    // committed bytes (docs/PHASE3E_DESIGN.md Section 4.10).
    @Test
    fun segmentAvVector() {
        val text = assets.open("segment_av.txt").bufferedReader().readLines()
        var sps = ByteArray(0)
        var pps = ByteArray(0)
        var config = ByteArray(0)
        var channels = 0
        var rate = 0
        var bitrate = 0
        val sizes = ArrayList<Int>()
        val pts = ArrayList<Long>()
        val keys = ArrayList<Boolean>()
        val audioSizes = ArrayList<Int>()
        val audioPts = ArrayList<Long>()
        var endUs = 0L
        var firstTick = -1L
        for (line in text) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts[0]) {
                "sps" -> sps = NativeBridge.fromHex(parts[1])
                "pps" -> pps = NativeBridge.fromHex(parts[1])
                "audio_config" -> config = NativeBridge.fromHex(parts[1])
                "audio_format" -> {
                    channels = parts[1].toInt()
                    rate = parts[2].toInt()
                    bitrate = parts[3].toInt()
                }
                "sample" -> {
                    sizes.add(parts[2].toInt())
                    pts.add(parts[3].toLong())
                    keys.add(parts[4] == "1")
                }
                "audio" -> {
                    audioSizes.add(parts[2].toInt())
                    audioPts.add(parts[3].toLong())
                }
                "end_us" -> endUs = parts[1].toLong()
                "first_tick" -> firstTick = parts[1].toLong()
            }
        }
        assertEquals(3, sizes.size)
        assertEquals(4, audioSizes.size)
        val samples = assets.open("segment_samples.bin").use { it.readBytes() }
        val audio = assets.open("segment_av_audio.bin").use { it.readBytes() }
        assertEquals(audioSizes.sum(), audio.size)
        val expected = assets.open("segment_av_one_fragment.bin").use { it.readBytes() }
        val handle = LongArray(1)
        assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentOpenAv(1920, 1080, 90, sps, pps, channels, rate, bitrate, config, 0, 1L shl 20, handle))
        try {
            // The arrival order of the vector: video 0, audio 0 and 1, video 1, audio 2, video 2, audio 3.
            val order = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, 1 to 2, 0 to 2, 1 to 3)
            val videoAt = IntArray(3)
            val audioAt = IntArray(4)
            var at = 0
            for (i in 0 until 3) { videoAt[i] = at; at += sizes[i] }
            at = 0
            for (i in 0 until 4) { audioAt[i] = at; at += audioSizes[i] }
            for ((track, i) in order) {
                if (track == 0) {
                    val buf = java.nio.ByteBuffer.allocateDirect(sizes[i])
                    buf.put(samples, videoAt[i], sizes[i])
                    buf.flip()
                    assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAdd(handle[0], buf, 0, sizes[i], pts[i], keys[i]))
                } else {
                    val buf = java.nio.ByteBuffer.allocateDirect(audioSizes[i])
                    buf.put(audio, audioAt[i], audioSizes[i])
                    buf.flip()
                    assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAddAudio(handle[0], buf, 0, audioSizes[i], audioPts[i]))
                }
            }
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentFinish(handle[0], endUs))
            val stats = LongArray(2)
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAudioStats(handle[0], stats))
            assertEquals(4L, stats[0])
            assertEquals(firstTick, stats[1])
            val copy = NativeBridge.nativeSegmentCopy(handle[0])
            assertNotNull(copy)
            try {
                assertEquals(expected.size, copy!!.size)
                assertArrayEquals(expected, copy)
            } finally {
                copy!!.fill(0)
            }
        } finally {
            NativeBridge.nativeSegmentClose(handle[0])
        }
    }

    // Both readers over a segment whose audio starts only in its second
    // fragment (the shape late audio leaves, docs/PHASE3F_DESIGN.md 4.2).
    @Test
    fun readersOnAudioStartingInSecondFragment() {
        val text = assets.open("segment_av.txt").bufferedReader().readLines()
        var sps = ByteArray(0)
        var pps = ByteArray(0)
        var config = ByteArray(0)
        for (line in text) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts[0]) {
                "sps" -> sps = NativeBridge.fromHex(parts[1])
                "pps" -> pps = NativeBridge.fromHex(parts[1])
                "audio_config" -> config = NativeBridge.fromHex(parts[1])
            }
        }
        val samples = assets.open("segment_samples.bin").use { it.readBytes() }
        val audio = assets.open("segment_av_audio.bin").use { it.readBytes() }
        val handle = LongArray(1)
        assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentOpenAv(1920, 1080, 90, sps, pps, 2, 48000, 128000, config, 0, 1L shl 20, handle))
        try {
            val key = java.nio.ByteBuffer.allocateDirect(327).put(samples, 0, 327).apply { flip() }
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAdd(handle[0], key, 0, 327, 0, true))
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAdd(handle[0], key, 0, 327, 1_000_000, true))
            val frame = java.nio.ByteBuffer.allocateDirect(341).put(audio, 0, 341).apply { flip() }
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAddAudio(handle[0], frame, 0, 341, 1_000_500))
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAddAudio(handle[0], frame, 0, 341, 1_021_833))
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentFinish(handle[0], 2_000_000))
            val copy = NativeBridge.nativeSegmentCopy(handle[0])!!
            try {
                val fw = Readback.framework(copy)
                val m3 = Readback.media3(copy)
                for (t in fw) android.util.Log.i("PrivateVaultTest", "second-fragment audio, framework ${t.line()}")
                for (t in m3) android.util.Log.i("PrivateVaultTest", "second-fragment audio, media3 ${t.line()}")
                assertEquals(2, m3.first { it.kind == "audio" }.samples)
                assertEquals(2, fw.first { it.kind == "audio" }.samples)
            } finally {
                copy.fill(0)
            }
        } finally {
            NativeBridge.nativeSegmentClose(handle[0])
        }
        // Audio starting in the third fragment: media3 reads it; the framework
        // count is measured (it moves to the next moof once when a track has
        // no samples) and recorded in the test report.
        assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentOpenAv(1920, 1080, 90, sps, pps, 2, 48000, 128000, config, 0, 1L shl 20, handle))
        try {
            val key = java.nio.ByteBuffer.allocateDirect(327).put(samples, 0, 327).apply { flip() }
            for (t in listOf(0L, 1_000_000L, 2_000_000L)) assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAdd(handle[0], key, 0, 327, t, true))
            val frame = java.nio.ByteBuffer.allocateDirect(341).put(audio, 0, 341).apply { flip() }
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAddAudio(handle[0], frame, 0, 341, 2_000_500))
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAddAudio(handle[0], frame, 0, 341, 2_021_833))
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentFinish(handle[0], 3_000_000))
            val copy = NativeBridge.nativeSegmentCopy(handle[0])!!
            try {
                val fw = Readback.framework(copy)
                val m3 = Readback.media3(copy)
                for (t in fw) android.util.Log.i("PrivateVaultTest", "third-fragment audio, framework ${t.line()}")
                for (t in m3) android.util.Log.i("PrivateVaultTest", "third-fragment audio, media3 ${t.line()}")
                assertEquals(2, m3.first { it.kind == "audio" }.samples)
                assertTrue(fw.first { it.kind == "audio" }.samples <= 2)
            } finally {
                copy.fill(0)
            }
        } finally {
            NativeBridge.nativeSegmentClose(handle[0])
        }
    }

    @Test
    fun segmentWriterVector() {
        val text = assets.open("segment.txt").bufferedReader().readLines()
        var sps = ByteArray(0)
        var pps = ByteArray(0)
        val sizes = ArrayList<Int>()
        val pts = ArrayList<Long>()
        val keys = ArrayList<Boolean>()
        var endUs = 0L
        var hashHex = ""
        for (line in text) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts[0]) {
                "sps" -> sps = NativeBridge.fromHex(parts[1])
                "pps" -> pps = NativeBridge.fromHex(parts[1])
                "sample" -> {
                    sizes.add(parts[2].toInt())
                    pts.add(parts[3].toLong())
                    keys.add(parts[4] == "1")
                }
                "end_us" -> endUs = parts[1].toLong()
                "blake2b" -> hashHex = parts[1]
            }
        }
        assertEquals(3, sizes.size)
        val samples = assets.open("segment_samples.bin").use { it.readBytes() }
        assertEquals(sizes.sum(), samples.size)
        val expected = assets.open("segment_one_fragment.bin").use { it.readBytes() }
        val handle = LongArray(1)
        assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentOpen(1920, 1080, 90, sps, pps, 1L shl 20, handle))
        try {
            var at = 0
            for (i in sizes.indices) {
                val buf = java.nio.ByteBuffer.allocateDirect(sizes[i])
                buf.put(samples, at, sizes[i])
                buf.flip()
                at += sizes[i]
                assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentAdd(handle[0], buf, 0, sizes[i], pts[i], keys[i]))
            }
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentFinish(handle[0], endUs))
            val stats = LongArray(3)
            assertEquals(NativeBridge.OK, NativeBridge.nativeSegmentStats(handle[0], stats))
            assertEquals(expected.size.toLong(), stats[0])
            assertEquals(3L, stats[1])
            assertEquals(1L, stats[2])
            val got = NativeBridge.nativeSegmentCopy(handle[0])!!
            assertTrue(got.contentEquals(expected))
            got.fill(0)
        } finally {
            NativeBridge.nativeSegmentClose(handle[0])
        }
        assertTrue(hashHex.isNotEmpty())
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
