# Video playback probe (Stage 3C, Milestones D and E)

Date: 2026-10-20. Scope: can the encrypted video segments of Stage 3C be played back
from guarded memory with seeking, through ExoPlayer, without app code writing plaintext
media to storage, and what does it cost? Evidence comes from the debug-only probe
(`android/app/src/debug/java/com/privatevault/app/VideoProbeActivity.kt`,
`GuardedSegmentDataSource.kt`) over clips recorded by the Stage 3C prototype
(`DebugVideoProbes.kt`) at the approved settings (`docs/PHASE3C_DESIGN.md` Section 3),
driven by the instrumented test `VideoProbeTest` and the scripted action `video_play` on
the emulator `Pixel_8_API_35` (Android 15, x86_64, software H.264 decoder) and on the
Xiaomi 2201116SG (Android 13, MIUI 14, hardware decoder; Section 6).

Every number marked **measured** comes from a logged run (emulator logs under `build/p3c/`,
phone logs under `build/p3c/phone/`, none committed). Logs carry object IDs, sizes, times,
and counts only.

## 1. What the probe does

1. Reads the index object (`media_type` 3, index v1) through the viewer load and decodes it
   in the core (`VideoIndex.read`).
2. Builds an ExoPlayer with one `MediaItem` per segment (`pv://segment/<n>`), a
   `ProgressiveMediaSource` whose `DataSource` decrypts segment `n` through the 3B viewer
   pipeline into guarded memory when the player opens it and wipes it when the player
   closes it, and an extractor wrapper around `FragmentedMp4Extractor` that supplies a seek
   map built from the segment's own fragment table (Section 4).
3. Plays the clip to the end, then seeks to ten clip times chosen from the index (segment
   starts and times inside segments), each through `pv_video_index_locate`
   (`player.seekTo(segment, keyOffsetMs)`), and records for each the latency to the first
   frame released, the frame's time, and the player position.
4. Logs time to first frame, the gap at every segment boundary, seek results, the peak of
   live guarded buffers, PSS, native and Java heap, dropped frames, and errors; then
   `video_play: done`.
5. On a lock (or when the activity stops) releases the player and waits for every open data
   source to close, which frees and wipes its buffer.

The window carries `FLAG_SECURE`; the `SurfaceView` has `setSecure(true)`.

## 2. Recording at the approved settings (emulator, measured)

`VideoProbeTest`, 10 s clip, back camera, 1280x960, `build/p3c/t2-video9-logcat.txt`:

