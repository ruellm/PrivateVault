# Phase 3E progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3E_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-21)

Stage 3E complete: Milestones A to E done. Design approved at the checkpoint with the
conditions applied; one amendment out of T3 (the orientation of the encoded frame, D-037).
T1 **19/19** Debug and Release; T2 **72 tests, 0 failures, 1 skipped** on the emulator with
the final code (`build/p3e/t2-d8-results.xml`); lint 0 errors; release and benchmark at
2577 media3 classes, manifests `CAMERA` only; version 0.7.0 (`versionCode` 7). T3 on the
Xiaomi done with the developer's install taps and by-hand actions on the benchmark build
(viewfinder live, the clip upright and smooth after the fix, Home, screen off, the MIUI
camera, recents, the 60 s clip, both regression clips, leak scan). Report in
`docs/PHASE3E_TEST_REPORT.md`. All changes uncommitted; the suggested commit message is at
the end of this file. The phone holds the benchmark build 0.7.0 and 68 objects (49
photos, 4 samples, the two regression clips, one 3E clip); the emulator holds the 0.7.0
debug build.

## Milestone E log (2026-10-21)

1. Final full T2 on the viewfinder-count build (`t2-only.sh d4`, `t2-d4-results.xml`):
   **72 tests, 0 failures, 0 errors, 1 skipped**, 596 s.
2. Xiaomi, debug build 0.7.0 (developer install tap, `phone-p3e.sh install-debug`), the
   developer aiming at the neutral scene:
   - `record <pin> 10000 E1-record` (the PIN on the command line only; `E1-record.txt`):
     the feature path at **1920x1080, rotation 90, `c2.qti.avc.encoder`, 8 Mbit/s**;
     camera 279 frames at 30 fps, **279 drawn to the viewfinder and 279 to the encoder**;
     275 frames encoded in 9.2 s (30 fps); 3 segments of 3 189 546, 3 714 406, and
     2 379 518 bytes (3 fragments and 3 keys each), encrypt 240, 260, and 166 ms from the
     guarded buffer (debug build); index of 9.2 s; leak scan 0 findings (101 files).
   - `play <pin> latest:seeks=6 E2-play` (`E2-play.txt`): every segment on the
     standard path, read-ahead target 7 428 812 bytes, first frame **797 ms**, the codec
     applied the container rotation (`1080x1920 unapplied_rotation=0`), gaps 33, 33 ms,
     0 dropped of 275, one live buffer (3.7 MB), opens equal closes, PSS peak 161 MiB;
     six seeks landing on the chosen key frame (in-segment 1136, 4540, 7945 ms, latency
     252 to 649 ms). One imprecision in the scripted plan: a segment-start target was
     truncated to milliseconds and landed in the previous segment (3405 ms to the key at
     2270 ms); `VideoActivity` now keeps microseconds for scripted seeks (`seekToUs`),
     rebuilt as `d-all.sh d3 skip`; the bar still seeks in milliseconds.
   - Heap investigation (`phone-heap.sh`, route b of D-043: `open,capture_photo_n=10,
     memory,wait,gc,memory,wait,leak_scan` with `am dumpheap` after the tenth capture and
     after the forced collection, dumps pulled to `D:\pv-heap`, read by
     `build/p3e/hprof_large_arrays.py`, deleted; logs `E3-heap.txt`, `E3-before-analysis.txt`,
     `E3-after-analysis.txt`): ten captures at 2448x3264 (JPEG 2.8 to 3.3 MB, `DIRECT`
     path, encrypt 196 to 234 ms), the sampler's Java peak per capture 7.6 to 11.6 MB
     (the first run: 8.2 to 19.4 MB) against a resting 4.4 to 4.8 MB; the dump 2 s after
     the burst (36.6 MB, 215 260 instances) holds **no byte array of 1 MB or more**
     (103 060 byte arrays, 4.9 MB in all), nor does the dump after the forced
     collection (4.4 MB Java used); the leak scan reported one three-byte chance match
     at offset 19082 inside a new object's ciphertext. ART collects garbage before a heap
     dump, so the allocating method of the transient array needs the Studio allocation
     recording; the report records this as the limit of route b.
