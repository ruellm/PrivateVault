package com.privatevault.app

import android.os.Debug

// Debug-only Stage 3D Milestone A trial: the first key frame of a clip's
// first segment decoded to a 256 px bitmap through VideoFirstFrame, timed
// per step, repeated n times. Prints the codec, the sizes, the times, the
// mean luma and colour count of the bitmap, and the memory before and
// after. The bitmap is erased after each measurement; nothing is kept.
//   thumb_probe=<index hex | latest>[:n=<repeat>]
object VideoThumbProbe {

    fun run(vaults: VaultSession, arg: String, say: (String) -> Unit) {
        val v = vaults.vault
        if (v == null) {
            say("thumb_probe: vault not open")
            return
        }
        val parts = arg.split(':')
        var repeat = 3
        for (p in parts.drop(1)) if (p.startsWith("n=")) repeat = p.substringAfter('=').toIntOrNull() ?: 3
        val selector = parts[0]
        var index: VideoIndex? = null
        var hex = ""
        if (selector == "latest") {
            for (e in v.listObjects()) {
                if (e.size > VideoIndex.OBJECT_CAP + 4096) continue
                val d = VideoIndex.read(v, e.id)
                if (d.status == NativeBridge.OK && d.index != null) {
                    index = d.index
                    hex = e.hex
                    break
                }
            }
        } else if (selector.length == 32) {
            val d = VideoIndex.read(v, NativeBridge.fromHex(selector))
            if (d.status == NativeBridge.OK) index = d.index
            hex = selector
        }
        val idx = index
        if (idx == null) {
            say("thumb_probe: no index object")
            return
        }
        val seg = idx.segments[0]
        say("thumb_probe: index $hex segments=${idx.segments.size} ${idx.header.width}x${idx.header.height} rotation=${idx.header.rotation} first_segment=${seg.hex} bytes=${seg.byteLen}")
        val rt = Runtime.getRuntime()
        for (i in 0 until repeat) {
            val mi0 = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi0)
            val java0 = (rt.totalMemory() - rt.freeMemory()) shr 10
            val native0 = Debug.getNativeHeapAllocatedSize() shr 10
            val t0 = System.nanoTime()
            val load = v.viewerLoad(seg.id, PhotoLoader.SIZE_CAP)
            val t1 = System.nanoTime()
            if (load.status != NativeBridge.OK) {
                say("thumb_probe: segment load ${NativeBridge.errorName(load.status)}")
                return
            }
            val item = load.item
            val payload = load.payload
            if (load.itemStatus != NativeBridge.OK || item == null || item.mediaType != NativeBridge.MEDIA_VIDEO_SEGMENT || payload == null) {
                load.close()
                say("thumb_probe: first segment is not a video segment (${NativeBridge.errorName(load.itemStatus)})")
                return
            }
            val started = VideoFirstFrame.start(payload)
            val t2 = System.nanoTime()
            // The sample is inside the codec now; the segment buffer goes.
            load.close()
            val t3 = System.nanoTime()
            val r = if (started is VideoFirstFrame.Session) started.finish(VideoFirstFrame.TARGET_PX) else started as VideoFirstFrame.Result
            val t4 = System.nanoTime()
            val mi1 = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi1)
            val java1 = (rt.totalMemory() - rt.freeMemory()) shr 10
            val native1 = Debug.getNativeHeapAllocatedSize() shr 10
            val bmp = r.bitmap
            var luma = -1L
            var colours = 0
            var size = "none"
            if (bmp != null) {
                var sum = 0L
                val set = HashSet<Int>()
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                    val c = bmp.getPixel(x, y)
                    sum += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
                    set.add(c and 0xffffff)
                }
                luma = sum / (bmp.width.toLong() * bmp.height)
                colours = set.size
                size = "${bmp.width}x${bmp.height}"
                val turned = PhotoLoader.turned(bmp, item.rotation)
                size += " turned=${turned.width}x${turned.height}"
                PhotoLoader.dispose(turned)
            }
            say("thumb_probe run $i: codec=${r.codec} frame=${r.width}x${r.height} sample_bytes=${r.sampleBytes} decrypt_ms=${(t1 - t0) / 1_000_000} parse_ms=${r.parseMs} codec_start_ms=${r.codecMs} buffer_live_ms=${(t3 - t0) / 1_000_000} decode_ms=${r.decodeMs} convert_ms=${r.convertMs} total_ms=${(t4 - t0) / 1_000_000} thumb=$size mean_luma=$luma distinct_colours=$colours error=${r.error ?: "none"}")
            say("thumb_probe run $i: memory pss=${mi0.totalPss}->${mi1.totalPss}KiB java=${java0}->${java1}KiB native=${native0}->${native1}KiB buffers_freed=${Vault.buffersFreed.get()}")
            if (r.error != null && i == 0) {
                say("thumb_probe: first run failed, stopping")
                return
            }
        }
        say("thumb_probe: done")
    }
}
