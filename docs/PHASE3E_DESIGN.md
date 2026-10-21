# Phase 3E design: video capture as a feature

Status: implemented (Milestones C to E done 2026-10-21; results in
`docs/PHASE3E_TEST_REPORT.md`; the orientation amendment of 4.2 came out of T3). Approved by the developer 2026-10-21 at the Milestone B checkpoint, with the
Milestone A trials complete on the emulator and the Xiaomi. CP-1, CP-4, CP-6, CP-7, and
CP-8 as written; CP-2 and CP-3 with three conditions, applied below: the writer covers
H.264 now and AAC in 3F, no other codec, and only the boxes Section 4.4 lists; every new
clip passes a compatibility gate in T2 and T3 (the framework `MediaExtractor` and media3
`FragmentedMp4Extractor` parse every segment with key-frame counts equal to the index,
Section 7); T1 carries golden vectors for the init segment and one fragment (4.10, 7).
CP-5 confirmed: the Xiaomi decides 1920x1080 at 30 fps, the emulator records at its 720p
fallback and T2 asserts no frame rate. The wrapper landing seeks on `tfdt` clips (2.4) is
traced in Milestone D or recorded as a known limit with a T2 test that pins the current
behaviour; the design does not rely on it. Items marked **CP-n** are the
choices this document makes beyond the brief, or where the brief leaves the default to
the design (Section 9). "Measured" names a run in `docs/PHASE3E_PROGRESS.md` (Milestone A)
or in the Stage 3C and 3D records; everything else is design intent to be confirmed by
Milestones C to E.

Facts from earlier stages are not repeated: `docs/VIDEO_CAPTURE_FEASIBILITY.md` (the V3b
prototype), `docs/PHASE3C_DESIGN.md` (settings, index v1, the seek rule),
`docs/PHASE3D_DESIGN.md` (viewer, gallery, sweep, recording gate), `docs/PHASE3B_DESIGN.md`
(guarded buffers, cancellation, lock rules, the heap investigation procedure). Nothing
here changes the version 1 object format, the item header, envelope providers 1 to 3, or
index v1; the index fields are filled as defined.

## 1. Summary

The debug prototype becomes the release capture path. The capture screen gains a video
mode with a live viewfinder: one camera stream is split in OpenGL to the on-screen
preview and the encoder surface through a public `CameraEffect` (measured on both
devices in both split routes: on the Xiaomi 30 fps at 1920x1080 reach the viewfinder and
the encoder, the same rate as the prototype without a viewfinder). The encoder output goes
into a fragmented MP4 writer in the core that assembles each segment inside guarded
native memory, writes `tfdt` per fragment, a `sidx` after the `moov`, true sample
durations, and the rotation in `tkhd`, so a new clip plays and seeks through the bare
`FragmentedMp4Extractor` with no wrapper and no Java copy of the segment on the way to
encryption. The viewer tells new segments from old by the `tfdt` it already looks for and
keeps the 3D wrapper for the two regression clips. Stop, lock, low space, encoder error,
and a full encrypt queue all end a recording the same way: the current segment is
finished and encrypted, the index written, the gate released. The read-ahead gets a byte
target of two segments. The heap investigation runs on the Xiaomi with the profiler.

## 2. Milestone A results (measured)

### 2.1 Gates

`git status` clean apart from the untracked brief. T1 18/18 in Debug and Release, 0 build
warnings. T2 69 tests, 0 failures, 1 skipped (`LockBoundKeyTest`, as in every stage since
3A), 346 s. Lint 0 errors. The trial code (debug source set, Section 2.6) built with lint
at 0 errors: the CameraX effect API it uses is public.

### 2.2 The pinned libraries (javap over the cached AARs)

- `FragmentedMp4Muxer` 1.11.1 accepts `Mp4OrientationData` through `addMetadataEntry`
  and writes it into the `tkhd` matrix (measured: `tkhd_rotation=90` in every trial
  segment, `MediaExtractor` reports `rotation=90`, ExoPlayer reports
  `format_rotation=90` from the container). Its box writer has no `tfdt` and no `sidx`.
  Its `tfhd` carries flag 0x1 with an absolute `base_data_offset`, so any pass that
  inserts bytes must rewrite it. Its sample path copies every sample into Java byte
  buffers (`setSampleCopyingEnabled` defaults to true; `LinearByteBufferAllocator`) and
  writes whole fragments to the channel, so about one fragment of samples lives in the
  Java heap at any time whatever channel it writes to.
- `CameraEffect` (abstract, protected constructor `targets, executor, SurfaceProcessor,
  errorListener`), `SurfaceProcessor`, `SurfaceOutput` (`getSurface`, `getSize`,
  `updateTransformMatrix(float[], float[])`, `close`), `UseCaseGroup`,
  `Preview.Builder.setTargetFrameRate`, and `ResolutionSelector` are public API in
  `camera-core` 1.6.2. The `TRANSFORMATION_*` and `OUTPUT_OPTION_*` constants and
  `SurfaceRequest.getExpectedFrameRate` are restricted and are not used.

### 2.3 Viewfinder plus encoder (P3E-1)

Trial code `GlSplitter.kt`: an EGL context on an own thread, the camera renders into a
`SurfaceTexture`, and every frame is drawn once per output (the viewfinder and the encoder
input surface, which gets the camera timestamp as its presentation time). Two routes:

