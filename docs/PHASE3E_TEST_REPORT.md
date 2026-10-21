# Phase 3E test report

Date: 2026-10-21. Format per `PHASE3E_BRIEF.md` Section 9. Every result below comes from a
command that was run; nothing is inferred. "Pending" means the step has not run. Measured
values are marked; everything else is design reasoning.

## Status

**Complete.** Video capture is a feature of every variant: the capture screen records with
the viewfinder live through a public `CameraEffect` that draws one camera stream to the
preview and the encoder, the core assembles each segment as a fragmented MP4 with `tfdt`,
`sidx`, true durations, and the `tkhd` rotation inside guarded native memory and encrypts
it from there, the index follows the last segment, and every way a recording ends (stop,
lock, low space, encoder error, full queue) keeps and indexes the segments encrypted so
far. New clips play and seek through the bare `FragmentedMp4Extractor`; the two regression
clips keep the 3D wrapper. The read-ahead is bounded to about two segments. T1 19/19 and
T2 72 tests green; on the Xiaomi the developer confirmed by eye the live viewfinder, the
upright and smooth clip, and the blank recents card. T3 found one defect in the first
build (the clip played sideways: the camera transform inside the texture matrix was
applied twice), fixed and confirmed by eye before the remaining steps. The heap
investigation (route b) found no retained array; the allocating method of the transient
one needs the Studio allocation recording.

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, NDK, libsodium, Gradle 8.11.1, AGP 8.9.3, Kotlin 2.1.10, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2, media3 1.11.1, JDK Temurin 21 at `D:\tools\jdk-21` | As in `docs/PHASE3D_TEST_REPORT.md` (unchanged) |
| App version | 0.7.0 (`versionCode` 7) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), software GL translator, encoder `c2.android.avc.encoder`, decoder `c2.goldfish.h264.decoder`, virtual camera 1280x720 at most |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, Adreno 619, encoder `c2.qti.avc.encoder`, decoder `c2.qti.avc.decoder`, Keystore `TRUSTED_ENVIRONMENT` |
| Android Studio | Rabbit (not used: the heap steps ran through `am dumpheap` from the host) |

## Architecture changes

`docs/PHASE3E_DESIGN.md`. Core: `segment.cpp` (the segment writer, five calls, codes 30
and 31), `pv.h`, `pv_init.cpp`, `make_vectors --segment`, fixtures `segment_one_fragment.bin`,
`segment_samples.bin`, `segment.txt`. Android main: `VideoRecorder.kt` (new),
`GlSplitter.kt` (new), `SegmentWriter.kt` (new), `CaptureActivity` video mode,
`LockController` (a recording stops first, 4 s bound), `PvApp`, `GuardedSegmentDataSource`
(per-segment `tfdt` detection), `VideoActivity` (read-ahead byte target, microsecond
scripted seeks), `Vault.encryptSegment`, `NativeBridge`, `jni_bridge.cpp`, `MainActivity`
(actions `record`, `record_hook`), version 0.7.0. Debug: `Mp4Boxes` reports `tfdt`, `sidx`,
and the `tkhd` rotation; `VideoProbeActivity` `plain=1`; `SegmentVerify` reports the
framework rotation; the Milestone A trial code was removed in Milestone D. Tests:
`test_segment_writer` (T1), `VideoCaptureTest` and `GoldenVectorTest.segmentWriterVector`
(T2).

## Split route and container route (checkpoint decisions, with the trial evidence)

