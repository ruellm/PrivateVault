# Video capture feasibility (Phase 2, Milestone E)

Date: 2026-10-13. Scope: can video be recorded into the vault the way photos are, without
app code writing plaintext media to storage? Evidence comes from a debug-only prototype
(`android/app/src/debug/java/com/privatevault/app/DebugVideoProbes.kt`) driven by the
scripted actions `video_probe` and `v2_probe` on the emulator `Pixel_8_API_35` (Android 15,
x86_64, back camera "virtual scene", LIMITED hardware level) in Milestone E, and on the
Xiaomi 2201116SG (Android 13, MIUI 14, hardware H.264 encoder) in Milestone F. Sections 2
and 3 give the emulator numbers; Section 7 gives the phone numbers side by side.

Every claim marked **measured** comes from a logged run: emulator logs under `build/emu/`
(`E-video-probe.txt`, `E-v2-probe.txt`) and phone logs under `build/phone/` (`H1.txt`,
`H2.txt`), none of them committed. Everything else is design reasoning.

## 1. Options and what happened

| Option | Pipeline | Tried | Result | Plaintext in app-visible storage |
|---|---|---|---|---|
| V1 | `Recorder` to a temporary file, encrypt, delete | No (paper only, as the brief requires) | Would write the whole clip in plaintext to app storage; deletion on flash is not erasure (threat model). Rejected. | Yes |
| V2 | `Recorder` with `FileDescriptorOutputOptions` over `StorageManager.openProxyFileDescriptor` | Yes, probe | **Measured:** the recorder accepts the proxy descriptor, records (9.47 s, 1 446 779 bytes reported by `Finalize`), and finalizes with error 0. The platform muxer issues random writes: 421 writes, 5 846 991 bytes written for a final length of 1 846 831 bytes, 70 writes at offsets below the end reached so far. First writes: `0+28`, `28+4068`, `4096+131072` three times, then small writes; from write 47 on the prefix starting at offset 28 is rewritten again and again. 0 reads, 0 `fsync`. The callback kept everything in memory (16 MiB cap, not reached) and discarded it. | No in the probe (memory only); a real implementation would need a random-access encrypted store |
| V3a | `VideoCapture.withOutput(customVideoOutput)`, a `VideoOutput` implementing only its one abstract method | Yes | **Measured:** `bindToLifecycle` fails with `IllegalArgumentException: CameraUseCaseAdapter$CameraException: java.lang.IllegalArgumentException: MediaSpec can't be null`. `VideoCapture` calls `getMediaSpec()`, a `@RestrictTo(LIBRARY)` default method, and requires a non-null value. Implementing it would use a restricted API, which lint (`RestrictedApi` as error, run on the module) and the developer's gate forbid. **Not usable with public API.** | n/a |
| V3b | `Preview` use case whose `SurfaceProvider` hands CameraX a `MediaCodec` encoder input surface; encoder output into `FragmentedMp4Muxer` per segment; each segment encrypted as one item object | Yes, full prototype | **Measured, works.** Details in Section 2. No on-screen viewfinder while recording: the one `Preview` use case is taken by the encoder. | No (checked by `leak_scan` after the probe: 0 findings, 0 new non-object files) |
| V4 | Camera2 directly with `MediaCodec` | No | Same encoder and muxer work as V3b, minus CameraX device handling. Not needed while V3b works. | No |

The lint gate the developer asked for: `android/app/build.gradle.kts` sets `RestrictedApi` as
an error and lint ran clean on the final debug sources (0 errors). During development lint
caught one restricted call in the prototype (`SurfaceRequest.getExpectedFrameRate`, used only
for a log line); it was removed. `VideoOutput.onSurfaceRequested(SurfaceRequest)` itself is
not restricted, but the pipeline cannot run on that method alone (V3a above), so the
prototype records with V3b.

## 2. V3b prototype, measured on the emulator

Configuration: H.264 (`video/avc`), encoder `c2.android.avc.encoder` (software), target
4 Mbit/s, 30 fps, I-frame interval 1 s, a sync frame requested at every 3 s boundary,
`FragmentedMp4Muxer` with 1 s fragments, one muxer per segment, 10 s clip, debug build
(unoptimized native code).