- `effect`: a `CameraEffect` with target `PREVIEW`; its `SurfaceProcessor` gets the camera
  input and the `PreviewView` surface as the output; the encoder surface is a second
  output. The preview output is drawn with the matrix CameraX supplies
  (`updateTransformMatrix`), which turns and crops it for the screen; the encoder gets
  the frame as delivered.
- `own`: the Preview output is an own `SurfaceTexture`; the viewfinder is an own
  `SurfaceView` added over the preview, turned in GL by the rotation CameraX reports.

Emulator (`Pixel_8_API_35`, software GL translator "SwiftShader", `c2.android.avc.encoder`,
10 s clips, a 1920x1080 request gave 1280x720, the largest 16:9 size of the virtual
camera; rotation 90 reported for the stream):

| Route | Camera frames delivered | Encoder frames drawn | Viewfinder frames drawn | Encoder output | Delivered rate | CPU (whole process) | PSS peak |
|---|---|---|---|---|---|---|---|
| direct (V3b, no viewfinder) | 15 fps measured by the meter | n/a | n/a | 122 frames in 8.6 s | 14 fps | 91 percent of one core | 80 MiB |
| effect | 87 (7 fps) | 87 | 87 | 56 frames in 8.9 s | 6 fps | 112 percent | 75 MiB |
| own | 83 (7 fps) | 83 | 82 | 63 frames in 9.1 s | 7 fps | 109 percent | 76 MiB |
| effect at 1280x720 with the duration fix | 119 (10 fps) | 118 | 118 | 91 frames in 9.8 s | 9 fps | | |

Both routes work end to end: every camera frame reached both outputs with no EGL error,
the clips recorded, played, and seeked (2.4), and the preview output was the turned
720x1280 surface of the `PreviewView`. The emulator renders GL in software and is CPU
bound at this size, so the halved rate against the direct route says nothing about the
phone.

Xiaomi 2201116SG (Adreno 619, `c2.qti.avc.encoder`, debug build, 10 s clips, a neutral
lit scene, rotation 90 reported for the stream; logs `build/p3e/phone/P*-*.txt`):

| Route, size, bitrate asked | Camera frames delivered | Encoder and viewfinder frames drawn | Encoder output | Bitrate settled | Largest segment | CPU (whole process) | PSS peak (debug) |
|---|---|---|---|---|---|---|---|
| effect, 1920x1080, 8 Mbit/s | 289 (**30 fps**) | 289, 289 | 284 frames in 9.48 s, **30 fps** | 8.00 Mbit/s | 3 538 757 bytes | 119 percent of one core | 261 MiB |
| effect, 1280x720, 5 Mbit/s | 285 (30 fps) | 285, 285 | 283 in 9.45 s, 30 fps | 5.07 Mbit/s | 2 041 439 | 117 percent | 195 MiB |
| own, 1920x1080, 8 Mbit/s | 291 (30 fps) | 291, 291 | 286 in 9.55 s, 30 fps | 8.12 Mbit/s | 3 489 653 | 123 percent | 233 MiB |
| direct (no viewfinder), 1920x1080, 8 Mbit/s | meter 29 fps | n/a | 266 in 9.21 s, 29 fps | 8.00 Mbit/s | 3 625 824 | 102 percent | 143 MiB |
| direct, 1280x720, 5 Mbit/s | meter 30 fps | n/a | 283 in 9.45 s, 30 fps | 5.10 Mbit/s | 2 029 076 | 103 percent | 142 MiB |
| effect, 1920x1080, own writer | 284 (29 fps) | 284, 284 | 280 in 9.45 s, 30 fps | 8.05 Mbit/s | 3 555 974 | 121 percent | 239 MiB |
| effect, 1920x1080, native buffer, duration fix | 282 (29 fps) | 282, 282 | 277 in 9.31 s, 30 fps | 8.08 Mbit/s | 3 416 613 | 132 percent | 250 MiB |

On the phone the split costs no frames: the camera delivers 30 fps at 1920x1080 with the
30 fps target, every frame is drawn to both outputs, and the encoder keeps 30 fps, the
same as the direct route. It costs about 20 percent of a core and 90 to 120 MiB of PSS
over the direct route (the GL context and the second set of 1080p surface buffers; the
split against the viewfinder buffers was not separated). The `PreviewView` received its
1080x1920 output turned by the CameraX matrix (route `effect`); the own `SurfaceView`
received 767x1365 turned in GL (route `own`). Encrypt 280 to 356 ms per 3.3 to 3.5 MB
segment in the debug build (7.5 to 12.4 MB/s, both buffer paths), queue high-water mark
1 in every split run.

### 2.4 Container fixes (P3E-3) and playback through the standard path (P3E-4)

Trial code `Mp4Fix.kt` (a pass over a finished muxer segment: `tfdt` per `traf`, the
`trun` data offset and the `tfhd` base data offset rewritten, a `sidx` after the `moov`,
optionally the true duration of the last sample of each fragment), `Fmp4Writer.kt` (an
own writer sketch, Section 2.5), `VideoProbeActivity` with `plain=1` (the bare
`FragmentedMp4Extractor`, no seek map, no rotation from the index).