| Decision | Choice | Evidence (`docs/PHASE3E_PROGRESS.md` Milestone A) |
|---|---|---|
| P3E-1 viewfinder | `CameraEffect` with a `SurfaceProcessor`, GL copy to the preview and the encoder (D-037) | Xiaomi: 30 fps at 1920x1080 delivered, every frame drawn to both outputs, the encoder at 30 fps, the same as the direct route; about 20 percent of a core and 90 to 120 MiB of PSS over the direct route in the debug build; the own-view route gave the same numbers; the emulator halves its rate through any GL split (software GL) |
| P3E-2 buffering | the core writer assembles the segment in guarded memory, three buffers at most, no Java copy (D-038) | the media3 muxer copies samples into Java buffers before every fragment write (javap); the own writer sketch and the native channel both played and seeked on both devices |
| P3E-3 container | own writer: `tkhd` rotation, `tfdt`, `sidx`, true durations, microsecond timescale (D-038) | muxer plus post-pass and own writer both gave 6 of 6 seeks on the key frame through the bare extractor on both devices; `tfdt` without `sidx` left every in-segment seek at the segment start; the own writer gave container times equal to the index times exactly |
| P3E-4 playback | per segment by `tfdt` (D-039) | regression clips on the wrapper path, new clips on the standard path (T2 and T3 logs) |
| P3E-5 settings | 1920x1080 asked with fallback, 30 fps target, 8 Mbit/s (5 at 720p), 8 MiB cap (D-040) | Section "Capture settings" |
| Orientation (T3 amendment) | the encoder draws with the viewfinder matrix CameraX supplies, sized to the turned stream, rotation 0 in index and container (D-037 amended) | the first build played sideways by the developer's eye; the texture matrix logged as a transpose with flips (`camera_transform=true`); the fixed build confirmed upright and smooth by eye |

## Capture settings (measured)

| Setting | Value | Xiaomi (feature path, benchmark build) | Emulator (T2, debug build) |
|---|---|---|---|
| Stream, encoder | 1920x1080 asked; the encoder at the turned size (1080x1920 upright) | 1920x1080 stream, 1080x1920 encoded, rotation 0 | 1280x720 stream (fallback), 720x1280 encoded |
| Frame rate | 30 to 30 target; the index records the delivered rate | 28 to 30 fps delivered and encoded in every run | 2 to 27 fps depending on the emulator load (CP-5: no rate asserted) |
| Bitrate | 8 Mbit/s at 1080p, 5 at 720p | 8.0 Mbit/s (9.3 MB for 9.2 s; 63.1 MB for 63 s) | 5 Mbit/s |
| Segments | 3 s of three GOPs cut on the key frame, 8 MiB cap | 3.2 to 3.7 MB, 3 fragments and 3 keys each, 101 samples | 1.0 to 2.6 MB |
| Encrypt per segment | from the guarded buffer, `pv-encrypt` thread | 166 to 260 ms (debug build), the lock finishing a recording in 65 to 389 ms | 61 to 142 ms |

## Commands

```
# Windows core (T1), logs build/p3e/t1c-*.log
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug      # 19/19
cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release    # 19/19
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --segment

# Android (T2), JAVA_HOME = D:\tools\jdk-21, logs build/p3e/build-d4.log, t2-d8.log
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
gradlew.bat connectedDebugAndroidTest                                                        # 72 tests
python -I build\p3b\dexclasses.py <three apks>
aapt2 dump xmltree --file AndroidManifest.xml <apk>

# Emulator trials (Milestone A, debug build, throwaway PIN of README.md), build/p3e/emu-trial.sh
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_trial=route=effect:size=1920x1080:fps=30:clip=10000:meta=1:fix=sidx,leak_scan
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_play=latest:seeks=6:plain=1

# Xiaomi (build/p3e/phone-p3e.sh, phone-trial.sh, phone-heap.sh; the throwaway PIN on the command line only)
phone-p3e.sh install-debug | record <pin> 10000 <log> | play <pin> latest:seeks=6 <log> | log-start <name> | log-stop <name> | pss-sample <name> <s>
phone-heap.sh <pin>          # ten captures, am dumpheap before and after gc, hprof_large_arrays.py, dumps deleted
phone-p3e.sh install-benchmark
```

## T1 (Windows)

| Test | Debug | Release |
|---|---|---|
| The 18 existing tests | pass | pass |
| `test_segment_writer` (new): three fragments parsed box by box (`ftyp`, `tkhd` matrix and size, `mdhd` timescale, `hdlr`, `avcC` with the parameter sets, the empty sample tables, `trex`, `sidx` references with sizes and durations, `mfhd` sequence, `tfhd` default-base-is-moof, `tfdt`, `trun` flags 0x701 with durations, sizes, and flags per sample, data offsets on the `mdat` payload, the payload bytes with length prefixes); the four rotation matrices; parameter sets with and without start codes; a sample without a start code; the state rules (`SEGMENT_STATE`); increasing times and the end past the last sample (`INVALID_ARGUMENT`); a sample larger than the room (`SEGMENT_FULL`, the segment still finishes); a key frame with no room for its slot; the 1024-sample and 64-fragment bounds; arguments; error names 30 and 31; the round trip through a vault object and the header peek; the golden vector byte for byte with the offsets and hash of `segment.txt` | pass | pass |
| Total | **19/19** | **19/19** |