3. Final full T2 on the d3 build (`t2-only.sh d5`, `t2-d5-results.xml`): **72 tests,
   0 failures, 0 errors, 1 skipped**, 662 s.
4. Benchmark 0.7.0 installed (tap), vault kept. By hand (`B1-byhand.txt`): a 12.5 s
   recording at 1920x1080, 30 fps, 377 camera frames drawn to the viewfinder (376) and
   the encoder (376), 4 segments, index of 12.5 s; playback on the standard path, first
   frame 247 ms, gaps 33, 33, 33 ms, 0 dropped of 372. **Defect: the developer saw the
   clip sideways** although the phone was held upright. Diagnosis with a benchmark build
   logging the texture matrix (`B2-diag.txt`): `texture matrix 0,-1,0,0,-1,0,0,0,0,0,1,0,
   1,1,0,1` with `camera_transform=true`, so the camera transform (a quarter turn) sits
   inside the matrix; the GL copy applied it and the clip carried rotation 90 as well,
   turned twice at playback. Fix: the encoder is sized to the turned stream and drawn
   with the viewfinder matrix CameraX supplies; the index and the container carry
   rotation 0 (`docs/PHASE3E_DESIGN.md` 4.2). Rebuilt (`d-all.sh d4 skip`): BUILD
   SUCCESSFUL, lint 0 errors. T2 classes `VideoCaptureTest` and `VideoViewerTest` rerun
   (`t2-d6`): pending; by-hand check of the fix: pending.
5. T2 after the orientation fix (`t2-d6`, `VideoCaptureTest` and `VideoViewerTest`): three
   of four methods passed; the capture test tripped on the framework line (no rotation
   key for 0; expectation adjusted). `t2-d7` (the capture class again): the 60 s recording
   gave 7 segments at 2 fps and the capture-screen case met `ERROR_CAMERA_FATAL_ERROR` from
   the emulator camera after the long run; the long-clip test now asks for five segments,
   not a rate (CP-5), and the emulator was rebooted. Final full T2 (`t2-only.sh d8`,
   `t2-d8-results.xml`): **72 tests, 0 failures, 0 errors, 1 skipped**, 619 s.