| Clip (emulator) | Container | Box walk | Playback with the bare extractor |
|---|---|---|---|
| effect, 3 segments, 8.9 s | muxer plus `tfdt` and `sidx` (136 bytes per 3-fragment segment, 1 to 19 ms per pass) | `tkhd_rotation=90 sidx_refs=3 tfdt=[0,119040,249079]` (90 kHz), `trun` flags 0x701 with per-sample sync flags as before; framework, media3, and index key counts 3/3/3 | first frame 1119 ms, rotation 90 from the container applied by the codec (`size 720x1280 unapplied_rotation=0`), 2 transitions, gaps 33, 33 ms, 0 dropped, **5 of 5 seeks on the chosen key frame** (segment starts at 0, 4067, 8527 ms; in-segment targets 1500 and 5567 ms landed on the key frames at 1322 and 5397 ms, `frame_ms` equal to `key_ms`), latency 262 to 557 ms |
| own route, 3 segments, 8.4 s | the same | the same shape (`tfdt=[0,111366,286258]`) | 5 of 5 seeks on the key frame (in-segment 1237 and 5877 ms), 4 dropped frames |
| effect at 720p with the true last durations | plus the last sample of each fragment given the gap to the next | container sample times equal the encoder key offsets to within one 90 kHz tick (1 133 522 against 1 133 532 us; 2 369 455 against 2 369 464) in every segment | 6 of 6 seeks on the key frame (in-segment 1133, 4825, 8598 ms), latency 147 to 547 ms |
| `tfdt` only, no `sidx` | `sidx_refs=-1 tfdt=[0,129244,235129]` | | the three segment-start seeks land; **every in-segment seek lands at the segment start** (target 1500 ms gave position 0, target 5520 ms position 4020, target 9123 ms position 7623): without a `sidx` the extractor reports the stream unseekable, as 3C found, so the standard path needs the `sidx` |
| the same effect clip through the 3D wrapper (`plain=0`) | | | 5 of 5 seeks on the key frame, the same landings as the bare extractor |
| native buffer channel (2.6), `tfdt` and `sidx` | the same shape | the same | 6 of 6 seeks on the key frame, first frame 941 ms |
| own writer (2.5), 3 segments, 9.9 s | `Fmp4Writer.kt` | `tkhd_rotation=90 sidx_refs=3 tfdt=[0,1216894,2468822]` (microseconds), `trun` flags 0x701 with per-sample sync flags; media3 sync times and the framework count equal the encoder key offsets exactly; `MediaExtractor` reads `rotation=90`, decodes the first frame | first frame 968 ms, rotation applied by the codec, 2 transitions, gaps 33, 33 ms, 0 dropped, **6 of 6 seeks on the key frame** (in-segment 1216, 4852, 8687 ms), latency 192 to 529 ms |

Xiaomi, hardware decoder `c2.qti.avc.decoder`, every clip through the bare extractor:

| Clip | Container | Playback |
|---|---|---|
| effect 1920x1080, muxer plus `tfdt` and `sidx` | `tkhd_rotation=90 sidx_refs=3 tfdt=[0,102139,204278]` (90 kHz), key counts 3/3/3/3 | first frame **771 ms**, `size 1080x1920 unapplied_rotation=0`, surface sample luma 90 with 3073 colours, gaps 33, 33 ms, 290 frames, 0 dropped, **6 of 6 seeks on the key frame** (in-segment 1134, 4539, 7910 ms), latency 249 to 654 ms, one live buffer (3.5 MB), opens equal closes, PSS peak 144 MiB |
| effect 1280x720, the same | `tfdt=[0,96130,192261]` | first frame 629 ms, 0 dropped, 6 of 6 seeks (1068, 4272, 7443 ms), latency 145 to 379 ms, PSS 159 MiB |
| effect 1920x1080, own writer | `tfdt=[0,1134879,2269758]` equal to the key offsets exactly; `MediaExtractor` `rotation=90` | first frame 865 ms, gaps 33, 16 ms, 0 dropped, 6 of 6 seeks (1134, 4572, 7977 ms), latency 277 to 674 ms, PSS 119 MiB |
| effect 1920x1080, native buffer with the duration fix | `tfdt=[0,99135,201274]`, encrypted through `DIRECT` | first frame 968 ms, 0 dropped, 6 of 6 seeks (1101, 4506, 7910 ms), latency 308 to 696 ms, PSS 139 MiB |

The last row was not expected: the wrapper adds the fragment start to the sample times of
a fragment it was seeked to, which with `tfdt` present should have doubled them. The
measured frames land right; the mechanism was not traced in Milestone A and the design
does not rely on it (the wrapper serves old clips only, 4.5).

### 2.5 Own writer sketch

`Fmp4Writer.kt` writes `ftyp`, `moov` (`tkhd` matrix, `avcC` from the encoder parameter
sets, `trex`), `sidx`, and per fragment `moof` (`mfhd`, `tfhd` with default-base-is-moof,
`tfdt`, `trun` with per-sample duration, size, and flags) and `mdat`, with microsecond
timescale, every fragment starting on a key frame, the last sample of a fragment given the
gap to the next, and the encoder Annex B samples turned into 4-byte length prefixes. About
250 lines of Kotlin. Result on the emulator (2.4, last row): the segments read back
through the box walk, the framework extractor, and media3 with the key counts of the
encoder and the index agreeing, the container times equal to the index times exactly
(microsecond timescale), the first frame decoded, and the clip played and seeked through
the bare extractor with every seek on the chosen key frame. Encrypt 62 ms for a 728 KB
segment on the emulator (the sketch still hands a Java array to the encrypt).