| Measure | Value |
|---|---|
| Resolution CameraX chose for the `Preview` request | 1280x960 (the back camera's largest size) |
| Frames encoded, recorded span | 312 frames, 11 974 ms (about 26 fps; the clip ran until the encoder drained) |
| Encoded bytes, bitrate | 4 955 192 bytes, 3.31 Mbit/s |
| Segments | 4: 913 630 / 1 394 839 / 1 440 378 / 1 213 501 bytes; 3 409 / 3 054 / 3 087 / 2 447 ms; 60 / 89 / 91 / 72 samples |
| Encrypt time per segment | 41 / 83 / 61 / 68 ms; about 19 100 KiB/s |
| Queue high-water mark | 1 segment waiting at most; capacity 2, cap 8 MiB per segment, largest segment 1 440 378 bytes |
| Dropped or late frames | Not observable at this layer: the encoder receives whatever the camera renders into its surface; frame counts per segment are consistent with 26 to 30 fps. No frame was refused by the muxer. |
| CPU | 7 830 ms of process CPU over 12 564 ms wall (62 percent of one core), whole process: camera, software encoder, muxer, encryption, verification sampler |
| Memory | PSS peak 73 149 KiB; native heap peak 22 776 KiB (the capture path alone peaks at about 18 500 KiB on this emulator) |
| Index object | written (`media_type` 3, prototype layout from `docs/PHASE2_DESIGN.md` 8.3.5) |
| Verification | every segment decrypted into memory, parsed by `MediaExtractor` over a `MediaDataSource`: `ftyp` present, `video/avc`, 1280x960, sample counts equal to the muxed counts (60 / 89 / 91 / 72), spans 3 376 / 3 028 / 3 054 / 2 413 ms, first frame decoded by `MediaCodec` (1 843 200 bytes, which is 1280x960 YUV 4:2:0) |
| Sync frames | The encoder flagged 2 / 4 / 4 / 3 key frames per segment; `MediaExtractor` reports 2 sync samples per segment. Both numbers are reported as logged. The mismatch is an open risk for the video phase (Section 5), because seeking inside a segment depends on which samples the container marks as sync. |
| Leak scan after the probe | 0 findings; the only new files are `.pvo` objects |
| Whole probe wall time | 18 972 ms including verification |

Low space at segment boundaries (developer amendment): implemented as "stop cleanly when
free space is below 64 MiB plus four times the largest segment seen"; on the emulator (about
4 GB free) the branch was never taken. The code path finishes the current segment if its
muxer closes, writes the index for the completed segments, and reports `LOW_STORAGE` with
the segment count. **Not exercised by a measurement**; the ballast test in
`CaptureFailureTest` covers only the photo precheck and the core's `ENOSPC` behaviour.

Observed limitations of the prototype:

- No on-screen viewfinder during recording (the encoder owns the only `Preview`). Showing
  one would need a `CameraEffect` with a `SurfaceProcessor` rendering the camera frame to
  both surfaces (an OpenGL ES copy per frame, app code), or a `SurfaceTexture` based copy;
  neither was built.
- Audio: not attempted in Phase 2. The stretch item (AAC from `AudioRecord`) was left
  for the video phase once the video-only path had been measured on both devices.
- Plaintext copies on the Kotlin side: `FragmentedMp4Muxer` works with Java byte buffers and
  copies samples (its default `setSampleCopyingEnabled(true)`), and the prototype collects
  each segment in a Java `ByteArrayOutputStream` before one native copy into guarded memory
  for encryption. The verification step reads whole segments back into a `ByteArray`
  (debug-only JNI). A production path would move the segment buffer to native memory.
- Timestamps: the encoder output `presentationTimeUs` is used as is (relative to the first
  frame); A/V sync was not a concern without audio.

## 3. V2 probe, measured

`Recorder` accepted the `ParcelFileDescriptor` from `openProxyFileDescriptor`
(`MODE_READ_WRITE`) and finalized with error 0. The write pattern of the platform MP4 muxer
is not sequential: 70 of 421 writes land below the end reached so far, the prefix from
offset 28 is rewritten repeatedly (the `moov` region as it grows), and three 131 072-byte
writes at 4 096, 135 168, and 266 240 recur each time. A sequential secretstream writer
cannot serve this; a random-write encrypted store would be needed, which is a new
construction the project would own (the Phase 1 design already rejected that for seeking;
the same argument applies). V2 is therefore not a path to full video. The probe wrote no
file (`leak_scan`: 0 findings, 0 new files).

