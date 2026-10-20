package com.privatevault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// T2: the video index through the bridge (docs/PHASE3C_DESIGN.md Section
// 4.4): a round trip, the committed golden vectors decoded and re-encoded to
// the same bytes, the index item decrypted through the viewer pipeline, the
// seek rule, and the rejections. Synthetic values only.
@RunWith(AndroidJUnit4::class)
class VideoIndexTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    private fun id(seed: Int): ByteArray = ByteArray(NativeBridge.OBJECT_ID_BYTES) { (seed * 17 + it).toByte() }

    private fun sample(): VideoIndex = VideoIndex(
        VideoIndex.Header(NativeBridge.VIDEO_CODEC_H264, 90, 1280, 960, 15_000, 9_066_666L, 1791849600000L),
        listOf(
            VideoIndex.Segment(id(1), 0L, 3_066_666L, 900_000L, intArrayOf(0, 1_000_000, 2_066_666)),
            VideoIndex.Segment(id(2), 3_066_666L, 3_000_000L, 850_000L, intArrayOf(0, 1_066_666, 2_000_000)),
            VideoIndex.Segment(id(3), 6_066_666L, 3_000_000L, 910_000L, intArrayOf(0, 1_000_000, 2_000_000))
        )
    )

    @Test
    fun roundTripThroughTheBridge() {
        val x = sample()
        val bytes = x.encode()
        assertEquals(64 + 3 * 48 + 9 * 4, bytes.size)
        assertEquals("PVVINDEX", String(bytes, 0, 8, Charsets.US_ASCII))
        val d = VideoIndex.decode(bytes)
        assertEquals(NativeBridge.OK, d.status)
        val y = d.index!!
        assertEquals(x.header, y.header)
        assertEquals(3, y.segments.size)
        for (i in 0 until 3) {
            assertArrayEquals(x.segments[i].id, y.segments[i].id)
            assertEquals(x.segments[i].startUs, y.segments[i].startUs)
            assertEquals(x.segments[i].durationUs, y.segments[i].durationUs)
            assertEquals(x.segments[i].byteLen, y.segments[i].byteLen)
            assertArrayEquals(x.segments[i].keyOffsetsUs, y.segments[i].keyOffsetsUs)
        }
        assertArrayEquals(bytes, y.encode())
        // The seek rule.
        fun at(t: Long): Triple<Int, Int, Long> = y.locate(t).let { Triple(it.segment, it.key, it.keyTimeUs) }
        assertEquals(Triple(0, 0, 0L), at(0))
        assertEquals(Triple(0, 0, 0L), at(999_999))
        assertEquals(Triple(0, 1, 1_000_000L), at(1_000_000))
        assertEquals(Triple(0, 2, 2_066_666L), at(3_066_665))
        assertEquals(Triple(1, 0, 3_066_666L), at(3_066_666))
        assertEquals(Triple(1, 1, 4_133_332L), at(4_500_000))
        assertEquals(Triple(2, 2, 8_066_666L), at(9_066_665))
        assertEquals(Triple(2, 2, 8_066_666L), at(9_066_666))
        assertEquals(Triple(2, 2, 8_066_666L), at(Long.MAX_VALUE / 4))
    }

    @Test
    fun committedVectorsDecodeAndReEncode() {
        val lines = assets.open("video_index.txt").bufferedReader().readLines()
        var vectors = 0
        var items = 0
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+"))
            if (p[0] == "vector") {
                val bytes = assets.open("${p[1]}.bin").use { it.readBytes() }
                val d = VideoIndex.decode(bytes)
                assertEquals("${p[1]}: ${NativeBridge.errorName(d.status)}", NativeBridge.OK, d.status)
                val idx = d.index!!
                assertEquals(p[2].toInt(), idx.segments.size)
                assertEquals(p[3].toInt(), idx.keysTotal)
                assertEquals(p[4].toLong(), idx.header.totalDurationUs)
                assertEquals(p[5].toInt(), idx.header.audioCodec)
                assertEquals(0L, idx.segments[0].startUs)
                assertEquals(0, idx.segments[0].keyOffsetsUs[0])
                assertArrayEquals(p[1], bytes, idx.encode())
                vectors++
            } else if (p[0] == "item") {
                // The item object placed in a vault under the test key and read
                // through the viewer pipeline.
                val dir = File(context.filesDir, "video_index_test").also { it.deleteRecursively() }
                assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
                val target = File(File(dir, "objects"), "${p[2]}.pvo")
                assets.open("${p[1]}.pvo").use { input -> target.outputStream().use { input.copyTo(it) } }
                val vault = Vault.openWithKey(dir, TestKeys.VMK)
                try {
                    val d = VideoIndex.read(vault, NativeBridge.fromHex(p[2]))
                    assertEquals(NativeBridge.OK, d.status)
                    val idx = d.index!!
                    assertEquals(3, idx.segments.size)
                    assertEquals(9, idx.keysTotal)
                    val three = assets.open("video_index_three.bin").use { it.readBytes() }
                    assertArrayEquals(three, idx.encode())
                    // The wrong key never reaches the index.
                    val other = Vault.openWithKey(dir, TestKeys.OTHER_VMK)
                    try {
                        assertEquals(NativeBridge.ERR_WRONG_VMK, VideoIndex.read(other, NativeBridge.fromHex(p[2])).status)
                    } finally {
                        other.close()
                    }
                } finally {
                    vault.close()
                    dir.deleteRecursively()
                }
                items++
            }
        }
        assertEquals(3, vectors)
        assertEquals(1, items)
    }

    @Test
    fun rejections() {
        val good = sample().encode()
        fun status(b: ByteArray): Int = VideoIndex.decode(b).status
        // No magic, the Phase 2 prototype magic, and an empty buffer are not an index.
        assertEquals(NativeBridge.ERR_NOT_INDEX, status(ByteArray(0)))
        assertEquals(NativeBridge.ERR_NOT_INDEX, status(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        val prototype = good.copyOf()
        System.arraycopy("PVVIDX01".toByteArray(Charsets.US_ASCII), 0, prototype, 0, 8)
        assertEquals(NativeBridge.ERR_NOT_INDEX, status(prototype))
        // Version 2 is unsupported; a bad codec too.
        assertEquals(NativeBridge.ERR_INDEX_UNSUPPORTED, status(good.copyOf().also { it[8] = 2 }))
        assertEquals(NativeBridge.ERR_INDEX_UNSUPPORTED, status(good.copyOf().also { it[12] = 3 }))
        // Truncated anywhere after the magic, a trailing byte, a rotation of 45.
        for (n in listOf(8, 63, 64, 100, good.size - 1)) {
            assertEquals("cut at $n", NativeBridge.ERR_INDEX_MALFORMED, status(good.copyOfRange(0, n)))
        }
        assertEquals(NativeBridge.ERR_INDEX_MALFORMED, status(good + byteArrayOf(0)))
        assertEquals(NativeBridge.ERR_INDEX_MALFORMED, status(good.copyOf().also { it[14] = 45 }))
        // The writer refuses what the reader would: 4097 keys in one segment,
        // a second segment starting before the first ends by more than a
        // frame, a key offset at the duration.
        fun refused(x: VideoIndex, expect: Int) {
            try {
                x.encode()
                fail("encode accepted a bad index")
            } catch (e: Vault.CoreException) {
                assertEquals(expect, e.code)
            }
        }
        val tooMany = IntArray(4097) { it * 100 }
        refused(VideoIndex(sample().header.copy(totalDurationUs = 1_000_000L), listOf(VideoIndex.Segment(id(1), 0, 1_000_000L, 10, tooMany))), NativeBridge.ERR_INDEX_MALFORMED)
        val okMany = IntArray(4096) { it * 100 }
        val big = VideoIndex(sample().header.copy(totalDurationUs = 1_000_000L), listOf(VideoIndex.Segment(id(1), 0, 1_000_000L, 10, okMany)))
        assertEquals(64 + 48 + 4096 * 4, big.encode().size)
        val s = sample()
        refused(VideoIndex(s.header, listOf(s.segments[0], VideoIndex.Segment(id(2), 2_000_000L, 3_000_000L, 10, intArrayOf(0)), s.segments[2])), NativeBridge.ERR_INDEX_MALFORMED)
        refused(VideoIndex(s.header, listOf(VideoIndex.Segment(id(1), 0L, 3_066_666L, 10, intArrayOf(0, 3_066_666)), s.segments[1], s.segments[2])), NativeBridge.ERR_INDEX_MALFORMED)
        refused(VideoIndex(s.header.copy(videoCodec = 7), s.segments), NativeBridge.ERR_INDEX_UNSUPPORTED)
        // Through the viewer pipeline: a JPEG item and a plain object are not an index.
        val dir = File(context.filesDir, "video_index_test2").also { it.deleteRecursively() }
        assertEquals(NativeBridge.OK, NativeBridge.nativeVaultCreate(dir.absolutePath))
        val vault = Vault.openWithKey(dir, TestKeys.VMK)
        try {
            val jpeg = vault.encryptItemArray(ByteArray(500), 0, 500, Vault.ItemMeta(NativeBridge.MEDIA_JPEG, 0, 10, 10, 1791849600000L)).id
            assertEquals(NativeBridge.ERR_NOT_INDEX, VideoIndex.read(vault, jpeg).status)
            val plain = vault.encryptSample(TestKeys.SEED, 300)
            assertEquals(NativeBridge.ERR_NOT_ITEM, VideoIndex.read(vault, plain).status)
            // An index item with the Phase 2 prototype magic stays an unknown index.
            val proto = vault.encryptItemArray(prototype, 0, prototype.size, Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_INDEX, 0, 10, 10, 1791849600000L)).id
            assertEquals(NativeBridge.ERR_NOT_INDEX, VideoIndex.read(vault, proto).status)
            // The real one reads back.
            val real = vault.encryptItemArray(good, 0, good.size, Vault.ItemMeta(NativeBridge.MEDIA_VIDEO_INDEX, 90, 1280, 960, 1791849600000L)).id
            val d = VideoIndex.read(vault, real)
            assertEquals(NativeBridge.OK, d.status)
            assertNotNull(d.index)
            assertArrayEquals(good, d.index!!.encode())
            assertNull(VideoIndex.read(vault, jpeg).index)
            assertTrue(Vault.buffersFreed.get() > 0)
        } finally {
            vault.close()
            dir.deleteRecursively()
        }
    }
}