### 2.6 Native buffer channel (P3E-2)

Trial: `NativeSegmentBuffer`, a `WritableByteChannel` over a direct buffer in
`sodium_malloc` memory (`nativeProbeAllocDirect`, debug JNI), handed to `encryptItem` in
place (path `DIRECT`) and freed after. Result (emulator, `tfdt` and `sidx` post-pass
over the native buffer): every segment encrypted through the `DIRECT` path (17 to 127 ms
for 371 to 665 KB, 9 100 KiB/s in the debug build), the post-pass 1 to 42 ms per segment
over the direct buffer, the clip played and seeked through the bare extractor with 6 of 6
seeks on the key frame; `leak_scan` 0 findings after every trial. Whatever the channel,
the media3 muxer keeps about one fragment of samples in Java byte buffers before it
writes (2.2), which is why the own writer is proposed for the feature (4.3).

### 2.7 Leak scans and the phone state

`leak_scan` 0 findings after every trial on both devices (phone: 72 to 97 files
scanned, no new non-object file). The seven trial clips (21 segments, 7 indexes) stay on
the phone beside the two regression clips until the Milestone E cleanup, which deletes
them through the app and keeps the two regression clips and one 3E clip (brief
Section 6). The emulator vault holds the eight emulator trial clips.

## 3. Decisions proposed (brief Section 3)

| ID | Decision | Proposal |
|---|---|---|
| P3E-1 | Viewfinder while recording | the `effect` route (`CameraEffect` with a `SurfaceProcessor`, 4.2); the `own` route stays in the trial code as the fallback (CP-1) |
| P3E-2 | Segment buffering | the segment is assembled by the core writer inside one guarded buffer per segment and encrypted from it; no Java copy of a sample or a segment (4.3) (CP-2) |
| P3E-3 | Container fixes | an own fragmented MP4 writer in the core: `tkhd` rotation, `tfdt`, `sidx`, true durations, microsecond timescale (4.4) (CP-3); the muxer-plus-post-pass route is the measured alternative |
| P3E-4 | Playback of old and new clips | per segment: `tfdt` present means the bare extractor and the container rotation (0 for feature clips, which are encoded upright, 4.2); absent means the 3D wrapper and the index rotation (4.5) (CP-4) |
| P3E-5 | Capture settings | 1920x1080 asked with a closest-lower fallback, 30 fps target, 8 Mbit/s, H.264 hardware encoder (4.6) (CP-5); the emulator falls back to 1280x720 and records what it gets |
| P3E-6 | Stop conditions | one stop path for the stop button, every lock trigger, low space, encoder or camera error, and a full queue; bounded by a 4 s wait on the lock (4.7) (CP-6) |
| P3E-7 | Process killed mid-recording | orphans, swept at the next unlock; the item header reserved bytes stay zero (known limit, Phase 6) |
| P3E-8 | Read-ahead | `DefaultLoadControl` with a byte target of two segments and size before time (4.8) (CP-7) |
| P3E-9 | Heap investigation | the 3B procedure with the Rabbit profiler on the Xiaomi debug build (4.9) |

## 4. Architecture

### 4.1 Capture screen

`CaptureActivity` keeps its photo path and gains:

- A mode switch (Photo, Video) above the shutter row. In video mode the shutter becomes
  Record and, while recording, Stop; Flip is disabled while recording; an elapsed-time
  label (`m:ss`) and a red indicator sit over the preview; the status line shows the
  outcome of the last recording (duration, segment count) or the stop reason.
- `FLAG_SECURE` as today; the `PreviewView` stays the viewfinder in both modes. In video
  mode the use cases are `Preview` (through the split effect while recording) and
  `ImageCapture` stays unbound, so a photo cannot be taken mid-recording; the mode switch
  rebinds.
- Video mode requires `UNLOCKED`; Record takes `VideoItems.recordingGate` (waiting for a
  running sweep, as `V3Probe` does) and holds it from the first encoder frame to the index
  write. The recording survives an activity recreation only as far as the process-scoped
  recorder (4.2) does: the activity rebinds to it on `onStart`; a stop while the activity
  is gone ends the recording through the lock rules (4.7).
- On stop: the recorder finishes (4.7), the status line shows the clip duration, and the
  gallery shows the new clip on return (the gallery lists on `onStart`).
- Scripted actions (debug `ScriptActivity` through the capture screen): `record=<ms>`
  records from the feature path and waits; `record_stop_hook=<lock|space|encoder|queue>`
  makes the next recording hit that stop (4.7); `video_trial` stays as the Milestone A
  probe until Milestone D retires it (CP-8).

### 4.2 Recording pipeline

```
CameraX Preview + CameraEffect(PREVIEW) -> SurfaceProcessor (GlSplitter) -> PreviewView surface (turned by the CameraX matrix)
                                                                          -> MediaCodec H.264 encoder input surface (as delivered)
encoder output buffer (direct) -> core segment writer, guarded buffer (4.3, 4.4)
segment closed on the third key frame -> pv_vault_encrypt from the buffer, on the encrypt thread
after the last segment -> index v1 -> encrypt; gate released
```