| Item | Value |
|---|---|
| Camera frame rate measured before the encoder is made | 47 frames in 1531 ms: 30 fps (29 in three earlier runs) |
| Encoder settings | frame rate 30, `KEY_I_FRAME_INTERVAL` 10 s backstop, sync request one GOP (1 s) after the last key frame, fragment 1000 ms, 4 Mbit/s |
| Frames, bytes, bitrate | 300 frames, 5 630 132 bytes, 4.51 Mbit/s |
| Segments | 4: 3142, 3103, 3100, 666 ms; 1 753 323, 1 748 688, 1 704 188, 431 113 bytes; 3, 3, 3, 1 key frames |
| Key counts per segment: encoder, container (box walk), media3, index | equal on every segment (3, 3, 3, 1); framework `MediaExtractor` 3, 3, 3, 1 (one fragment per GOP, so it agrees here) |
| First sample of every segment a key frame | yes (encoder flag on the first buffer; container per-sample flag on the first sample) |
| Index key times against the encoder log | equal (`index_times_match=true` on every segment) |
| Index object | 296 bytes, 4 segments, 10 keys, `total_duration_us` 10 012 502, `frame_rate_millihz` 30000; reads back and re-encodes to the same bytes |
| Encrypt per segment (debug build, emulator) | 27 to 38 ms (34, 27, 36, 38) for 0.4 to 1.75 MB |
| Process during the recording | CPU 2830 ms over 11 865 ms wall (23 %), PSS peak 72.3 MiB, native heap peak 15.3 MiB |
| Leak scan after the recording | 0 findings; new files: objects only (the lock controller's preference files aside) |

Under load (the first run after the emulator booted, `t2-video6-logcat.txt`), frame delivery
was uneven (gaps of 44 to 201 ms); the recording still produced key-aligned segments and the
same count agreement, but the container times shifted against the index by up to 141 ms
(Section 5).

## 3. Playback (emulator, measured)

Same run, `video_play` on the index above, software decoder:

| Measurement | Value |
|---|---|
| Time to first frame (`prepare` to `onRenderedFirstFrame`) | 644 ms (first run after boot: 2310 ms; other runs 835 to 1062 ms) |
| Segment boundaries crossed | 3 of 3 (`onMediaItemTransition` reason AUTO) |
| Gap at each boundary (release time of the first frame of segment n+1 minus the last of n) | 33, 33, 33 ms: one frame period at 30 fps, so no visible gap. Under load: 33, -37, 66 ms (a frame early, two frames late) |
| Dropped frames over the clip | 4 of 306 rendered (software decoder) |
| Segment decrypt at open (viewer load, 1.7 MB) | 5 to 232 ms, typically 20 to 80 ms |
| Live guarded buffers | peak 1 buffer, 1 753 323 bytes; 21 opens, 21 closes |
| PSS, native heap, Java heap peaks during playback | 77.8 MiB, 17.9 MiB, 16.8 MiB |
| Seeks | 10 of 10 rendered a frame, 0 timeouts, 0 player errors |

Seeks (target, chosen key frame, latency from `seekTo` to the first frame released, the
frame's time, the player position):

| Target ms | Segment, key | Key ms | Latency ms | Frame ms | Position ms |
|---|---|---|---|---|---|
| 0 | 0, 0 | 0 | 202 | 0 | 0 |
| 3142 | 1, 0 | 3142 | 183 | 3142 | 3142 |
| 6245 | 2, 0 | 6245 | 274 | 6245 | 6245 |
| 9345 | 3, 0 | 9345 | 191 | 9345 | 9345 |
| 1500 | 0, 1 | 1033 | 699 | 1033 | 1033 |
| 4642 | 1, 1 | 4179 | 418 | 4179 | 4178 |
| 7745 | 2, 1 | 7279 | 459 | 7279 | 7278 |
| 2500 | 0, 2 | 2100 | 210 | 2100 | 2100 |
| 5642 | 1, 2 | 5212 | 702 | 5212 | 5212 |
| 8745 | 2, 2 | 8312 | 460 | 8312 | 8311 |

Every seek rendered exactly the key frame the rule chose (frame time equal to the key time,
to the millisecond), so the position is at most one GOP before the target, and at the
segment start for segment-start seeks. Latency 183 to 702 ms on the software decoder, the
longer values for seeks into a segment the player had to decrypt and extract again.

## 4. Two media3 facts the probe had to work around (measured and confirmed in source)

1. **No `sidx`, no `tfdt`.** `FragmentedMp4Muxer` 1.11.1 writes `ftyp`, `moov`, then
   `moof` and `mdat` pairs; `traf` holds `tfhd` (track ID, base data offset) and `trun`
   (per-sample durations, sizes, flags) and no `tfdt`; no `sidx`. `FragmentedMp4Extractor`
   therefore reports an unseekable stream, and ExoPlayer treats a seek into a segment it has
   not loaded as a seek to the segment start. The data source builds a `ChunkIndex` from
   the fragment table (`moof` offsets and sizes; each fragment's start time as the sum of the
   `trun` durations before it, in the `mdhd` timescale) and the wrapper hands it to the
   player as the seek map.
2. **A seek to a fragment offset renumbers the fragment from zero.** On `seek(position,
   timeUs)` the extractor resets `nextFragmentDecodeTime` to 0; without a `tfdt` the fragment
   read next gets sample times from 0. The first probe build therefore showed frames from the
   wrong GOP after seeks inside a segment while the player position (masked to the seek
   target) looked right; the frame listener's times (1010, 1024, 1042 ms for a seek to
   1033 ms) gave it away. The wrapper now adds the fragment's start time back to every
   sample time after such a seek, and the frame times equal the key times (Section 3).

Also measured: a seek to the position the player already holds renders nothing (ExoPlayer
returns early), so the probe plans ten distinct landings; and ExoPlayer's `release()`
returns before the loader thread has closed a data source still open, so the probe waits for
the close (0 ms in every run so far; the test checks that no buffer is live when the
activity is destroyed).

## 5. Container times against the index

The muxer gives the last sample of every fragment the duration of the sample before it
(`LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS`, media3
1.11.1 `Boxes.getLastSampleDurationVu`). With even frame delivery the two durations are
equal and the container's key sample times equal the index offsets to within the timescale
rounding (0 ms largest difference in the passing runs, 4 to 6 microseconds per sample). When
the frame right before a key frame arrived after an uneven gap, the container times after
that fragment boundary shift by the difference: 141 ms once on the loaded emulator
(`t2-video6-logcat.txt`, segment 1, third key: encoder offset 2 488 365, container 2 629 566;
the gap before the key frame was 174 ms, the one before it 54 ms). The index keeps the
encoder's key-frame times (D-030); a seek to a shifted key lands on the fragment holding it
and renders the frame at the index time, which is then up to one frame gap before the key
frame, still within one GOP of the target. 3E removes the shift at the source by writing a
`tfdt` per fragment (or a `sidx`) and the true last durations (`docs/ROADMAP.md`).

## 6. Xiaomi (Milestone E, measured)

Xiaomi 2201116SG, Android 13, MIUI 14, debug build 0.5.0, hardware H.264 encoder and
decoder (`c2.qti.avc.encoder`), developer providing the install taps and the by-hand actions;
logs `build/p3c/phone/E*.txt`.

### 6.1 Recording

| Item | First clip (`E1-record`, before the meter fix) | Aimed clip (`E1-record` final, after it) |
|---|---|---|
| Camera frame rate measured before the encoder | 0 frames: the `ImageReader` with the default usage got nothing from this HAL; encoder given 30 | 45 frames in 1807 ms: 24 fps, encoder given 24 (reader made with `USAGE_GPU_SAMPLED_IMAGE`) |
| Frames, bytes, bitrate | 167 frames in 11.57 s (14.4 fps delivered), 7 012 432 bytes, 4.85 Mbit/s | 285 frames in 11.45 s (24.9 fps delivered), 7 010 128 bytes, 4.90 Mbit/s |
| Segments | 4: 3314, 3334, 3334, 1508 ms; 1.78, 2.25, 2.03, 0.95 MB; 3, 3, 3, 2 keys | 4; largest 2 051 684 bytes; 3, 3, 3, 2 keys |
| Key counts: encoder, container, framework, media3, index | equal on every segment | equal on every segment |
| First sample a key frame; index times equal the encoder log | yes; yes | yes; yes |
| Index | 300 bytes, 11 keys, `frame_rate_millihz` 30000 (the fallback; the delivered 14.4 fps was not recorded) | 11 keys, `frame_rate_millihz` 24000 (the meter's value; the delivered rate was 25) |
| Decoded first frame of each segment, mean luma | not measured then; the clip played back as a near-black picture (Section 6.3) | 134, 135, 137, 128 |
| Encrypt per segment (debug build) | 69 to 153 ms for 0.95 to 2.25 MB (14.6 MB/s) | similar |
| Process during the recording | CPU 11 310 ms over 19 958 ms wall (56 %, the meter wait included), PSS peak 132.7 MiB, native heap peak 38.8 MiB | similar |
| Unlock (PIN, final KDF default) before each run | 12.4 to 21.0 s on this day (3B measured about 4 s; the phone was warm and on battery saver level unknown; not investigated here) | |

The delivered rate of 14.4 fps in the first clip repeats the Milestone A finding (14.2 fps at
the prototype size); the aimed clip, held upright towards a window, delivered 24.9 fps.
The HAL picks the frame rate from the scene light; 3E chooses the size and a target range
(`docs/ROADMAP.md`). The index now records the measured rate, or the delivered rate of the
encoder output when the meter gets no frames.

### 6.2 Playback

Hardware decoder, index of the first clip unless noted (`E2-play`, `E3-home`, `E4-screenoff`,
`E5b-camera`, `E6-unsecure`, `E8-pixels-*`, `E9-play`; eight runs):

| Measurement | Value |
|---|---|
| Time to first frame | 327 to 542 ms (446 first run; 388 on the aimed clip) |
| Boundary gaps | 33, 33, 33 ms in six runs (one frame period at 30 fps nominal); 33, 33, 16 and 33, 16, 33 once each |
| Dropped frames | 0 in every run (177 or 295 frames rendered) |
| Seeks | 10 of 10 rendered the chosen key frame exactly in every run; latency 142 to 373 ms (segment starts 145 to 224 ms; inside a segment 150 to 373 ms) |
| Segment decrypt at open | not separated from the seek latency on the phone; the 3B viewer measured 120 to 150 ms for 2 MB in the debug build |
| Live guarded buffers | peak 1, 2 251 919 bytes; opens equal closes at release (16 to 21) |
| PSS, native heap, Java heap peaks | 148.6 to 160.8 MiB, 20.5 to 21.0 MiB, 9.7 to 11.2 MiB |
| `release()` to last buffer closed | 0 to 5 ms |

Seeks of the first run (`E2-play`):

| Target ms | Segment, key | Key ms | Latency ms | Frame ms | Position ms |
|---|---|---|---|---|---|
| 0 | 0, 0 | 0 | 148 | 0 | 0 |
| 3351 | 1, 0 | 3351 | 224 | 3351 | 3351 |
| 6723 | 2, 0 | 6723 | 205 | 6723 | 6723 |
| 10094 | 3, 0 | 10094 | 160 | 10094 | 10094 |
| 1500 | 0, 1 | 1103 | 313 | 1103 | 1103 |
| 4851 | 1, 1 | 4475 | 150 | 4475 | 4474 |
| 8223 | 2, 1 | 7846 | 271 | 7846 | 7846 |
| 11594 | 3, 1 | 11218 | 145 | 11218 | 11217 |
| 2500 | 0, 2 | 2227 | 373 | 2227 | 2227 |
| 5851 | 1, 2 | 5599 | 152 | 5599 | 5598 |

### 6.3 The black picture, and what it was

The developer saw a black picture on the probe screen for the first clip, with and without
`setSecure(true)` on the `SurfaceView` (`E6-unsecure`, `E6b-unsecure`), while ExoPlayer
reported every frame rendered. A `PixelCopy` sample of the `SurfaceView` (64x64, mean luma and
distinct colours only, no image kept) read mean luma 6 with about 188 distinct colours in
both modes (`E8-pixels-secure0`, `E8-pixels-secure1`), so the decoder output did reach the
surface and the picture itself was dark: the phone had been lying with the back camera
towards the desk during a recording that has no viewfinder. The aimed clip reads mean luma
134 to 136 with about 2300 distinct colours on the secure surface (`E9-play`), and the first
decoded frame of every segment has mean luma 128 to 137. The emulator, checked the same
way, reads mean luma 99 with about 2100 colours. The secure surface therefore does not
black out the hardware decoder's output on this device; the `secure=0` switch stays in the
probe for comparisons and the default is the secure surface. The developer's confirmation
by eye of the aimed clip is recorded in `docs/PHASE3C_TEST_REPORT.md`.

### 6.4 Lock cases and captures

| Case | Result |
|---|---|
| Home while the probe held the last frame after the clip and the seeks (`E3-home`; the 11.6 s clip is short for a press mid-clip) | `onStop` released the player (close wait 5 ms, no buffer live, 19 opens and 19 closes), the background lock followed 0.7 s later, the launcher was in front |
| Screen off (power button) while the probe was up (`E4-screenoff`) | `onStop` released the player (0 ms, 21 opens and 21 closes), then `lock: locking (background)` (the stop beat the screen-off broadcast to the lock) |
| MIUI camera (`E5b-camera`): the developer went Home and opened the camera from the launcher | the stop released the player (1 ms), the camera connected to camera 0, the background lock followed; a first attempt opened the camera during the invisible 12 s PIN unlock, before the probe existed (`E5-camera`), and is not counted |
| Screen capture during the hold (`adb exec-out screencap`, window `FLAG_SECURE`) | empty capture (0 bytes), as in 3B on this device |
| Recents card (`E7-recents.png`) | the PrivateVault card is blank (white with a grey header), as in 3B; the Camera card beside it shows its own content |
| Leak scan after every recording | 0 findings, 98 to 103 files scanned, no new non-object files |

## 7. Where plaintext lives during playback

`docs/PHASE3C_DESIGN.md` Section 5.3 and `docs/THREAT_MODEL.md` Section 9. The probe's own
measurements on both devices: one guarded buffer live at a time (never two in any run),
freed within 0 to 5 ms of `release()` returning; ExoPlayer's sample queues, the decoder's
input and output buffers, and the compositor are outside the app's reach, as recorded there.

## 8. Lock cases (emulator, measured)

| Case | Result |
|---|---|
| Lock during playback (`lock.lock` after the first frame) | player released, 2 opens and 2 closes, no live buffer, `Vault.buffersFreed` advanced, activity destroyed, lock screen routed |
| Lock during a slowed segment decrypt (viewer hook delays the decrypt 1.5 s) | the open in flight ends with `PV_ERR_CANCELLED`, the lock took 1443 to 1483 ms (the wait for the in-flight call), no buffer live, player released |
| Leak scan after all of it | 0 findings; new files: objects and the lock controller's preference files only (a chance 3-byte JPEG marker match inside one object's ciphertext at offset 64137 was seen once and judged as such) |
