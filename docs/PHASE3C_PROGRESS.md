# Phase 3C progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3C_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-20)

Stage 3C complete: Milestones A to E done. T1 **18/18** Debug and Release; T2 **67 tests,
0 failures, 1 skipped** on the emulator with the final code (`build/p3c/t2-t2f-results.xml`);
T3 on the Xiaomi done with the developer's install taps and by-hand actions; lint 0 errors;
release and benchmark at 0 media3 classes; version 0.5.0 (`versionCode` 5). Findings and
numbers in `docs/VIDEO_PLAYBACK_PROBE.md`, the report in `docs/PHASE3C_TEST_REPORT.md`. Phone
cleanup through the delete action done: 45 video objects deleted, 53 objects remain (49 photos, 4 Phase 1 samples). All changes uncommitted; the suggested
commit message is at the end of this file. The phone holds the Stage 3C debug build 0.5.0;
the emulator holds the same build with a throwaway-PIN vault of a few scripted clips.

## Milestone E log (2026-10-20)

1. Install 0.5.0 debug on the Xiaomi (`phone-p3c.sh install-debug`, developer's tap; two
   later installs the same way for the frame-rate meter fix, the `secure=0` switch, and
   the pixel sample). The phone screen must be awake for every scripted run: a dozing
   screen, or the screen timing out during the 12 s PIN unlock, stopped the activities and
   locked the vault in two runs (`E1-record` first attempt, `E10-verify` first attempt);
   the developer kept the screen alive by hand after that.
2. Recording (`phone-p3c.sh record <pin> 12000`, `E1-record.txt`): two clips at the
   approved settings. Counts from encoder, container, framework, media3, and index equal on
   every segment; first sample a key frame; index times equal the encoder log; leak scan 0.
   First clip: the `ImageReader` meter got no frames from this HAL (fallback 30 given to the
   encoder, 14.4 fps delivered as in Milestone A, phone face down on the desk). Fix in
   `DebugVideoProbes.kt`: the reader is made with `USAGE_GPU_SAMPLED_IMAGE`, the index
   records the meter's rate or, failing that, the delivered rate of the encoder output.
   Aimed clip after the fix: meter 24 fps, 285 frames in 11.45 s, decoded first frames with
   mean luma 128 to 137.
3. Playback (`phone-p3c.sh play <pin> latest[:...] <log>`; `E2-play`, `E3-home`,
   `E4-screenoff`, `E5b-camera`, `E6-unsecure`, `E6b-unsecure`, `E8-pixels-secure0`,
   `E8-pixels-secure1`, `E9-play`): first frame 327 to 542 ms, boundary gaps 33, 33, 33 ms,
   0 dropped frames, 10 of 10 seeks rendering the chosen key frame exactly at 142 to 373 ms,
   one live guarded buffer (2.25 MB peak), PSS 149 to 161 MiB, `release()` to last close 0 to
   5 ms. Numbers side by side with the emulator in `docs/VIDEO_PLAYBACK_PROBE.md` Section 6.
4. The black picture: the developer saw black for the first clip, with and without the
   secure surface. A `PixelCopy` sample of the `SurfaceView` added to the probe read mean
   luma 6 (about 188 colours) in both modes: the decoder output reached the surface and the
   recording itself was dark (camera towards the desk, no viewfinder). The aimed clip reads
   mean luma 134 to 136 (about 2300 colours) on the secure surface; the emulator 99 (about
   2100). The developer was asked whether the aimed clip showed a picture and had not answered by the end of the session; the pixel samples are the evidence.
5. Interrupt cases (developer's hands; the 11.6 s clip is short, so the presses landed while
   the probe held the last frame after the seeks, player alive and paused): Home (stop
   released the player, 5 ms, background lock followed); screen off (stop released, lock
   followed); MIUI camera (Home, then the camera from the launcher: stop released, camera
   connected, lock followed; a first attempt opened the camera during the invisible unlock
   and is not counted). Recents card blank (`E7-recents.png`), screen capture during the hold
   empty. Leak scan 0 after every recording.
6. Vault listing for the cleanup (`verify_all`, `E10-verify.txt`): 98 of 98 objects verify;
   49 JPEG, 36 video segments, 9 video indexes, 4 Phase 1 synthetic samples. The video
   objects by day: 2026-10-13 (Phase 2 clip) 4 segments and 1 index; 2026-10-19 (Milestone A
   matrix) 24 and 6; 2026-10-20 (Milestone E) 8 and 2; list in `E10-video-objects.txt`.