- `VideoRecorder` (main source set, process scoped like `CaptureSession`): owns the
  splitter, the encoder, the drain thread, the pending-segment counter, and the stop
  state. `GlSplitter` moves from debug to main unchanged. The drain loop is the
  prototype's: a sync request one GOP after the last key frame, segments cut on the third
  key frame, the first segment waits for a key frame, sample times inside a segment start
  at zero, the index keeps the clip offsets (3C rules, D-029).
- The encoder is configured at the resolution of the camera request with the settings of
  4.6; the `Preview` carries the `ResolutionSelector` and the frame-rate range.
- At most two finished segments wait for encryption; a third stops the recording with
  `QUEUE_FULL` (4.7). Encryption runs on the process-scoped `pv-encrypt` thread that
  photos use, so a photo queued before the mode switch and the segments share one queue.
- **Orientation (found in T3, Milestone E).** The camera transform of the device sits
  inside the `SurfaceTexture` matrix (measured on the Xiaomi: a transpose with flips, a
  quarter turn), and `TransformationInfo.hasCameraTransform` is true. The first build
  drew the encoder with that matrix and recorded the stream turn (90) as well, so the
  clip played sideways: the frame was upright before the codec turned it again. The
  direct route of 3D never met this because an encoder surface ignores the buffer
  transform flag. The recorder now sizes the encoder to the turned stream (width and
  height swapped for a quarter turn) and draws it with the matrix CameraX supplies for
  the viewfinder output (`SurfaceOutput.updateTransformMatrix`: the camera transform,
  the turn to the display, the crop, the mirroring, resolved by CameraX for this device),
  so the clip holds what the viewfinder shows; the item headers, the index, and the
  `tkhd` carry rotation 0. A recording started with the phone held landscape gives a
  landscape clip. Frames that arrive before the viewfinder output exists are not drawn
  to the encoder (a few milliseconds at the start).

### 4.3 Segment buffering (P3E-2; CP-2)

- One guarded buffer per segment, allocated by the core writer (`pv_secure_alloc`, cap
  from the settings: 8 MiB as today, raised if a 1080p segment comes near it in T3). The
  writer appends every encoder output buffer straight from its direct `ByteBuffer`
  (`GetDirectBufferAddress`, one `memcpy` into the segment buffer, with the start codes
  turned into length prefixes on the way). No Java array holds a sample or a segment.
- Three segment buffers can exist at once: the one being written and two finished ones
  waiting for or in encryption. A fourth is never allocated (the queue-full stop, 4.7).
- The buffer is wiped and freed after the encrypt call returns, on every path (the
  writer handle is closed in a `finally`; a stop drops the open segment through the same
  close; `pv_secure_free` wipes).
- The index bytes (a few hundred bytes) cross as a Java array as today, wiped after the
  encrypt.

### 4.4 Container: the core segment writer (P3E-3; CP-3)

The feature writes its own fragmented MP4 in the core (`src/vault_core/src/segment.cpp`,
portable, no media3), the shape of the `Fmp4Writer.kt` sketch:

- `ftyp` (`iso6`, compatible `isom iso6 avc1 mp41`), `moov` with `mvhd`, `trak` (`tkhd`
  with the rotation matrix and the picture size, `mdia` with `mdhd` at timescale
  1 000 000, `hdlr`, `minf`, `stbl` with `stsd`/`avc1`/`avcC` from the encoder SPS and PPS
  and empty `stts`, `stsc`, `stsz`, `stco`), `mvex`/`trex`.
- `sidx` after the `moov`: one reference per fragment (size, duration, SAP type 1).
- Per fragment `moof` (`mfhd`, `traf` with `tfhd` default-base-is-moof, `tfdt` version 1
  with the first sample time, `trun` flags 0x701 with duration, size, and flags per
  sample, data offset to the `mdat` payload) then `mdat`. A fragment starts on every key
  frame (one fragment per GOP, as 3C measured for the muxer); the last sample of a
  fragment gets the gap to the next key frame, the last sample of the segment the frame
  period, so container times equal the index times exactly.
- Layout inside the guarded buffer, written once with no second pass: the `moov` first; a
  `sidx` slot sized for the most fragments a segment can hold; per fragment a `moof` slot
  sized for the most samples a fragment can hold (bounded at 1024; a fragment that would
  exceed it is closed early at the next key frame or reported full), the samples appended
  after it as they arrive, and at fragment close the real `moof` written into the slot
  and the `mdat` moved back to follow it (`memmove` inside the buffer, under a millisecond
  per megabyte). At segment finish the `sidx` is written into its slot and the fragments
  moved back once. The buffer then holds a plain file image with no padding box.
- Timescale 1 000 000: the encoder gives microseconds, the index records microseconds,
  and the 90 kHz rounding of the muxer trial (one tick) goes away.
- The audio seam for 3F: a second track in `moov` and a second `traf` per `moof`; the
  writer takes a track list at creation, with one entry in 3E.

Alternative measured and not chosen: the media3 muxer with the post-pass of 2.4 (works
end to end on the emulator; costs a release dependency on `media3-muxer`, a Java copy of
every fragment inside the muxer, and a second pass with offset rewriting per segment).

### 4.5 Playback: new and old clips (P3E-4; CP-4)

- `SegmentSeekMap.build` already reads a `tfdt` when present. The data source records
  per opened segment whether its first fragment carries a `tfdt`; the extractor factory
  then returns the bare `FragmentedMp4Extractor` for such a segment (default flags, so
  the `sidx` makes it seekable and the container rotation reaches the codec), and the 3D
  `SeekableSegmentExtractor` with the index rotation otherwise. The viewer, the thumbnail,
  the gallery, delete, and the sweep are otherwise unchanged.