## 4. Push-style core writer: not needed, and why

The question from the brief was whether the core needs a `begin`/`write`/`finish`/`abort`
writer for video. The prototype shows it would not help with the approved item header:
`payload_len` sits at the start of the plaintext, so the object cannot start before the
segment length is known, and the segment length is known only when the muxer closes
(D-014). The pull-style `pv_source` over the completed segment is enough, and encryption
never fell behind (queue high-water mark 1, 41 to 83 ms per 3 s segment). A push-style
writer becomes useful only if a future item header version moves `payload_len` to a
trailer, which would let a segment stream through encryption while it is muxed and cut the
per-segment memory from "whole segment" to "one fragment".

## 5. Recommended architecture for full video

1. **Capture:** CameraX with V3b (encoder surface as the `Preview` provider) plus a
   `CameraEffect`/`SurfaceProcessor` that mirrors the frame to the on-screen viewfinder, or
   the V4 Camera2 route if the processor turns out to cost more than CameraX saves. Keep
   `VideoOutput` out of the design unless CameraX makes `MediaSpec` part of the public
   surface.
2. **Encoding and muxing:** `MediaCodec` H.264 (H.265 where supported), `FragmentedMp4Muxer`
   per segment, 2 to 4 s segments starting on requested sync frames, exactly as prototyped.
3. **Storage:** one item object per segment (`media_type` 2) and one index object
   (`media_type` 3) per clip, as prototyped; the index layout needs a real design pass
   (durations, per-segment codec config, clip-level metadata, and what happens when the
   index object is missing or stale).
4. **Memory:** a native bounded segment buffer (`sodium_malloc`, cap per segment, two
   segments in flight) replacing the Java `ByteArrayOutputStream`; muxer output copied
   straight from the muxer's buffer into it through JNI.
5. **Audio:** AAC from `AudioRecord` into the same muxer as a second track; `RECORD_AUDIO`
   in the manifest from then on.