7. Cleanup (`phone-cleanup.sh <pin>`, `E11-cleanup.txt`), after the developer's go: 45
   `delete_object` actions through the app, 45 `PV_OK`, 0 errors; `list` shows 53 objects and
   no partial; every remaining object is one of the 49 photos or the 4 Phase 1 samples of
   the listing; leak scan 0 findings, 58 files scanned.
8. Final verification on the host after the last code change (`build-d10.log`, `t2-t2f.log`):
   build of all variants with lint (0 errors, 74 warnings), class scan (debug 2637 media3
   classes, benchmark and release 0), merged manifests as in Milestone D, T2 67 tests,
   0 failures, 1 skipped.
9. Docs: `docs/VIDEO_PLAYBACK_PROBE.md` (new), `docs/PHASE3C_TEST_REPORT.md` (new),
   `docs/PHASE3C_DESIGN.md` (meter usage flag, index rate rule, `video_play` options, pixel
   sample), this file. Git read-only; nothing committed.

## Suggested commit message

```
Phase 3C: video index v1, key-frame findings, playback probe

Core: index v1 encode, decode, locate (pv.h codes 27 to 29), test_video_index,
golden vectors and the --video-index generator mode. Android: index entry
points in the bridge and VideoIndex.kt; viewer load returns the buffer for
video items; the V3b prototype at the approved settings writes index v1;
playback probe over ExoPlayer in the debug variant (guarded segment data
source, seek map from the fragment table, extractor wrapper); VideoIndexTest
and VideoProbeTest; version 0.5.0. Docs: PHASE3C_DESIGN, VIDEO_PLAYBACK_PROBE,
PHASE3C_TEST_REPORT, PHASE3C_PROGRESS, decisions D-028 to D-031, threat model
Section 9, roadmap, dependencies (Temurin JDK 21 for Gradle).
```

## Milestone D log (2026-10-20)

1. Bridge: `nativeVideoIndexEncode`, `nativeVideoIndexDecodeHeader`, `nativeVideoIndexDecode`,
   `nativeVideoIndexLocate` in `jni_bridge.cpp` (array shapes in `docs/PHASE3C_DESIGN.md`
   Section 4.4), the three codes and the index constants in `NativeBridge`, and
   `VideoIndex.kt` (main source set, no media3): fields, `encode`, `decode`, `locate`, and
   `read` of an index object through the viewer load. `nativeViewerLoad` now hands back the
   guarded buffer for video segment and index items too, not only JPEG (the 3B contract
   otherwise unchanged; `PhotoLoader` still reports them as unknown items and frees the
   buffer).
2. Prototype (`DebugVideoProbes.kt`) at the approved settings: the camera frame rate is
   measured first over an `ImageReader` (1.5 s), then the encoder gets that rate, a 10 s
   I-frame backstop, and a sync request one GOP after the last key frame; a segment closes
   when the key frame after its third GOP arrives; sample times inside a segment start at
   zero; index v1 is written through the core and read back; the per-segment counts line
   gained the index count, the index-times-match flag, and the first-sample checks;
   `Mp4Boxes` reports the first sample's sync flag; `Media3Sync` reports the sync sample
   times. `V3Probe` takes a `Context` and a `LifecycleOwner` so the instrumented test can run
   it without an activity. `video_probe=<clip ms>[:<fragment ms>[:<backstop s>]]`.
3. Playback probe: `VideoProbeActivity` (debug source set and manifest, not exported,
   `FLAG_SECURE`, secure `SurfaceView`, lock listener), `GuardedSegmentDataSource`
   (`pv://segment/<n>`, viewer load into guarded memory, wiped on close, `LiveBuffers`
   accounting), `SegmentSeekMap` (a `ChunkIndex` from the fragment table),
   `SeekableSegmentExtractor` (the seek map in place of the unseekable one; sample times
   re-based after a seek to a fragment offset), scripted action `video_play=<index hex |
   latest>[:seeks=<n>][:hold=<ms>]` from the vault screen through the `VideoProbes.playback`
   seam. `media3-exoplayer` 1.11.1 `debugImplementation`; its `WAKE_LOCK` permission removed
   in the debug manifest; `versionCode` 5, `versionName` 0.5.0.