- `VideoFirstFrame` parses the `tfhd` flags it needs already (base data offset present
  or default-base-is-moof) and skips the `sidx` on its way to the first `moof`; it works
  on both shapes (measured for the muxer shape; the own-writer shape is checked in T2).
- The two regression clips on the phone (`3c55a432...`, `39b46d41...`) have no `tfdt` and
  take the wrapper path; T3 plays both.

### 4.6 Capture settings (P3E-5; CP-5)

| Setting | Value | Measured (2.3) |
|---|---|---|
| Size asked | 1920x1080 through a `ResolutionSelector` (16:9, closest lower then higher) | the Xiaomi delivers 1920x1080 at 30 fps with the viewfinder live, both split routes; the emulator offers 1280x720 at most and takes the fallback |
| Frame rate | target range 30 to 30 through `setTargetFrameRate`; the encoder is configured with the target rate (the sync requests are time based, D-029, so the configured rate only steers bitrate control and the 10 s backstop); the index records the delivered rate of the encoder output | 30 fps delivered and 30 fps encoded in every phone run; 7 to 11 fps on the emulator through the trial split (software GL, CPU bound), 23 to 28 fps through the feature path with the test viewfinder |
| Codec | H.264, the hardware encoder the platform picks (`c2.qti.avc.encoder`, profile 8) | as 3C |
| Bitrate | 8 Mbit/s at 1080p (the encoder settled at 7.99 to 8.12); 5 Mbit/s when the fallback gives 1280x720 or smaller (settled at 5.07 to 5.10) | segments of 3.3 to 3.6 MB at 1080p, 2.0 MB at 720p, for 3.2 to 3.4 s |
| Segment cap | 8 MiB per segment, as the prototype; a cap hit stops the recording with `SEGMENT_FULL` | the largest trial segment was 3.6 MB; a busy scene at 8 Mbit/s stays under 4 MB for the 3 s target |
| GOP, segment, fragment | 1 s GOP by time-based sync request, 10 s backstop, three GOPs per segment cut on the encoder key frame, one fragment per GOP | unchanged from 3C (D-029), confirmed by the key counts of every trial segment |

The brief's rule asks for 1080p only if both devices deliver at least 25 fps there. The
emulator cannot: it offers no 1080p stream and its software GL gives 7 to 11 fps through
the split at any size, 15 fps direct. The proposal takes the phone as the deciding device
(CP-5): the size is a request with a fallback, so the emulator records at 1280x720 at the
rate it gets, and T2 asserts the container and index rules, not a rate. **To confirm at
the checkpoint.**

### 4.7 Stop and failure rules (P3E-6, P3E-7; CP-6)

One stop path, `VideoRecorder.stop(reason)`: no new segment is started; the encoder is
signalled end of stream; the drain loop reads to the end-of-stream buffer (bounded by a
2 s wait as the prototype), closes the open segment, queues it for encryption, waits for
the encrypt jobs, writes the index over every encrypted segment, releases the gate, and
reports `(reason, duration, segments)`. The current segment is finished where the stream
ends (a segment ends anywhere; it starts on a key frame).

| Situation | Behaviour |
|---|---|
| Stop button | `stop("user")` |
| Lock (background, screen off, idle, manual) | `LockController.lock()` calls `stop("lock")` and waits up to 4 s (`RECORD_STOP_MS`) before `captures.drain` as today; if the wait expires, the open segment buffer is dropped (wiped), the index is written over the segments already encrypted, and the lock proceeds. The index write and the last encrypt are inside the wait. The gate is released in a `finally` |
| Free space below `CaptureSession.LOW_SPACE_BYTES` plus four times the largest segment seen, checked at every segment start | `stop("LOW_STORAGE")` with a message |
| Encoder error (`MediaCodec.CodecException`), camera error (`CameraState` error while recording), effect error | `stop(<name>)`; the segments encrypted so far are indexed |
| Encrypt failure of a segment | the recording stops with the status; segments before it are indexed; the failed segment is not named |
| Third finished segment while two wait | `stop("QUEUE_FULL")` |
| Process killed | segment objects without an index stay; the sweep after the next unlock removes them (3D rule). No recovery in 3E; a clip ID in the reserved item header bytes is the Phase 6 item |
| MIUI camera case | the camera error or the background lock, whichever arrives first, both through `stop` |
| Recording shorter than one key frame | no segment, no index, status "nothing recorded" |

### 4.8 Read-ahead (P3E-8; CP-7)

`DefaultLoadControl.Builder().setTargetBufferBytes(2 * largest segment of the index,
at least 4 MiB).setPrioritizeTimeOverSizeThresholds(false)` with the 2000/4000/500/1000 ms
durations of 3D: the loader pauses when the sample queue holds about two segments of
bytes, whichever threshold is reached first. Measured on a 60 s clip on both devices
(opens and closes over time, the peak of the live guarded buffers, PSS); the design
records the bound reached. The sample queue is still media3 memory the app does not
wipe (threat model 10.3).

### 4.9 Heap investigation (P3E-9)

