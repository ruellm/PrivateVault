package com.privatevault.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Stage 3F Milestone A, debug only: what the device offers for audio
// capture, measured. AAC encoders and their ranges, the AudioRecord
// configurations that initialize and deliver frames, the timestamp support
// of AudioRecord on both time bases, the timestamp source of each camera,
// and a short AAC encode of a synthetic tone. Lines carry names, counts,
// and times; nothing recorded by the microphone leaves this object.
object AudioProbes {
    const val MIME = "audio/mp4a-latm"
    const val FRAMES_PER_AAC = 1024

    fun caps(context: Context, say: (String) -> Unit) {
        encoders(say)
        cameras(context, say)
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("audio_caps: no microphone permission, record checks skipped")
        } else {
            for (source in listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
                for (rate in listOf(48000, 44100)) {
                    for (channels in listOf(2, 1)) record(source, rate, channels, say)
                }
            }
        }
        for (channels in listOf(2, 1)) encode(48000, channels, 128_000, say)
    }

    fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SOURCE_$source"
    }

    fun channelMask(channels: Int): Int =
        if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    private fun encoders(say: (String) -> Unit) {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        var n = 0
        for (info in list.codecInfos) {
            if (!info.isEncoder || info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) continue
            n++
            val caps = info.getCapabilitiesForType(MIME)
            val audio = caps.audioCapabilities
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else false
            val rates = audio.supportedSampleRates?.joinToString(",") ?: audio.supportedSampleRateRanges.joinToString(",") { "${it.lower}-${it.upper}" }
            val profiles = caps.profileLevels.joinToString(",") { it.profile.toString() }
            say("audio_caps: encoder ${info.name} hardware=$hw rates=$rates max_channels=${audio.maxInputChannelCount} bitrate=${audio.bitrateRange.lower}-${audio.bitrateRange.upper} profiles=$profiles")
        }
        say("audio_caps: aac encoders=$n")
    }

    private fun cameras(context: Context, say: (String) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                val source = when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                    else -> "none"
                }
                val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                say("audio_caps: camera $id facing=$facing timestamp_source=$source hardware_level=$level")
            } catch (e: Exception) {
                say("audio_caps: camera $id: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // Opens one AudioRecord configuration, reads for about 300 ms, and
    // reports the frames delivered and the timestamp support.
    private fun record(source: Int, rate: Int, channels: Int, say: (String) -> Unit) {
        val prefix = "audio_caps: record source=${sourceName(source)} rate=$rate channels=$channels"
        val mask = channelMask(channels)
        val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            say("$prefix min_buffer=$minBuffer unsupported")
            return
        }
        val frameBytes = 2 * channels
        val chunk = FRAMES_PER_AAC * frameBytes
        val rec = try {
            AudioRecord(source, rate, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, chunk * 8))
        } catch (e: SecurityException) {
            say("$prefix min_buffer=$minBuffer open refused: ${e.message}")
            return
        } catch (e: Exception) {
            say("$prefix min_buffer=$minBuffer open failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                say("$prefix min_buffer=$minBuffer init=no")
                return
            }
            val t0 = System.nanoTime()
            rec.startRecording()
            val buf = ByteBuffer.allocateDirect(chunk).order(ByteOrder.nativeOrder())
            var frames = 0L
            var reads = 0
            var shortReads = 0
            var peak = 0
            var firstDataMs = -1L
            while (System.nanoTime() - t0 < 300_000_000L) {
                buf.clear()
                val n = rec.read(buf, chunk, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    say("$prefix read error $n")
                    break
                }
                reads++
                if (n < chunk) shortReads++
                if (n > 0 && firstDataMs < 0) firstDataMs = (System.nanoTime() - t0) / 1_000_000
                frames += n / frameBytes
                val shorts = buf.asShortBuffer()
                for (i in 0 until n / 2) {
                    val v = Math.abs(shorts.get(i).toInt())
                    if (v > peak) peak = v
                }
            }
            val mono = timestampLine(rec, AudioTimestamp.TIMEBASE_MONOTONIC, frames, rate)
            val boot = timestampLine(rec, AudioTimestamp.TIMEBASE_BOOTTIME, frames, rate)
            rec.stop()
            say("$prefix min_buffer=$minBuffer init=yes actual_rate=${rec.sampleRate} actual_channels=${rec.channelCount} frames=$frames reads=$reads short_reads=$shortReads first_data_ms=$firstDataMs peak=$peak monotonic{$mono} boottime{$boot}")
        } catch (e: Exception) {
            say("$prefix failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            rec.release()
        }
    }

    // getTimestamp on the given time base: the result code, the frame
    // position against the frames read, and the age of the timestamp.
    private fun timestampLine(rec: AudioRecord, timebase: Int, framesRead: Long, rate: Int): String {
        val ts = AudioTimestamp()
        val rc = rec.getTimestamp(ts, timebase)
        if (rc != AudioRecord.SUCCESS) return "rc=$rc"
        val now = if (timebase == AudioTimestamp.TIMEBASE_BOOTTIME) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
        // Where the read position sits on this clock, given the timestamp.
        val readAtUs = (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / rate) / 1000
        return "rc=0 frame_position=${ts.framePosition} frames_read=$framesRead age_ms=${(now - ts.nanoTime) / 1_000_000} read_position_behind_now_ms=${(now / 1000 - readAtUs) / 1000}"
    }

    // Encodes 20 frames of a synthetic tone and reports the codec config,
    // the output sizes, and whether the output times follow the input times.
    private fun encode(rate: Int, channels: Int, bitrate: Int, say: (String) -> Unit) {
        val prefix = "audio_caps: encode rate=$rate channels=$channels bitrate=$bitrate"
        val codec = try {
            MediaCodec.createEncoderByType(MIME)
        } catch (e: Exception) {
            say("$prefix create failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        try {
            val format = MediaFormat.createAudioFormat(MIME, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_AAC * 2 * channels * 4)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frames = 20
            val frameBytes = 2 * channels
            val pcm = ByteBuffer.allocate(FRAMES_PER_AAC * frameBytes).order(ByteOrder.nativeOrder())
            var fed = 0
            var eos = false
            var csd = "none"
            val outSizes = ArrayList<Int>()
            val inPts = ArrayList<Long>()
            val outPts = ArrayList<Long>()
            val info = MediaCodec.BufferInfo()
            var phase = 0.0
            while (true) {
                if (!eos) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        if (fed < frames) {
                            pcm.clear()
                            for (i in 0 until FRAMES_PER_AAC) {
                                val v = (Math.sin(phase) * 8000).toInt().toShort()
                                phase += 2 * Math.PI * 440 / rate
                                for (c in 0 until channels) pcm.putShort(v)
                            }
                            pcm.flip()
                            ib.put(pcm)
                            val pts = fed.toLong() * FRAMES_PER_AAC * 1_000_000L / rate
                            inPts.add(pts)
                            codec.queueInputBuffer(idx, 0, FRAMES_PER_AAC * frameBytes, pts, 0)
                            fed++
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val c = codec.outputFormat.getByteBuffer("csd-0")
                    if (c != null) {
                        val d = c.duplicate()
                        val sb = StringBuilder()
                        while (d.hasRemaining()) sb.append(String.format("%02x", d.get().toInt() and 0xff))
                        csd = sb.toString()
                    }
                    continue
                }
                if (out >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        outSizes.add(info.size)
                        outPts.add(info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val follow = outPts.size == inPts.size && outPts.indices.all { Math.abs(outPts[it] - inPts[it]) <= 1 }
            say("$prefix codec=${codec.name} csd0=$csd in_frames=$fed out_frames=${outSizes.size} out_bytes=${outSizes.minOrNull()}-${outSizes.maxOrNull()} out_pts_equal_in=$follow first_out_pts=${outPts.take(3)} last_out_pts=${outPts.lastOrNull()}")
        } catch (e: Exception) {
            say("$prefix failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
            }
            codec.release()
        }
    }
}
