package com.privatevault.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Debug-only walk over the boxes of a fragmented MP4 held in memory, for the
// Stage 3C key-frame investigation (docs/PHASE3C_DESIGN.md Section 2). It
// reports the sample flags the container carries: trex and tfhd defaults,
// and per trun the flags field, sample count, first_sample_flags, and how
// many per-sample flag words mark the sample as sync. Nothing here reads
// sample data; the summary carries counts and flag values only.
object Mp4Boxes {

    class Trun(val flags: Int, val sampleCount: Int, val firstSampleFlags: Int?, val perSampleFlags: Int?, val syncPerSample: Int, val nonSyncPerSample: Int)

    class Summary {
        var moofCount = 0
        var trexDefaultFlags: Int? = null
        val tfhdDefaultFlags = ArrayList<Int?>()
        val truns = ArrayList<Trun>()
        var mdatBytes = 0L
        var error: String? = null
        // Stage 3E: the tfdt of each traf (null when absent), the sidx
        // reference count (-1 when absent), and the rotation the tkhd
        // matrix encodes (-1 when the matrix is none of the four).
        val tfdts = ArrayList<Long?>()
        var sidxReferences = -1
        var tkhdRotation = -1
        var trafCount = 0

        // One line, flag words in hex, counts in decimal.
        fun line(): String {
            val sb = StringBuilder("boxes: moof=$moofCount mdat_bytes=$mdatBytes tkhd_rotation=$tkhdRotation sidx_refs=$sidxReferences tfdt=[${tfdts.joinToString(",") { it?.toString() ?: "-" }}] trex_default_flags=${hex(trexDefaultFlags)}")
            sb.append(" tfhd_default_flags=[").append(tfhdDefaultFlags.joinToString(",") { hex(it) }).append("]")
            sb.append(" trun=[")
            sb.append(truns.joinToString(" ") { t ->
                "flags=${hex(t.flags)} n=${t.sampleCount} first=${hex(t.firstSampleFlags)} per_sample=${if (t.perSampleFlags != null) "yes" else "no"} sync=${t.syncPerSample} nonsync=${t.nonSyncPerSample}"
            })
            sb.append("]")
            if (error != null) sb.append(" error=$error")
            return sb.toString()
        }

        // Whether the container marks the very first sample as sync; null
        // when there is no trun.
        fun firstSampleSync(): Boolean? {
            val t = truns.firstOrNull() ?: return null
            val f = t.perSampleFlags ?: t.firstSampleFlags ?: tfhdDefaultFlags.getOrNull(0) ?: trexDefaultFlags ?: 0
            return isSync(f)
        }

        // Sync samples the container marks: per-sample flags where present,
        // otherwise first_sample_flags for the first sample and the tfhd or
        // trex default for the rest.
        fun containerSyncCount(): Int {
            var n = 0
            for ((i, t) in truns.withIndex()) {
                if (t.perSampleFlags != null) {
                    n += t.syncPerSample
                    continue
                }
                val default = tfhdDefaultFlags.getOrNull(i) ?: trexDefaultFlags ?: 0
                val first = t.firstSampleFlags ?: default
                if (t.sampleCount > 0 && isSync(first)) n++
                if (t.sampleCount > 1 && isSync(default)) n += t.sampleCount - 1
            }
            return n
        }
    }

    private fun hex(v: Int?): String = if (v == null) "-" else "0x" + Integer.toHexString(v)

    // sample_is_non_sync_sample is bit 16 of a sample_flags word.
    fun isSync(sampleFlags: Int): Boolean = sampleFlags and 0x00010000 == 0