As `docs/PHASE3B_DESIGN.md` Section 10 with the Rabbit profiler: debug build on the
Xiaomi, `open,capture_photo_n=10,memory` on a neutral scene with Java allocation
recording (stacks for arrays above 1 MB), `am dumpheap` before and after the scripted
`gc`, dumps under `D:\pv-heap`, deleted after reading. The report names the allocating
class and method, the retention, whether image data is in the array, and the public
CameraX settings reviewed; a capture change only through a public setting with T2 and
the phone captures green.

### 4.10 Core additions (brief 4.7)

Additive, portable, T1-tested. Error codes after 29:

```c
#define PV_ERR_SEGMENT_FULL 30       // the sample, fragment, or sidx slot does not fit
#define PV_ERR_SEGMENT_STATE 31      // a call out of order (sample before a key frame, after finish)
#define PV_SEGMENT_TIMESCALE 1000000u
#define PV_SEGMENT_MAX_FRAGMENTS 64u
#define PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT 1024u

typedef struct pv_segment_writer pv_segment_writer;

// Starts a segment: a guarded buffer of cap bytes, the H.264 track from the
// parameter sets (raw NAL units or with a start code), the picture size and
// rotation. The ftyp and moov are written at once.
int pv_segment_writer_open(uint32_t width, uint32_t height, uint16_t rotation_degrees,
                           const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                           size_t cap, pv_segment_writer** out);
// Appends one encoder output buffer (Annex B) with its time from the segment
// start; a key frame starts a fragment. PV_ERR_SEGMENT_FULL when it does not fit.
int pv_segment_writer_add(pv_segment_writer* w, const uint8_t* sample, size_t len,
                          uint64_t pts_us, int key);
// Closes the last fragment with end_us as the end of its last sample and
// writes the sidx. The buffer then holds the file image.
int pv_segment_writer_finish(pv_segment_writer* w, uint64_t end_us);
// The file image after finish (the writer keeps ownership).
int pv_segment_writer_data(const pv_segment_writer* w, const uint8_t** data_out, size_t* len_out);
// Bytes written so far, samples, and fragments; valid at any time.
int pv_segment_writer_stats(const pv_segment_writer* w, size_t* bytes_out, uint32_t* samples_out,
                            uint32_t* fragments_out);
// Wipes and frees the buffer and the writer. Safe before or after finish.
void pv_segment_writer_close(pv_segment_writer* w);
```

The encrypt needs no new core call: the JNI builds the item header (media type 2,
`payload_len` from `data`) and encrypts `[header, data]` through the memory source and
`pv_vault_encrypt`, the path `nativeEncryptItem` takes for a direct buffer today, with the
same cancel hook. JNI (D-011 shape): `nativeSegmentOpen(width, height, rotation, sps,
pps, cap, handleOut): Int`; `nativeSegmentAdd(handle, ByteBuffer direct, offset, length,
ptsUs, key): Int`; `nativeSegmentFinish(handle, endUs): Int`; `nativeSegmentStats(handle,
out): Int`; `nativeSegmentEncrypt(vault, handle, fields, idOut, lenOut): Int`;
`nativeSegmentClose(handle)`. The debug variant adds `nativeSegmentCopy(handle): ByteArray`
for the T2 box checks and the golden comparison. The desktop CLI is unchanged.

Golden vectors (checkpoint condition): `tests/fixtures/v1/segment_one_fragment.bin`, a
segment of one fragment of three synthetic samples at 1920x1080 with rotation 90, written
by `make_vectors --segment` with the parameter sets and samples from the deterministic
source, and `segment.txt` with the inputs, the box offsets (end of `moov`, end of `sidx`,
`moof`, `mdat`), and the BLAKE2b of the file. T1 writes the same inputs through the
writer and compares byte for byte, then checks the boxes of the fixture; T2 does the same
through the JNI (Milestone D).

Not added: a post-pass in C++ (not needed with the own writer); a push-style object
writer (D-014 stands: the item header needs the length first).

## 5. Where plaintext lives during a recording

| Place | Owner | Lifetime | Wiped by the app |
|---|---|---|---|
| Camera buffers, the `SurfaceTexture` image | platform and vendor | per frame | no |
| GL output buffers of the viewfinder and the encoder input surface | platform (BufferQueue) | per frame; the viewfinder is a `PreviewView` under `FLAG_SECURE` | no |
| Encoder input and output buffers | platform codec | per frame | no |
| Segment buffer (open, plus up to two finished) | app (core, `sodium_malloc`) | from the first sample to the end of its encrypt; 3 s of video each | yes |
| Index bytes | app (Java array) | the encode and encrypt | yes, `fill(0)` |
| Encrypt chunk buffers | app (core) | per chunk | yes |

No Java copy of a sample or a segment exists in the feature path (the prototype kept one
in `ByteArrayOutputStream`; the muxer alternative would keep one fragment).

## 6. Failure handling

Section 4.7 for the stop paths. Also: a `pv_secure_alloc` failure at segment start stops
the recording with `OUT_OF_MEMORY` (no plain `malloc` fallback, D-024); a `sidx` or
`moof` slot overflow ends the fragment early or reports `SEGMENT_FULL`, which stops the
recording with the segments so far; a lock during the index encrypt is waited for (the
index is one small object inside the 4 s bound); the effect error listener and the
camera state observer both route to `stop`.

## 7. Test plan