6. **Failure handling:** the photo table applies per segment; a clip whose index was not
   written is a set of orphan segments and needs a recovery rule (either delete them on
   open, or rebuild an index from segment headers, which argues for a segment sequence
   number inside the item header's reserved field).

Effort estimate (milestones, not hours): one milestone for the viewfinder mirror and the
native segment buffer; one for audio and A/V timing; one for the index design, recovery
rules, and the Windows-side tooling; one for device testing (phone, orientation changes,
calls and interruptions, low space, long clips) and the report. Four milestones.

Open risks: encoder throughput on low-end phones (the emulator used a software encoder
at 62 percent CPU; the one phone measured, the Xiaomi 2201116SG, used the hardware
`c2.qti.avc.encoder` at 56 percent CPU for the whole process at 1600x1200, Section 7;
lower-end devices remain unmeasured); the
`CameraEffect` surface processor is the least explored piece; thermal behaviour on long
clips; the index format and recovery rules; `FragmentedMp4Muxer` is `@UnstableApi` and may
change between media3 releases; **sync-frame marking**: the encoder reported 2 / 4 / 4 / 3
key frames per segment on the emulator while `MediaExtractor` reported 2 sync samples per
segment after muxing, so either the muxer does not carry every key-frame flag into the
container or the extractor reads them differently. Seeking inside a segment relies on those
marks, so the video phase must settle which flags the muxer writes (`trun` sample flags)
and whether the requested sync frames at segment boundaries are the ones the container
sees. Also for Phase 3: the release variant carries `media3` 1.9.0 through `camera-video`
while the debug variant uses 1.11.1 for the prototype, and the debug APK grew from 4.1 MB
to 17.6 MB with CameraX, media3, and guava; the viewer phase should decide which media3
line the app standardises on and whether `camera-video` can be kept out of the release
variant.

## 6. Placement recommendation

Full video belongs in a later phase, not a Phase 2 follow-up. The photo path is complete
and tested; video has a working pipeline shape, measured on the emulator and on the Xiaomi
(V3b with the hardware encoder, Section 7), but three design items (viewfinder mirror,
native buffering, index and recovery rules) and the audio track are each a milestone.
Recommended: Phase 3 starts with the viewer and the
seeking decision that the Phase 1 design already assigned to it, and full video capture is
scheduled as its own phase (or the second half of Phase 3) once the viewer can play
segmented clips, because capture and playback share the index format.

## 7. Xiaomi 2201116SG, measured (debug build, 2026-10-13, T3)

Same prototype, same actions (`open,video_probe,list,leak_scan` and
`open,v2_probe,leak_scan`), run over adb on the phone (Android 13, MIUI 14, arm64).

| Measure | Phone | Emulator (for comparison) |
|---|---|---|
| V3a custom `VideoOutput` | same bind failure, "MediaSpec can't be null" | same |
| Variant used | V3b, no on-screen viewfinder | same |
| Resolution CameraX chose for the `Preview` request | 1600x1200 (the camera's largest preview-class size; stills are 3264x2448) | 1280x960 |
| Encoder | `c2.qti.avc.encoder`, a hardware (Qualcomm) encoder; format reports profile 8, bitrate-mode 1, bitrate 4 800 000 | `c2.android.avc.encoder`, software |
| Frames, recorded span | 229 frames, 11 495 ms (about 20 fps delivered to the encoder surface) | 312 frames, 11 974 ms |
| Encoded bytes, bitrate | 6 897 840 bytes, 4.80 Mbit/s (the encoder settled on 4.8 rather than the requested 4.0) | 4 955 192 bytes, 3.31 Mbit/s |
| Segments | 4: 1 749 074 / 1 805 771 / 1 882 875 / 1 464 649 bytes; 3 045 / 2 994 / 3 095 / 2 342 ms; 60 / 60 / 62 / 47 samples | 4 of 0.9 to 1.4 MB |
| Encrypt time per segment | 139 / 143 / 152 / 122 ms; about 12 100 KiB/s (debug build, unoptimized native code) | 41 to 83 ms, about 19 100 KiB/s |
| Queue high-water mark | 1 segment waiting; largest segment 1 882 875 bytes, cap 8 MiB | 1 |
| CPU | 7 090 ms over 12 491 ms wall (56 percent of one core, whole process) | 62 percent |
| Memory | PSS peak 136 339 KiB; native heap peak 47 501 KiB; after the probe `memory:` reported java 14 281 KiB, native heap 22 456 KiB, PSS 158 651 KiB with the capture screen rebound | PSS peak 73 149 KiB, native peak 22 776 KiB |
| Verification | all four segments: `ftyp`, `video/avc`, 1600x1200, sample counts equal to the muxed counts, spans 3 011 / 2 961 / 3 061 / 2 309 ms, first frame decoded (2 880 000 bytes, 1600x1200 YUV 4:2:0) | same, 1280x960 |
| Sync frames, encoder against extractor | 2 / 2 / 3 / 2 against 2 / 2 / 2 / 2: only segment 2 differs | 2 / 4 / 4 / 3 against 2 / 2 / 2 / 2 |
| Leak scan after the probe | 0 findings, 0 new non-object files | same |
| Dropped frames at the camera layer | not observable at this layer (see Section 2); 20 fps delivered against a 30 fps encoder setting suggests the camera or the surface path dropped frames on the phone, which the real implementation should measure with `onFrameAvailable` timestamps | n/a |
| Low-space stop during a recording | not exercised (phone storage far above the threshold) | not exercised |

V2 probe on the phone: `Recorder` accepted the proxy descriptor and started; the phone's
default recording quality writes much faster than the emulator (16 424 048 bytes in
6 642 ms, about 19.8 Mbit/s), so the probe's 16 MiB in-memory cap was reached after 6.6 s
and the callback refused the next write with `ENOSPC`; the recorder finalized with error 3
(`MediaMuxer operation failed`). Pattern before the cap: 469 writes, 20 333 971 bytes
written for a final length of 16 744 448, 49 writes below the end reached so far, the same
`0+28`, `28+4068`, `4096+131072` prefix and the same repeated rewrites from offset 28 as on
the emulator. 0 reads, 0 `fsync`, descriptor released, no file written (`leak_scan`
0 findings, 0 new files). The conclusion of Section 3 holds on the phone.

Not measured anywhere: the audio stretch item, and the low-space stop during a recording.