4. Two media3 behaviours found and worked around, with the evidence in
   `docs/VIDEO_PLAYBACK_PROBE.md` Section 4: the muxer writes no `tfdt` and no `sidx`, so
   the extractor reports an unseekable stream and, after a seek to a fragment offset,
   numbers that fragment from zero (the first probe build rendered frames from the wrong
   GOP after seeks inside a segment while the masked player position looked right); and the
   muxer gives the last sample of a fragment the previous sample's duration, which shifts
   the container times after an uneven frame gap (141 ms once on the loaded emulator;
   Section 5 there). The design, the roadmap (3E writes `tfdt` or `sidx`), and the test plan
   were updated; D-030 stands (the index records the encoder times).
5. Tests: `VideoIndexTest` (bridge round trip, the committed vectors decoded and re-encoded
   to the same bytes, the index item through a vault under the test key, the seek rule,
   rejections, the Phase 2 prototype magic as `NOT_INDEX`); `VideoProbeTest` (one 10 s
   recording: counts agree across encoder, container, media3, and index on every segment,
   every segment starts on a key frame, index times equal the encoder log, container key
   times within the muxer shift of the index; playback through the end, ten seeks each
   rendering the chosen key frame, no live buffer afterwards, a lock during playback
   releases the player, a lock during a slowed decrypt ends the open with `CANCELLED`; leak
   scan clean, a chance ciphertext match logged and not counted). Three test defects fixed
   on the way: the lock controller's preference files landing between two scans, a
   same-position seek rendering nothing, and the player's first-frame callback arriving
   late from an earlier seek.
6. Runs (`build/p3c/build-d5.log`, `t2-t2e.log`, `t2-t2e-results.xml`, `t2-video9-logcat.txt`,
   `D-manifest-*.txt`, `deps-*-d5.txt`):

   ```
   $env:JAVA_HOME = "D:\tools\jdk-21"
   cd android
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease   # BUILD SUCCESSFUL; lint 0 errors, 72 warnings
   gradlew.bat connectedDebugAndroidTest    # 67 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest), 240 s
   python -I build\p3b\dexclasses.py <apks>  # debug 2637 media3 classes; benchmark and release 0; camera/video 0 everywhere
   aapt2 dump xmltree --file AndroidManifest.xml <apk>   # debug adds VideoProbeActivity (not exported); WAKE_LOCK removed; no service, receiver, or provider added
   bash build/p3c/emu-3c.sh install; bash build/p3c/emu-3c.sh record 12000; bash build/p3c/emu-3c-caps.sh
   ```

   APK sizes: debug 20.6 MB (ExoPlayer), benchmark 10.6 MB, release 10.6 MB. Resolved debug
   tree: `media3-common`, `-container`, `-database`, `-datasource`, `-decoder`, `-exoplayer`,
   `-extractor`, `-muxer`; release tree 0 media3 lines.
7. Emulator numbers (`docs/VIDEO_PLAYBACK_PROBE.md` Sections 2, 3, 8): first frame 644 ms,
   boundary gaps 33, 33, 33 ms (one frame period), ten seeks at 183 to 702 ms each rendering
   the key frame exactly, one live guarded buffer at a time (1.75 MB peak), PSS peak 77.8 MiB,
   lock during a slowed decrypt cancels the open in 1.4 s. Scripted run: screen captures
   during playback and during the held last frame are black (15 sampled colours, 40463 of
   40488 samples `000000`), the recents card after Home is black (`build/p3c/D3-playing.png`,
   `D4-hold.png`, `D5-recents.png`, not committed).
8. Git read-only; nothing committed.

## Milestone C log (2026-10-19)

1. Core: `src/vault_core/src/video_index.cpp` (new, portable, libsodium only for nothing:
   plain byte packing and checks). `pv.h`: error codes 27 to 29, the index constants, the
   two structs, and the five functions of `docs/PHASE3C_DESIGN.md` Section 4.4, plus
   `PV_VIDEO_INDEX_HEADER_MAX_BYTES` (4096), `PV_VIDEO_INDEX_MAX_DIMENSION` (16384), and
   `PV_VIDEO_INDEX_MAX_SEGMENT_BYTES` (1 GiB) so the bounds of Section 4.2 are named in one
   place. `pv_error_name` knows the three codes. The writer emits the fixed 64-byte header
   only (`header_len` must be 64); the reader accepts `header_len` 64 to 4096 within the
   buffer and skips the extra bytes. `decode` validates every rule of Section 4.2 in two
   passes (counts and sizes first, then records), refuses trailing bytes, and requires
   `total_duration_us` to equal the end of the last segment. A buffer without the magic is
   `PV_ERR_NOT_INDEX` (including a zero-length buffer with a real pointer); a null pointer is
   `PV_ERR_INVALID_ARGUMENT`.