**T1 (Windows).** The 18 existing tests. `test_segment_writer` (new): a segment of three
fragments from synthetic Annex B samples (3- and 4-byte start codes, several NAL units per
sample) parsed back box by box: `ftyp`, `moov` with the `tkhd` matrix for 0, 90, 180, 270,
`avcC` with the parameter sets, `sidx` references equal to the fragments with the right
sizes and durations, every `moof` with `tfdt` equal to the first sample time, `trun`
durations summing to the gaps and the last one to `end_us`, data offsets landing on the
`mdat` payload, sample bytes with length prefixes equal to the input NAL units; a sample
before the first key frame (`SEGMENT_STATE`), a sample after finish, a sample larger than
the cap (`SEGMENT_FULL`, the writer still finishes with the samples before it), the moof
slot bound, 1024 samples in one fragment, a fragment of one sample, close without finish,
null arguments; `pv_vault_encrypt_segment` round trip through `pv_vault_decrypt` and the
item header; error names 30 and 31. `test_golden`: unchanged vectors.

**T2 (emulator).** The 69 existing tests. `VideoCaptureTest` (new): a recording from
the feature path (`VideoRecorder` on a test lifecycle with the splitter and a test
surface as the viewfinder output); every segment carries `tfdt`, `sidx`, and the `tkhd`
rotation (box walk over the debug readback); the clip plays through `VideoActivity`
with the bare extractor (the data source reports `tfdt` for every segment), seeks
inside a segment land on the key frame within one GOP, position equal to the key time;
a 3D-muxer clip recorded in the test through `V3Probe` still plays through the wrapper
(the data source reports no `tfdt`); lock during a recording ends it with the segments
so far indexed and the vault locked (`lastRecording.reason == "lock"`, every named
segment present, the gate free); the low-space hook, the encoder-error hook, and the
queue-full hook each stop with their name and an index over the encrypted segments; a
process-kill stand-in (the writer closed without an index) leaves orphans that the next
unlock's sweep removes; the read-ahead bound on a 60 s scripted clip (opens over time
and the peak of live buffers); `FLAG_SECURE` on the capture screen while recording;
`leak_scan` clean; `VideoViewerTest` and `VideoProbeTest` unchanged. Host-driven: merged
manifests (`CAMERA` only), class scan (no `media3.muxer` outside debug), APK sizes,
captures of the capture screen while recording black.

**T3 (Xiaomi).** Brief Section 6: the debug build and the heap investigation; the
benchmark build over it; a recording by hand with the developer confirming the live
viewfinder and the upright, smooth clip; a 60 s clip with the read-ahead and memory
measured; Home, screen off, and the MIUI camera during a recording; both regression clips;
the recents card; `leak_scan`; cleanup leaving the two regression clips and one 3E clip.

## 8. Dependencies, manifests, version

No new dependency: the writer is core code, the splitter uses the platform EGL and GLES20
classes, CameraX stays at 1.6.2. `media3-muxer` stays `debugImplementation` for the
prototype and the trials. Release and benchmark manifests unchanged (`CAMERA` only,
`LockActivity` the only export). Version 0.7.0 (`versionCode` 7).

## 9. Checkpoint items (CP-1 to CP-8)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Split route | `effect` (public `CameraEffect`), the `own` route kept as the fallback in the trial code (2.3, 4.2); confirmed on the Xiaomi numbers |
| CP-2 | Segment buffering | the core writer assembles the segment in one guarded buffer; no Java copy; three buffers at most (4.3) |
| CP-3 | Container route | own writer in the core with `tfdt`, `sidx`, true durations, `tkhd` rotation, microsecond timescale (4.4); the muxer plus post-pass measured as the alternative |
| CP-4 | Playback detection | per segment by `tfdt`; bare extractor for new clips, the wrapper for old (4.5) |
| CP-5 | Settings | 1920x1080 asked with fallback, 30 fps target, 8 Mbit/s (5 at 720p), 8 MiB cap; the phone decides, the emulator falls back (4.6) |
| CP-6 | Stop rules | one stop path; the lock waits up to 4 s (4.7) |
| CP-7 | Read-ahead | byte target of two segments, size before time (4.8) |
| CP-8 | Prototype and trial code | `V3Probe` stays as the debug probe for the old-shape fixture in T2; the trial options and `Mp4Fix`, `Fmp4Writer` are removed in Milestone D once the core writer exists; `GlSplitter` moves to main |

## 10. Seams for 3F and later

- 3F: the writer takes a track list (AAC as `mp4a` with `esds`, a second `traf` per
  `moof`); the index audio fields are filled; `VideoActivity` gains volume; the lock stop
  path drains the audio track the same way.
- Phase 5: a segment is streamed as the file image the writer produces.
- Phase 6: the reserved item header bytes for a clip ID and a sequence number
  (recovery of orphans), R8.

## 11. Threat model deltas (for `docs/THREAT_MODEL.md` Section 11)

- A recording keeps the camera frames in platform buffers for two outputs instead of
  one; the viewfinder is a secure window; nothing is read back from either.
- The segment plaintext lives in guarded native memory only, three segments at most,
  wiped after encryption; the Java heap holds no sample.
- A lock during a recording ends it within 4 s with the segments so far encrypted and
  indexed; a partial segment that misses the bound is wiped, not stored.
- A process kill leaves orphan segment objects until the next unlock.
- The container now carries the rotation and the fragment times in plaintext inside the
  encrypted object (no change on disk).