    fun walk(data: ByteBuffer): Summary {
        val s = Summary()
        val b = data.duplicate().order(ByteOrder.BIG_ENDIAN)
        try {
            walkRange(b, b.position(), b.limit(), s, null)
        } catch (e: Exception) {
            s.error = "${e.javaClass.simpleName}: ${e.message}"
        }
        return s
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf")

    private fun walkRange(b: ByteBuffer, start: Int, end: Int, s: Summary, parent: String?) {
        var pos = start
        while (pos + 8 <= end) {
            b.position(pos)
            var size = b.int.toLong() and 0xffffffffL
            val type = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                size = b.long
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) {
                s.error = "box $type size $size past the end at $pos"
                return
            }
            val bodyStart = pos + header
            val bodyEnd = (pos + size).toInt()
            when (type) {
                in containers -> {
                    if (type == "moof") s.moofCount++
                    if (type == "traf") {
                        s.trafCount++
                        s.tfdts.add(null)
                    }
                    walkRange(b, bodyStart, bodyEnd, s, type)
                }
                "mdat" -> s.mdatBytes += size - header
                "tfdt" -> {
                    b.position(bodyStart)
                    val version = b.get().toInt() and 0xff
                    b.position(bodyStart + 4)
                    val t = if (version == 1) b.long else (b.int.toLong() and 0xffffffffL)
                    if (s.tfdts.isNotEmpty()) s.tfdts[s.tfdts.size - 1] = t
                }
                "sidx" -> {
                    b.position(bodyStart)
                    val version = b.get().toInt() and 0xff
                    b.position(bodyStart + 4 + 4 + 4 + (if (version == 1) 16 else 8) + 2)
                    s.sidxReferences = b.short.toInt() and 0xffff
                }
                "tkhd" -> {
                    b.position(bodyStart)
                    val version = b.get().toInt() and 0xff
                    // After version/flags, times, track_ID, reserved, duration, 8 reserved, 4 shorts.
                    b.position(bodyStart + 4 + (if (version == 1) 32 else 20) + 8 + 8)
                    val a = b.int; val bb = b.int; b.int; val c = b.int; val d = b.int
                    val one = 0x00010000
                    s.tkhdRotation = when {
                        a == one && bb == 0 && c == 0 && d == one -> 0
                        a == 0 && bb == one && c == -one && d == 0 -> 90
                        a == -one && bb == 0 && c == 0 && d == -one -> 180
                        a == 0 && bb == -one && c == one && d == 0 -> 270
                        else -> -1
                    }
                }
                "trex" -> {
                    b.position(bodyStart + 4)   // version and flags
                    b.int; b.int; b.int; b.int  // track_ID, description index, duration, size
                    s.trexDefaultFlags = b.int
                }
                "tfhd" -> {
                    b.position(bodyStart)
                    val vf = b.int
                    val flags = vf and 0xffffff
                    b.int                                   // track_ID
                    if (flags and 0x1 != 0) b.long          // base_data_offset
                    if (flags and 0x2 != 0) b.int           // sample_description_index
                    if (flags and 0x8 != 0) b.int           // default_sample_duration
                    if (flags and 0x10 != 0) b.int          // default_sample_size
                    s.tfhdDefaultFlags.add(if (flags and 0x20 != 0) b.int else null)
                }
                "trun" -> {
                    b.position(bodyStart)
                    val vf = b.int
                    val flags = vf and 0xffffff
                    val count = b.int
                    if (flags and 0x1 != 0) b.int           // data_offset
                    val first = if (flags and 0x4 != 0) b.int else null
                    val hasDuration = flags and 0x100 != 0
                    val hasSize = flags and 0x200 != 0
                    val hasFlags = flags and 0x400 != 0
                    val hasCts = flags and 0x800 != 0
                    var sync = 0
                    var nonSync = 0
                    var firstPerSample: Int? = null
                    for (i in 0 until count) {
                        if (hasDuration) b.int
                        if (hasSize) b.int
                        if (hasFlags) {
                            val f = b.int
                            if (i == 0) firstPerSample = f
                            if (isSync(f)) sync++ else nonSync++
                        }
                        if (hasCts) b.int
                    }
                    s.truns.add(Trun(flags, count, first, if (hasFlags) firstPerSample else null, sync, nonSync))
                }
                else -> {}
            }
            pos = bodyEnd
        }
    }
}