## T2 (emulator)

| Area | Result |
|---|---|
| The 69 existing tests (`VideoViewerTest`, `VideoProbeTest` included) | pass (`LockBoundKeyTest` skipped as before) |
| `GoldenVectorTest.segmentWriterVector`: the Android writer over `segment_samples.bin` equals `segment_one_fragment.bin` | pass |
| `VideoCaptureTest.recordPlayStopsAndOrphans`: a recording from the feature path with the viewfinder live (an `ImageReader` output); every segment carries `tfdt` per fragment equal to the index key offsets, a `sidx` with one reference per fragment, the `tkhd` rotation of the index; the compatibility gate (framework `MediaExtractor` and media3 parse every segment with key counts equal to the index, the first frame decoded); the clip plays through `VideoActivity` on the standard path for every segment with in-segment seeks landing on the key frame; the wrapper on the same clip pinned (known limit); an old-shape clip (`V3Probe`) on the wrapper path; a lock during a recording ends it with the segments indexed, the vault locked, the gate free; the low-space, encoder-error, and queue-full hooks each stop with their reason and an index; a recording without an index leaves orphans the next sweep removes; `FLAG_SECURE` on the capture screen while recording; leak scan clean | pass (116 to 129 s) |
| `VideoCaptureTest.readAheadBoundOnLongClip`: a 60 s recording (16 to 20 segments) played with the loader one to two segments ahead and one live guarded buffer | pass (68 to 134 s) |
| **Compatibility gate (checkpoint condition on D-038)**: every segment of every feature-path recording in `recordPlayStopsAndOrphans` is decrypted and parsed by the framework `MediaExtractor` (`SegmentVerify.inspect`: `ftyp`, `video/avc`, the first frame decoded) and by media3 `FragmentedMp4Extractor` (`Media3Sync.count`), and the test asserts the framework sync count, the media3 sync count, and the media3 sync times equal to the key count and key offsets of the index for that segment. Logged per segment, for example in the final run (`t2-d8-gate-lines.txt`, saved from the emulator buffer): segment 0 framework `samples=76 sync=3`, media3 `samples=76 sync=3 sync_times_us=0,1090805,2157472`; segment 1 `samples=85 sync=3` both; segment 2 `samples=90 sync=3` both; the short tail segment `samples=5 sync=1` both; the same shape in `t2-d2-logcat.txt` and `t2-d3-logcat.txt` (3/3/3/1 and 3/2 on the lock clip) | pass on every segment of every run |
| Totals (`build/p3e/t2-d8-results.xml`, final code, rebooted emulator) | **72 tests, 0 failures, 0 errors, 1 skipped**, 619 s (three earlier full runs on the way: 606, 596, 662 s, all 72 with 0 failures) |
| Host-driven: merged manifests, class scan, APK sizes | `CAMERA` only, 6 activities, `LockActivity` the only export, no muxer class outside debug (`docs/DEPENDENCIES.md`) |

Two emulator-only observations: the emulator delivers 2 to 27 fps through the feature path
depending on its load (the long-clip test asks for at least five segments, not a rate),
and after a long run its camera failed to open once (`ERROR_CAMERA_FATAL_ERROR`), cleared by
a reboot.

## T3 (Xiaomi)