2. `tests/core/test_video_index.cpp` (new, registered in `tests/core/CMakeLists.txt`): round
   trips for 1, 3, and 1 000 segments with 1 to 4096 keys; `decode_header` counts;
   `header_len` 72 with extra bytes accepted by the reader and refused by the writer; every
   header rule and every segment rule of the design test plan; truncation at every length of
   the three-segment vector and one trailing byte; the boundary tests the checkpoint asked
   for (one segment with 4096 keys accepted, 4097 refused; 16384 segments with one key each
   accepted, 16385 refused; 16384 segments with 128 keys each computes above 8 MiB and is
   refused by `size`, `encode`, and `decode`; three segments with 4096 keys each accepted,
   so the key bound is per segment); `locate` before the start, at every segment start,
   inside GOPs, at the end, past the end; argument checks; error names.
3. Golden vectors by `make_vectors --video-index` (`tests/tools/make_vectors.cpp`):
   `video_index_min.bin` (116 bytes), `video_index_three.bin` (244), `video_index_audio.bin`
   (244), `item_video_index.pvo` (437, media type 3, payload `video_index_three.bin`),
   `video_index.txt` (counts, durations, audio codec, BLAKE2b hashes, the item ID and payload
   hash). `test_golden` decodes the three index files, re-encodes them to the same bytes,
   decrypts the item through the item sink, and decodes its payload (3 segments, 9 keys).
   `tests/fixtures/v1/README.md` describes them and the generator mode.
4. T1 (`build/p3c/t1c-*.log`): Debug **18/18**, Release **18/18**, 0 warnings in either
   build. One test defect found and fixed on the way: the truncation loop started at length
   zero with an empty vector, whose data pointer is null; the test now checks the two cases
   apart (empty buffer with a pointer: `NOT_INDEX`; null pointer: `INVALID_ARGUMENT`).

   ```
   cmake --build --preset windows-x64-tests-debug
   ctest --preset windows-x64-tests-debug        # 18/18
   cmake --build --preset windows-x64-tests-release
   ctest --preset windows-x64-tests-release      # 18/18
   build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --video-index
   ```

5. No Android change in this milestone. Git read-only; nothing committed.

## Checkpoint (2026-10-19)

Approved by the developer with two confirmations, applied to `docs/PHASE3C_DESIGN.md`
(status line names them), `docs/DECISIONS.md` (D-028 to D-031 marked approved), and
`docs/ROADMAP.md`: the 4096 key bound is per segment, with boundary tests for both bounds;
segments are cut on the encoder's actual key-frame output and the index records actual
key-frame times, with a T2 check. Also: 3E picks the capture resolution and frame rate
(roadmap row); the phone cleanup in Milestone E goes through the app's delete action (3C
test recordings, prototype indexes, Phase 2 video objects; photos stay) and is announced
before it starts.

## Milestone B log (2026-10-19)

`docs/PHASE3C_DESIGN.md` written: key-frame findings with the box evidence and the
hypothesis supported; the frame-count behaviour of `KEY_I_FRAME_INTERVAL`; recommended
settings; index format v1 byte layout, bounds, and rules; the seek rule; the core ABI;
playback probe plan with the plaintext locations; lock rules; test plan; checkpoint items
CP-1 to CP-7; seams; threat model deltas. `docs/DECISIONS.md` D-028 to D-031 (proposed).
`docs/THREAT_MODEL.md` Section 9 (proposed). Checkpoint report sent to the developer.
Milestone C waits for approval.

## Toolchain inventory delta (2026-10-19)

Android Studio AI-262 (updated by the developer; opens the AGP 8.9 project; JBR 25).
Temurin JDK 21.0.12.1 at `D:\tools\jdk-21` for Gradle (above). Helper scripts under
`build/p3c/` (not committed): `t1.sh`, `emu-start.sh`, `t2-baseline.sh`, `t2-only.sh`,
`d-all.sh`, `emu-clips.sh`, `phone-p3c.sh`, `summarize_clips.py`. Emulator stopped at the end
of Milestone B. No SDK component installed or changed. All output on D.