6. Fixed benchmark build (developer's tap), by hand (`B3-byhand.txt`): 10.7 s recording at
   1080x1920, rotation 0, 4 segments at 30 fps, 319 frames to both outputs, portrait
   thumbnail; **the developer saw the clip upright and smooth and the viewfinder live**.
   Home, screen off, and the MIUI camera during recordings: reason `lock`, 3, 2, and 2
   segments indexed, the lock finishing the recording in 269, 97, and 70 ms, the vault
   locked each time. 60 s clip: 63.0 s, 19 segments, 1872 frames at 30 fps, 63.1 MB;
   playback first frame 201 ms, 18 transitions, 5 dropped of 1867, 19 opens and closes,
   one live buffer, the loader one segment ahead (segment 6 at 17.3 s, 14 at 45.7 s),
   PSS peak 124 MiB (`B4-pss.txt`: 125 MB). Recents during playback and during a
   recording: **blank by the developer's eye**. Both regression clips on the wrapper path,
   first frame 162 and 151 ms, 0 dropped, **upright by eye**. Leak scan from the vault
   screen: one three-byte chance match inside a photo ciphertext.
7. Cleanup (debug build, developer's tap; `E4-list2.txt`, `E5-cleanup.txt`,
   `E6-photos.txt`): 25 video tiles listed; 15 test clips deleted through the app (59
   segments, every status `PV_OK`); the 20 photos of the two heap bursts deleted; leak
   scan **0 findings, 73 files**; 68 objects. The two regression clips and the upright
   10.7 s 3E clip (`9a9eba86...`) stay. Benchmark build installed last (developer's tap).
8. Docs: `docs/PHASE3E_TEST_REPORT.md` (new), `docs/PHASE3E_DESIGN.md` (status
   implemented, 4.2 orientation), `docs/DECISIONS.md` (D-037 amended), `docs/THREAT_MODEL.md`
   Section 11 confirmed, `docs/ROADMAP.md` (3E complete), `docs/DEPENDENCIES.md`, this file.
   Git read-only; nothing committed.

## Milestone D log (2026-10-21)

1. JNI: `nativeSegmentOpen`, `nativeSegmentAdd` (a direct buffer in place), `nativeSegmentFinish`,
   `nativeSegmentStats`, `nativeSegmentEncrypt` (the existing item encrypt over the writer
   buffer, same cancel hook), `nativeSegmentClose`, debug-only `nativeSegmentCopy`;
   `NativeBridge` codes 30 and 31 and the three bounds; `SegmentWriter.kt` (new);
   `Vault.encryptSegment`.
2. Main: `GlSplitter.kt` moved from the trial to main unchanged; `VideoRecorder.kt` (new,
   process scoped in `PvApp`): the effect route, the encoder at the stream size with the
   settings of D-040, the drain loop of the prototype over the core writer, segments cut on
   the third key frame with the true end time, encryption on the `pv-encrypt` thread from
   the writer buffer, the index after the last segment, one `stop(reason)` path,
   `stopAndWait` with the abandon rule, the low-space, queue-full, encoder-error, and
   skip-index test hooks; `LockController.lock` stops a recording first (4 s bound);
   `CaptureActivity` video mode (mode button, Record and Stop, the elapsed label over the
   preview, Flip and the mode disabled while recording, the camera bound again after the
   recording, scripted `record=<ms>` and `record_hook=<name>`); `GuardedSegmentDataSource`
   records per segment whether its first fragment carries `tfdt` and logs the path;
   `SeekableSegmentExtractor.Factory` returns the bare `FragmentedMp4Extractor` for such a
   segment; `VideoActivity` sets the read-ahead byte target (twice the largest segment, at
   least 4 MiB, size before time) and exposes the path per segment. Version 0.7.0
   (`versionCode` 7).
3. Trial code retired (CP-8): `DebugVideoProbes.kt` back to its 3D shape plus the
   `rotation=` field in the framework line, `Mp4Fix.kt` and `Fmp4Writer.kt` deleted,
   `nativeProbeAllocDirect` removed; `Mp4Boxes` keeps the `tfdt`, `sidx`, and `tkhd`
   reporting; `VideoProbeActivity` keeps `plain=1`. The Milestone A results stay in this
   file and the design.
4. Tests: `VideoCaptureTest` (new, two methods) and `GoldenVectorTest.segmentWriterVector`
   (the Android writer over `segment_samples.bin` equals `segment_one_fragment.bin`).
5. Build (`d-all.sh d1 skip`, `build-d1.log`): BUILD SUCCESSFUL, lint 0 errors (89
   warnings). Class scan: debug 2637 media3 classes, benchmark and release 2577 (no muxer
   class outside debug), `androidx.camera.video` 0. Merged manifests: release and
   benchmark `CAMERA` only, 6 activities, `LockActivity` the only export, 1 disabled
   service, no receiver or provider. APK sizes: debug 20 403 192, benchmark 15 816 467,
   release 15 808 275 bytes (plus about 98 KB over 3D for the recorder, the splitter, and
   the core writer).
6. T2 runs: `t2-d1` (the two new classes): `readAheadBoundOnLongClip` and the golden
   segment vector passed on the first run; `recordPlayStopsAndOrphans` failed on the
   framework line of the restored prototype inspector (no `rotation=` field), fixed; `t2-d2`
   (that method alone, logcat `t2-d2-logcat.txt`): passed in 116 s. Figures: clip A at
   1280x720 (the fallback), 4 segments with `tfdt`, `sidx`, and rotation 90, framework and
   media3 counts equal to the index on every segment, standard path on every segment,
   first frame 1068 ms, gaps 33, 33, 33 ms, in-segment seeks landing on the key frame
   (target 1500 ms to the key at 1093 ms, 4693 to 4293), the wrapper on the same clip
   landing the same (pinned as a known limit), the old-shape clip on the wrapper path,
   the lock during a recording finished in 389 ms with 2 segments indexed, the
   low-space, encoder-error, and queue-full stops each with an index, the index-less
   recording swept, `FLAG_SECURE` on the capture screen while recording, leak scan clean.
   Full T2 (`t2-only` through Gradle directly with logcat, `t2-d3.log`, `t2-d3-results.xml`,
   `t2-d3-logcat.txt`): **72 tests, 0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`),
   606 s. Read-ahead on the 60 s clip (20 segments, 1463 frames at 24 fps on the
   emulator, 34.1 MB): the loader opened segment 2 at 4 s of playback, segment 4 at
   10 s, segment 8 at 22 s, one to two segments ahead of the position all the way, one
   live guarded buffer at every sample, against six segments at once in 3D.
7. One cosmetic defect found in the logs after the run: the viewfinder frame count was
   read after CameraX had closed that output and logged as null; the recorder now keeps
   the count at the close. Rebuilt (`d-all.sh d2 skip`, `build-d2.log`): BUILD SUCCESSFUL,
   lint 0 errors; APK sizes debug 21 022 484, benchmark 15 816 467, release 15 808 275
   bytes; manifests and class counts as in item 5. Final full T2 (`t2-d4`): see the
   Milestone E log.

## Milestone C log (2026-10-21)

1. Checkpoint approved by the developer (CP-1, CP-4, CP-6, CP-7, CP-8 as written; CP-2 and
   CP-3 with three conditions; CP-5 confirmed; the wrapper behaviour on `tfdt` clips to be
   traced in Milestone D or pinned by a T2 test). `docs/PHASE3E_DESIGN.md` status and
   4.10 updated, D-037 to D-043 marked approved with the conditions, threat model
   Section 11 approved.
2. Core: `src/vault_core/src/segment.cpp` (new), `pv.h` (codes 30 and 31, the
   `pv_segment_writer_*` calls, the three bounds), `pv_init.cpp` (error names),
   `CMakeLists.txt`. The encrypt reuses the existing item path over the writer buffer;
   no `pv_vault_encrypt_segment`.
3. T1: `tests/core/test_segment_writer.cpp` (new): three fragments parsed box by box
   (`tkhd`, `mdhd`, `hdlr`, `avcC`, `trex`, `sidx` references and durations, `mfhd`
   sequence, `tfhd`, `tfdt`, `trun` rows, data offsets landing on the `mdat` payload, the
   payload bytes with length prefixes); the four rotation matrices; parameter sets with
   and without start codes; a sample without a start code; the state rules; increasing
   times; the end past the last sample; a sample larger than the room (`FULL`, the segment
   still finishes); a key frame with no room for its slot; the 1024-sample and 64-fragment
   bounds; arguments; error names; the round trip through a vault object and the header
   peek; the golden vector byte for byte with the offsets and hash of `segment.txt`.
   `tests/tools/make_vectors.cpp --segment` writes `tests/fixtures/v1/segment_one_fragment.bin`
   (1387 bytes) and `segment.txt`; the fixtures README records them. Two defects found by
   the test on the way: the `moof` size counted the `traf` header twice (8 stray bytes in
   every `moof`, fixed, fixture regenerated), and one test expectation about a 32 KiB
   buffer was wrong (a closed fragment gives its slot slack back). `t1.sh c`: Debug
   **19/19**, Release **19/19**, 0 warnings.

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 19/19, 35.8 s
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 19/19, 14.2 s
   build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --segment
   ```

## Milestone B log (2026-10-21)

`docs/PHASE3E_DESIGN.md` written: Milestone A results on both devices; the split route,
buffering, container, and playback-detection proposals with the measured alternatives;
the capture settings table; stop and failure rules; the read-ahead bound; the heap plan;
the core ABI of the segment writer; plaintext locations; the test plan; checkpoint items
CP-1 to CP-8; seams; threat model deltas. `docs/DECISIONS.md` D-037 to D-043 (proposed).
`docs/THREAT_MODEL.md` Section 11 (proposed). `docs/ROADMAP.md` row 3E. Milestone C
waits for approval.

## Milestone A log (2026-10-21)

1. `git status`: clean apart from the untracked brief `PHASE3E_BRIEF.md`. Branch `main` at
   `abd9a5c`. The phone showed as `unauthorized` at the start and was authorized by the
   developer before the first phone step.
2. Helper scripts (not committed, `build/p3e/`): `t1.sh`, `d-all.sh`, `t2-only.sh`,
   `t2-class.sh`, `emu-start.sh`, `emu-3e.sh`, `emu-3e-caps.sh`, `phone-p3e.sh` (the 3D
   scripts with the log directory changed), plus `emu-trial.sh`, `emu-batch-a.sh`, and
   `phone-trial.sh` for the Milestone A trials. Logs under `build/p3e/`.
3. T1 on the unchanged tree (`t1.sh a`, logs `t1a-*.log`): Debug **18/18**, Release
   **18/18**, 0 warnings.

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 18/18, 53.0 s
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 18/18, 17.7 s
   ```

4. T2 on the unchanged tree (`d-all.sh a`, logs `build-a.log`, `t2-a.log`,
   `t2-a-results.xml`): build of all variants, lint 0 errors 86 warnings; **69 tests,
   0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`), 346 s. Class scan: debug 2637
   media3 classes, benchmark and release 2577, `androidx.camera.video` 0. Merged manifests:
   `CAMERA` plus the AGP-declared `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; debug 8
   activities, benchmark and release 6; 1 disabled service, no receiver, no provider.
   APK sizes: debug 20 801 312, benchmark 15 718 163, release 15 709 971 bytes.

   ```
   $env:JAVA_HOME = "D:\tools\jdk-21"; cd android
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
   gradlew.bat connectedDebugAndroidTest
   ```

5. API check of the pinned libraries (javap over the cached AARs, `media3-muxer` and
   `media3-container` 1.11.1, `camera-core` 1.6.2):
   - `FragmentedMp4Muxer` has `addMetadataEntry(Metadata.Entry)` and its `Boxes.tkhd` takes
     an orientation, so `Mp4OrientationData` reaches the `tkhd` matrix. `Boxes` has no
     `tfdt` and no `sidx` method. `Boxes.tfhd(trackId, baseDataOffset)` writes flag 0x1
     with an absolute base data offset, which a post-pass that inserts bytes must rewrite.
   - `CameraEffect` is public with protected constructors (`targets, executor,
     SurfaceProcessor, errorListener`); `SurfaceProcessor`, `SurfaceOutput.getSurface`,
     `getSize`, `updateTransformMatrix(float[], float[])`, `close`, and `UseCaseGroup`
     are public; the `TRANSFORMATION_*` and `OUTPUT_OPTION_*` constants are restricted and
     not used. `Preview.Builder.setTargetFrameRate` and `ResolutionSelector` are public.
     `SurfaceRequest.getExpectedFrameRate` is restricted (the 3B lint finding) and not used.
6. Trial code, debug source set only (`DebugVideoProbes.kt` trial options on `V3Probe`,
   `GlSplitter.kt`, `Mp4Fix.kt`, `Fmp4Writer.kt`, `Mp4Boxes.kt` reporting `tfdt`, `sidx`,
   and the `tkhd` rotation, `VideoProbeActivity` `plain=1`), with three small main-set
   hooks (`CaptureActivity.previewFrame` and `previewView` accessors, the `video_trial`
   action routed to the capture screen, a debug-only JNI `nativeProbeAllocDirect`).
   Scripted action: `video_trial=route=<direct|effect|own>:size=<w>x<h>:fps=<n>:clip=<ms>:
   bitrate=<bps>:meta=<0|1>:fix=<none|tfdt|sidx|dur>:writer=<media3|own>:buffer=<java|native>`;
   playback `video_play=latest:seeks=6:plain=1` runs the bare `FragmentedMp4Extractor`.
   Build (`d-all.sh trial skip`, `build-trial.log`): BUILD SUCCESSFUL, lint 0 errors (no
   restricted API in the trial code); release and benchmark unchanged (2577 media3
   classes, manifests as above).
7. Emulator trials (`emu-batch-a.sh` over `emu-trial.sh`; `emu-3e.sh install` first for
   a fresh throwaway-PIN vault; logs `A1-*` to `A8-*.txt`; 10 s clips; a 1920x1080
   request gave 1280x720, the largest 16:9 size of the virtual camera; software GL
   translator "SwiftShader"; `c2.android.avc.encoder`):

   | Trial | Route, container | Camera to encoder | Clip | Playback (bare extractor unless noted) |
   |---|---|---|---|---|
   | A1 | effect, muxer plus `tfdt` and `sidx` | 87 frames (7 fps) drawn to both outputs, 0 EGL errors | 56 frames, 8.9 s, 3 segments, `tkhd_rotation=90`, `sidx_refs=3`, `tfdt` per fragment, key counts 3/3/3/3 | first frame 1119 ms, container rotation applied (`720x1280 unapplied 0`), gaps 33, 33 ms, 5 of 5 seeks on the key frame (in-segment 1322, 5397 ms); through the 3D wrapper: the same 5 landings |
   | A2 | own view route, the same container | 83 frames (7 fps), viewfinder 82 | 63 frames, 9.1 s | 5 of 5 seeks on the key frame, 4 dropped |
   | A3 | direct (V3b) at 1280x720, no fix | meter 15 fps | 122 frames, 8.6 s, 14 fps delivered, CPU 91 percent | not played |
   | A4 | effect at 1280x720, `tfdt`, `sidx`, true last durations | 119 frames (10 fps) | container sample times equal the encoder key offsets within one 90 kHz tick | 6 of 6 seeks on the key frame |
   | A5 | effect, **own writer** (`Fmp4Writer.kt`) | 130 frames (11 fps) | `tfdt` in microseconds equal to the key offsets exactly, `sidx_refs=3`, `tkhd_rotation=90`, framework and media3 counts agree, first frame decoded | first frame 968 ms, 6 of 6 seeks on the key frame, 0 dropped |
   | A6 | effect, muxer plus post-pass into the **native buffer** | 106 frames (9 fps) | every segment encrypted through the `DIRECT` path, 17 to 127 ms, post-pass 1 to 42 ms | 6 of 6 seeks on the key frame |
   | A7 | effect, `tfdt` only | 123 frames (10 fps) | `sidx_refs=-1` | segment starts land; **every in-segment seek lands at the segment start** (unseekable without `sidx`) |
   | A8 | effect at the 3C default (1280x960), no fix | 112 frames (9 fps) | `tkhd_rotation=0`, no `tfdt` (the old shape through the split) | not played |

   CPU 98 to 112 percent of one core in every split run against 91 percent direct; PSS
   peaks 75 to 80 MiB; `leak_scan` 0 findings after every trial. The split halves the
   emulator rate because its GL runs in software; the phone decides the route and the
   settings. The 3D wrapper landing right on a `tfdt` clip (A1) was not expected and the
   mechanism was not traced; the design keeps the wrapper for old clips only.
8. Xiaomi trials (`phone-batch-a.sh <pin>` over `phone-trial.sh`; the developer tapped
   the install of the trial debug build, MD5 equal to the 16:04 build; the PIN on the
   command line only; logs `build/p3e/phone/P*-*.txt`; 10 s clips of a neutral lit
   scene; Adreno 619, `c2.qti.avc.encoder`, `c2.qti.avc.decoder`):

   | Trial | Route, size, bitrate | Camera to encoder | Clip | Playback (bare extractor) |
   |---|---|---|---|---|
   | P1 | effect 1920x1080 8 Mbit/s, muxer plus `tfdt` and `sidx` | 289 frames (30 fps) to both outputs | 284 frames, 9.48 s, 30 fps, 8.00 Mbit/s, largest segment 3 538 757 bytes, CPU 119 percent, PSS 261 MiB | first frame 771 ms, rotation from the container, gaps 33, 33 ms, 0 dropped of 290, 6 of 6 seeks on the key frame, PSS 144 MiB |
   | P2 | effect 1280x720 5 Mbit/s | 285 (30 fps) | 283 frames, 30 fps, 5.07 Mbit/s, 2 041 439 bytes, PSS 195 MiB | first frame 629 ms, 0 dropped, 6 of 6 seeks, PSS 159 MiB |
   | P3 | own view 1920x1080 | 291 (30 fps) | 286 frames, 30 fps, 8.12 Mbit/s, PSS 233 MiB | not played |
   | P4 | direct 1920x1080 | meter 29 fps | 266 frames in 9.21 s, 29 fps, 8.00 Mbit/s, CPU 102 percent, PSS 143 MiB | not played |
   | P5 | direct 1280x720 | meter 30 fps | 283 frames, 30 fps, 5.10 Mbit/s, PSS 142 MiB | not played |
   | P6 | effect 1920x1080, own writer | 284 (29 fps) | 280 frames, 30 fps, 8.05 Mbit/s; `tfdt` in microseconds equal to the key offsets, `sidx_refs=3`, `tkhd_rotation=90` | first frame 865 ms, 0 dropped, 6 of 6 seeks, PSS 119 MiB |
   | P7 | effect 1920x1080, native buffer, duration fix | 282 (29 fps) | 277 frames, 30 fps; every segment through the `DIRECT` path, encrypt 356 ms for 3.3 MB | first frame 968 ms, 0 dropped, 6 of 6 seeks, PSS 139 MiB |

   Encrypt 185 to 356 ms per segment (7.5 to 12.4 MB/s, debug build), queue high-water
   mark 1 in every split run (2 once in the direct 720p run), `leak_scan` 0 findings
   after every trial (72 to 97 files), no new non-object file. The PSS peaks are the
   debug build with the sampler running from the bind to the unbind.
9. Git read-only; nothing committed.

## Suggested commit message

```
Phase 3E: video capture as a feature, own fMP4 writer, orientation fix

Core: pv_segment_writer (segment.cpp) writes one H.264 segment as a
fragmented MP4 with tkhd rotation, tfdt, sidx, true durations, and a
microsecond timescale inside guarded memory; codes 30 and 31;
test_segment_writer; make_vectors --segment and the segment fixtures.
Android: VideoRecorder (CameraEffect split to the viewfinder and the
encoder, segments through the core writer, encrypt from the buffer, one
stop path, 4 s lock bound, hooks), GlSplitter, SegmentWriter, capture
screen video mode, the lock stops a recording first, per-segment tfdt
detection with the bare extractor for new clips, read-ahead byte
target, microsecond scripted seeks, version 0.7.0; the encoder draws
with the viewfinder matrix so clips record upright with rotation 0.
Tests: VideoCaptureTest, GoldenVectorTest segment vector. Docs:
PHASE3E_DESIGN, PHASE3E_TEST_REPORT, PHASE3E_PROGRESS, decisions D-037
to D-043, threat model Section 11, roadmap, dependencies.
```