| Step | Result |
|---|---|
| Install the debug build 0.7.0 (developer's tap), vault kept | done |
| Recording through the feature path, scripted (`E1-record.txt`) | 1920x1080, rotation 90 stream, 279 camera frames drawn to the viewfinder (279) and the encoder (279), 275 frames in 9.2 s (30 fps), 3 segments of 3.2 to 3.7 MB encrypted in 166 to 260 ms, leak scan 0 findings |
| Playback of that clip, six scripted seeks (`E2-play.txt`) | standard path on every segment, first frame 797 ms, gaps 33, 33 ms, 0 dropped of 275, in-segment seeks on the key frame (1136, 4540, 7945 ms), latency 252 to 649 ms, one live buffer (3.7 MB), PSS peak 161 MiB; the scripted segment-start seek truncated to milliseconds landed in the previous segment (fixed: microsecond targets) |
| Heap investigation (`E3-heap.txt`, analyses) | ten captures (JPEG 2.8 to 3.3 MB, `DIRECT`, encrypt 196 to 234 ms); the sampler's Java peak per capture 7.6 to 11.6 MB against 4.4 to 4.8 MB at rest; the dump 2 s after the burst holds **no byte array of 1 MB or more** (103 060 byte arrays, 4.9 MB), nor the dump after the forced collection; the per-capture array is transient garbage; ART collects before a dump, so the allocating method needs the Studio allocation recording (not run) |
| Install the benchmark build (developer's tap), vault kept | done |
| By hand, first build (`B1-byhand.txt`) | 12.5 s recording at 30 fps, 376 frames to both outputs, 4 segments; **the developer saw the clip sideways** although recorded upright; the viewfinder was live |
| Diagnosis (`B2-diag.txt`) | `texture matrix 0,-1,0,0,-1,0,0,0,0,0,1,0,1,1,0,1`, `camera_transform=true`: the camera transform (a quarter turn) sits inside the matrix; the encoder got an upright frame in a landscape buffer and the codec turned it again |
| Fixed benchmark build (developer's tap), by hand (`B3-byhand.txt`) | 10.7 s recording, 1080x1920 encoded, rotation 0, 4 segments at 30 fps, portrait thumbnail 144x256; **the developer confirmed the clip upright and smooth and the viewfinder live**; first frame 228 ms |
| Home during a recording | the background lock ended it: reason `lock`, 6.9 s, 3 segments indexed, the lock finishing the recording in 269 ms, vault locked |
| Screen off during a recording | reason `lock`, 6.3 s, 2 segments, 97 ms (the stop beat the screen-off broadcast, so the lock logged the background reason, as in 3C and 3D) |
| MIUI camera during a recording | reason `lock`, 4.1 s, 2 segments, 70 ms, vault locked, the lock screen on return |
| 60 s clip, recording | 63.0 s, 19 segments, 1872 frames at 30 fps, 63.1 MB, the lock button untouched |
| 60 s clip, playback (`B3-byhand.txt`, `B4-pss.txt`) | first frame 201 ms, 18 transitions, gaps 33 ms (one 66, two 16), 5 dropped of 1867, 19 opens and 19 closes, one live buffer at every moment (3.6 MB peak); the loader opened segment 2 at 2.6 s, 6 at 17.3 s, 10 at 31.0 s, 14 at 45.7 s, 18 at 57.9 s of playback (one segment ahead); PSS peak 124 MiB (app sampler), 125 MB (`dumpsys` every 2 s) |
| Recents during playback and during a recording | **the developer saw the card blank** both times; both returned to the lock screen |
| Regression clips `3c55a432...` and `39b46d41...` | both on the wrapper path (`path=wrapper` on every segment), first frame 162 and 151 ms, gaps 33, 33, 33 ms, 0 dropped; **the developer saw both upright** |
| `leak_scan` (vault screen button) | 1 finding: a three-byte chance match at offset 19082 inside the ciphertext of a photo object from the heap burst (the kind the tests classify as chance); MediaStore rows 0 |
| **Compatibility gate on the phone** (checkpoint condition on D-038) | **Partly shown, not complete.** media3 half: every feature clip played through the bare `FragmentedMp4Extractor` with every segment on the standard path and every encoded frame rendered (`E2-play.txt`: 275 of 275 frames over 3 segments; `B3-byhand.txt`: 1867 of 1872 over the 19 segments of the 60 s clip, 5 dropped by the renderer, 0 errors), so media3 parsed every segment; the per-segment key-frame count from media3 was not logged on the phone. Framework half: no feature clip was parsed by the framework `MediaExtractor` on the phone (the feature path has no readback step; the scripted `video_probe` verify covers only its own prototype recording). The Milestone A phone logs hold both counts equal to the index on every segment (`P1-effect-1080.txt`, `P2-effect-720.txt`, `P6-own-writer.txt`, `P7-native.txt`), but for the trial writers, not the core writer. Closing the gap needs a debug-only scripted action that decrypts the segments of a given clip and runs the two readers, one install tap, and about a minute on the kept clip `9a9eba86...` (not run). Closed in Stage 3F: a debug readback action runs both readers on the kept 3E clip (`9a9eba86...`) and on the 3F clips |
| Cleanup (debug build, developer's tap, `E5-cleanup.txt`, `E6-photos.txt`) | 15 test clips deleted through the app (`delete_video`: index `PV_OK`, every segment deleted, 0 missing, 0 failed, 59 segments in all) and the 20 photos of the two heap bursts (`delete_object`, all `PV_OK`); leak scan after: **0 findings, 73 files**; `list`: 68 objects |

## Playback, seek, read-ahead, and memory figures per device (measured)

| Measurement | Emulator (T2, debug) | Xiaomi debug (`E2-play.txt`) | Xiaomi benchmark (`B3-byhand.txt`) |
|---|---|---|---|
| Recording | 1280x720 stream, 720x1280 encoded, 2 to 27 fps | 1920x1080, 30 fps, 8.0 Mbit/s | 1080x1920 encoded, 28 to 30 fps, 8.0 Mbit/s |
| First frame | 1068 ms | 797 ms | 183 to 247 ms |
| Boundary gaps | 33, 33, 33 ms | 33, 33 ms | 33 ms (one 66, two 16 over 18) |
| Seek latency and landing | on the key frame, 242 to 615 ms | on the key frame, 252 to 649 ms | one drag of the bar (not measured) |
| Read-ahead | one to two segments ahead on a 60 s clip, one live buffer | one live buffer | one segment ahead on a 63 s clip, 19 opens and closes, one live buffer |
| PSS peak during playback | 80 to 91 MiB | 161 MiB | 124 MiB (60 s clip), 183 to 217 MiB (short clips with the gallery behind) |
| PSS peak during a recording | not sampled | 261 MiB (Milestone A trial, debug) | not sampled on the benchmark build |
| Dropped frames | 0 | 0 of 275 | 5 of 1867 |

## Plaintext locations

`docs/PHASE3E_DESIGN.md` Section 5, `docs/THREAT_MODEL.md` Section 11. Measured: the
segment buffers live in guarded memory from the first sample to the end of the encrypt
(three at most; the queue high-water mark was 1 in every phone run), no Java array holds a
sample (the heap dumps after a capture burst hold no array of 1 MB or more; the recorder
path has no such array by construction), the index bytes are a Java array wiped after the
encrypt, the viewfinder is a secure window and the recents card was blank by eye.

## Defects and decisions

| Item | What | Where |
|---|---|---|
| The clip played sideways on the Xiaomi | the camera transform inside the texture matrix was applied by the GL copy and the stream rotation by the codec; found by the developer in T3, diagnosed with a matrix log, fixed by drawing the encoder with the viewfinder matrix at the turned size, rotation 0 recorded; confirmed by eye | `VideoRecorder.onCameraInput`, design 4.2, D-037 amended |
| The moof size counted the traf header twice | 8 stray bytes in every `moof` of the first writer build, found by the T1 box walk before the fixture was committed | `segment.cpp`, Milestone C |
| Scripted segment-start seeks truncated to milliseconds | a seek to a segment start landed in the previous segment; the scripted plan keeps microseconds | `VideoActivity.seekToUs` |
| Viewfinder frame count logged as null | read after CameraX closed the output; kept at the close | `VideoRecorder` |
| Framework extractor reports no rotation key for 0 | test expectation adjusted | `VideoCaptureTest.checkSegments` |
| Emulator rate and camera | 2 to 27 fps through the feature path by load; one `ERROR_CAMERA_FATAL_ERROR` after a long run, cleared by a reboot; the long-clip test asks for five segments, not a rate | `VideoCaptureTest`, Milestone E |
| The 3D wrapper lands seeks right on `tfdt` clips | not traced; pinned by a T2 test as a known limit (D-039) | `VideoCaptureTest` |

Decisions: D-037 to D-043 approved at the checkpoint with conditions; D-037 amended for
the orientation finding.

## Known limits (assumptions, not measured)

- The allocating class and method of the transient per-capture Java array were not
  recorded: the heap dumps show no retained array, and the allocation recording is a
  Studio step the developer can run with the procedure of `docs/PHASE3B_DESIGN.md`
  Section 10. No CameraX setting was changed.
- The front camera was not recorded in T3; the orientation fix follows the CameraX
  viewfinder matrix, which handles mirroring the way the preview does.
- A process kill mid-recording was stood in for by the index-skip hook; the segments
  become orphans the next unlock removes (P3E-7).
- The read-ahead bound comes from the byte target and the measured opens; the sample
  queue is still media3 memory the app does not wipe.
- The encoder error path was exercised through a hook, not a real codec failure.
- The wrapper behaviour on `tfdt` clips is pinned, not explained.
- The compatibility gate is complete in T2 (every segment, every run); on the phone only
  the media3 half is shown, through playback, and the framework `MediaExtractor` parse of
  a core-writer clip has not run (T3 table).

## Acceptance checklist (brief Section 7)

| # | Criterion | Evidence |
|---|---|---|
| 1 | Phase 1, 2, 3A to 3D T1 and T2 suites pass | T1 19/19 Debug and Release; T2 72 tests, 0 failures |
| 2 | Recording from the release-shaped capture screen with a live viewfinder on both devices; developer confirmation by eye | T2 `FLAG_SECURE` case through `CaptureActivity`; Xiaomi by hand, viewfinder live and the clip upright and smooth (fixed build) |
| 3 | Segments never in the Java heap; guarded buffers wiped after encryption | by construction (core writer, `DIRECT` encrypt); heap dumps with no large array; `SegmentWriter.close` in every path |
| 4 | New segments carry rotation and `tfdt`; new clips seek through the standard path; old clips still play | T2 box checks; T3 `path=standard` for new clips, `path=wrapper` and upright for both regression clips |
| 5 | Settings from measurements on both devices, recorded in the index | D-040; index lines `1080x1920 ... fps=30` on the phone, 720x1280 on the emulator |
| 6 | Lock, low space, encoder error, queue-full stops keep and index; a process kill leaves orphans the sweep removes | T2 hooks and the orphan case; T3 Home, screen off, camera |
| 7 | Read-ahead bounded and measured on a 60 s clip | T2 and T3 timelines |
| 8 | Heap investigation result recorded with evidence | T3 row; `E3-*-analysis.txt` |
| 9 | `FLAG_SECURE` while recording and playing; recents blank; `leak_scan` clean | T2; T3 recents blank by eye; leak scan 0 findings after the scripted runs, one chance match on the by-hand scan |
| 10 | Release and benchmark manifests unchanged; APK size recorded | `docs/DEPENDENCIES.md` |
| 11 | Core changes additive; all golden vectors pass | `test_golden` in T1, `GoldenVectorTest` in T2 |
| 12 | Report separates demonstrated facts from assumptions | this report, "Known limits" |

## Phone state after Stage 3E

Benchmark build 0.7.0 over the kept vault: 49 photos, 4 Phase 1 samples, the two regression
clips (`3c55a432...`, `39b46d41...`, wrapper path), and one 3E clip kept for 3F
(`9a9eba86...`, 10.7 s, 1080x1920, 4 segments, standard path): 68 objects. The emulator
holds the 0.7.0 debug build; its vault is erased by the tests.

## Files changed

Core: `src/vault_core/include/pv/pv.h`, `src/vault_core/src/segment.cpp` (new),
`src/vault_core/src/pv_init.cpp`, `src/vault_core/CMakeLists.txt`, `tests/core/CMakeLists.txt`,
`tests/core/test_segment_writer.cpp` (new), `tests/tools/make_vectors.cpp`,
`tests/fixtures/v1/segment_one_fragment.bin`, `segment_samples.bin`, `segment.txt`, `README.md`.
Android main: `build.gradle.kts`, `jni_bridge.cpp`, `NativeBridge.kt`, `Vault.kt`,
`SegmentWriter.kt` (new), `GlSplitter.kt` (new), `VideoRecorder.kt` (new), `CaptureActivity.kt`,
`LockController.kt`, `PvApp.kt`, `MainActivity.kt`, `GuardedSegmentDataSource.kt`,
`VideoActivity.kt`. Android debug: `DebugVideoProbes.kt`, `Mp4Boxes.kt`, `VideoProbeActivity.kt`.
Tests: `VideoCaptureTest.kt` (new), `GoldenVectorTest.kt`. Docs: `PHASE3E_DESIGN.md`,
`PHASE3E_PROGRESS.md`, this report, `DECISIONS.md` (D-037 to D-043), `THREAT_MODEL.md`
Section 11, `ROADMAP.md`, `DEPENDENCIES.md`. Helper scripts and logs under `build/p3e/` are
not committed. The suggested commit message is in `docs/PHASE3E_PROGRESS.md`.
