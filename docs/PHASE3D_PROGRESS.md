# Phase 3D progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3D_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-20)

Stage 3D complete: Milestones A to E done. Design approved at the checkpoint with three
amendments (applied). T1 **18/18** Debug and Release with the core addition; T2 **69
tests, 0 failures, 1 skipped** on the emulator with the final code
(`build/p3d/t2-d8-results.xml`); lint 0 errors; release and benchmark at 2577 media3
classes, manifests `CAMERA` only; version 0.6.0 (`versionCode` 6). Emulator host-driven
checks done (captures black, recents black, leak scan 0, read-ahead measured on a 30 s
clip). T3 on the Xiaomi done with the developer's install taps and by-hand actions on the
benchmark build (picture confirmed by eye, upright and smooth; Home, screen off, camera,
recents; delete of one clip; leak scan 0). Report in `docs/PHASE3D_TEST_REPORT.md`. All
changes uncommitted; the suggested commit message is at the end of this file. The phone
holds the benchmark build 0.6.0 and 63 objects (49 photos, 4 Phase 1 samples, the two 3E
regression clips); the emulator holds the 0.6.0 debug build with a throwaway-PIN vault of
two scripted clips.

## Milestone A log (2026-10-20)

1. `git status`: clean apart from the untracked brief `PHASE3D_BRIEF.md`. Branch `main` at
   `deb8c9f`.
2. Helper scripts (not committed): `build/p3d/t1.sh`, `d-all.sh` (build every variant with
   lint, class scan, merged manifests, dependency trees, T2), `t2-only.sh`, `a-trial.sh`
   (the exoplayer-in-release trial), `emu-3d.sh` (install, record, thumb, actions),
   `phone-p3d.sh` (install, record, thumb, actions, list). All logs under `build/p3d/`.
3. T1 (`t1.sh a`, logs `t1a-*.log`): Debug **18/18**, Release **18/18**, 0 warnings.

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 18/18, 45.8 s
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 18/18, 19.6 s
   ```

4. T2 on the unchanged tree (`d-all.sh a`, logs `build-a.log`, `t2-a.log`,
   `t2-a-results.xml`): build of all variants up to date, lint 0 errors 74 warnings;
   **67 tests, 0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`, no lock screen on the
   emulator), 247 s. Class scan: debug 2637 media3 classes, benchmark 0, release 0;
   `androidx.camera.video` 0 everywhere. Merged manifests: debug 7 activities, benchmark and
   release 5; permissions `CAMERA` plus the AGP-declared
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; 1 service (the disabled CameraX holder), no
   receiver, no provider. APK sizes: debug 20 601 168, benchmark 10 627 697, release
   10 619 505 bytes.

   ```
   $env:JAVA_HOME = "D:\tools\jdk-21"; cd android
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
   gradlew.bat connectedDebugAndroidTest
   gradlew.bat :app:dependencies --configuration {debug,benchmark,release}RuntimeClasspath
   python -I build\p3b\dexclasses.py <three apks>
   aapt2 dump xmltree --file AndroidManifest.xml <apk>
   ```

5. Dependency trial (`a-trial.sh trial`, logs `build-trial.log`, `deps-trial-*.txt`,
   `trial-manifest-*.txt`): `media3-exoplayer` 1.11.1 declared as `implementation` for the
   build only, then reverted. Benchmark **15 636 251** bytes (+5 008 554), release
   **15 628 059** bytes (+5 008 554); 12 056 classes defined each, of which **2577** in
   `androidx.media3` (debug has 2637: the muxer is debug-only). Resolved release and
   benchmark trees: `media3-common`, `-container`, `-database`, `-datasource`, `-decoder`,
   `-exoplayer`, `-extractor`, all 1.11.1 (no muxer). Merged manifests: one change only,
   `android.permission.WAKE_LOCK` declared by `media3-exoplayer` (to be removed by a main
   manifest rule, as the debug manifest does today); activities, service, receiver, and
   provider counts unchanged; `LockActivity` the only exported one. Native libraries in the
   release APK unchanged (`libprivatevault_jni.so` and the two CameraX libraries per ABI);
   media3 ships no native code.
6. First-frame thumbnail trial on the emulator (`emu-3d.sh install`, `record 12000`,
   `thumb latest:n=5`; logs `A0-setup.txt`, `A1-record.txt`, `A2-thumb.txt`). Debug-only
   code: `VideoFirstFrame.kt` (own parse of `avcC` and the first sample of the first
   fragment, `MediaCodec` in buffer mode, flexible YUV sampled into a 256 px software
   bitmap) and `VideoThumbProbe.kt` (`thumb_probe=<index hex | latest>[:n=<repeat>]` in
   `ScriptActivity`). Clip: 12.7 s, 4 segments, 1280x960, rotation 90, first segment
   1 571 844 bytes, first sample 50 542 bytes. Five runs:

   | Run | Codec | Decrypt ms | Parse ms | Codec start ms | Buffer live ms | Decode ms | Convert ms | Total ms | Bitmap |
   |---|---|---|---|---|---|---|---|---|---|
   | 0 | `c2.goldfish.h264.decoder` | 13 | 0 | 92 | 108 | 0 | 59 | 170 | 256x192, turned 192x256 |
   | 1 to 4 | same | 11 to 12 | 0 | 27 to 37 | 39 to 50 | 0 to 1 | 3 to 4 | 46 to 57 | same |

   Mean luma 98 with 4945 distinct colours in every run (the emulator picture read 99 in
   3C through `PixelCopy`), so the frame decoded and converted. "Buffer live" is from the
   decrypt call to the free after the sample was queued into the codec. PSS 49 to 53 MiB;
   the Java heap rose about 1 MiB per run without a collection (the probe's own pixel
   walk and colour set; the bitmap pixels are native). Phone run pending (Section
   "Current state").
7. ExoPlayer segment lifetime, read from the 3C logs (`build/p3c/t2-video9-logcat.txt`,
   `build/p3c/phone/E9-play.txt`): every segment's data source opens, is extracted whole,
   and closes before the next one opens; on the phone all four segments of the 11.5 s clip
   were opened and closed within the first 2 s of playback, before the second item started.
   One guarded buffer at a time, live for its decrypt and extraction only (100 to 220 ms);
   the samples then sit in the player's queue (design Section 4.3).
8. Git read-only; nothing committed.

## Milestone E log (2026-10-20, in progress)

1. Full T2 with the final code (`t2-only.sh d8`, `t2-d8-results.xml`): **69 tests,
   0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`), 349 s.
2. Emulator host-driven checks (`emu-3d.sh install`, `record 30000`, `record 12000`,
   `emu-3d-caps.sh E`, and the 30 s clip through `video_open=<hex>`; logs `E1-record30.txt`,
   `E2-record12.txt`, `E-caps.txt`, `E3-read30.txt`, captures `E-*.png`): the gallery
   classified 15 objects and showed 2 (the two clips, thumbnails 192x256 in 281 and
   741 ms); viewer on the 12 s clip: first frame 960 ms, boundary gaps 99, -40, 33 ms
   (first run after the install, under load), six scripted seeks landing on the chosen
   key frame at 205 to 659 ms, peak 1 live buffer, PSS peak 64.2 MiB; on the 30 s clip:
   first frame 856 ms, 8 transitions, gaps 66, 16, 66, 9, -33, 149, 183, 33 ms, 424
   frames with 4 dropped, PSS peak 67.6 MiB, opens equal closes (9); read-ahead as
   recorded in the design (4.3). Captures of the gallery, the playing picture, and the
   held frame: black (40 465 of 40 488 samples `000000`); recents card black; leak scan
   0 findings (13 and 18 files); the player released on the background lock after the
   scripted run (`close_wait_ms=9`).
3. Release APK contents: 0 `androidx/media3/muxer` and 0 `androidx/media3/ui` class
   references in the release and benchmark dex files (debug has the muxer); native
   libraries unchanged.
4. T3 on the Xiaomi, after the developer reattached the phone (logs
   `build/p3d/phone/T*-*.txt`, capture `T6-recents.png`; the PIN was given on the command
   line only and appears in no file, checked):
   - `phone-p3d.sh install-debug` (tap): 0.6.0 debug over the Milestone A build, vault
     kept. `record <pin> 12000 T1-record2`: clip 2, 11.5 s, 4 segments, 29 fps, key
     counts agree, leak scan 0 (68 files). `record <pin> 6000 T2-record3`: clip 3, 5.5 s,
     2 segments, leak scan 0 (71 files).
   - `actions <pin> open,gallery T3-gallery`: 66 objects classified in 413 ms, 56 shown
     (3 video tiles, 49 photos, 2 large, 2 unknown); thumbnail 446 ms; the unlock sweep
     515 ms, nothing deleted.
   - Viewer on clip 2 with `seeks=6` under a long-running logcat (`T4b-viewer.txt`; the
     scripted entry reports "finished" as soon as the viewer opens, so `actions` cut the
     first attempt short): first frame 408 ms, gaps 33, 33, 33 ms, 0 dropped of 345,
     codec-applied rotation, 6 of 6 seeks exact at 33 to 502 ms, one live buffer (2.06 MB),
     PSS peak 161 MiB.
   - `install-benchmark` (tap): 0.6.0 benchmark, not debuggable, vault kept, unlock 0.9 to
     1.0 s. By hand (`T5-byhand.txt`, PSS sampler `T5-pss.txt`): gallery classified 66
     objects in 115 ms, thumbnails 214 to 382 ms; the developer opened the 0:06 clip,
     paused, resumed, dragged the bar (seek 69 ms), pressed Home (stop released the player
     in 0 ms, background lock 0.8 s later); screen off during playback (stop, lock);
     Home plus the MIUI camera (stop, lock, lock screen on return). First frame 154 to
     161 ms, gaps 33, 16 ms, 0 dropped, PSS peak 140 to 155 MiB (the 353 MB sample is the
     PIN unlock).
   - By hand (`T6-recents.txt`, `T6-recents.png`): the 0:12 clip 2 played through (first
     frame 168 ms, gaps 33, 33, 16 ms, 0 dropped of 345); **the developer confirmed the
     scene, upright, playing smoothly**; recents during the second play: the card white
     and blank by eye, the capture holds the recents view; the stop released the player.
   - By hand (`T7-delete.txt`): clip 3 deleted from its viewer (index `PV_OK`, segments
     0 and 1 `PV_OK`), gallery 63 objects with 55 shown; Leak scan from the vault screen
     twice: 0 findings, 68 files, 0 MediaStore rows. Clips 1 and 2 stay as the 3E
     regression clips.
5. Docs: `docs/PHASE3D_TEST_REPORT.md` (new), `docs/PHASE3D_DESIGN.md` (status
   implemented, 4.3 read-ahead), `docs/THREAT_MODEL.md` Section 10 confirmed,
   `docs/ROADMAP.md` (3D complete), `docs/DEPENDENCIES.md`, this file. Git read-only;
   nothing committed.

## Suggested commit message

```
Phase 3D: video playback in the gallery and viewer, release build

Core: pv_object_item_header reads the item header from the first chunk
(viewer.cpp, pv.h, test_viewer_buffer). Android: media3-exoplayer 1.11.1
in every variant with the WAKE_LOCK removal in the main manifest, version
0.6.0; the probe data source, seek map, and extractor wrapper move to main
with the index rotation on the track format; VideoFirstFrame decodes the
first key frame through MediaCodec; VideoItems classifies objects through
the header peek, checks segments, builds thumbnails, deletes index first,
and sweeps orphan segments behind a recording gate; VideoActivity with own
controls, keepScreenOn while playing, idle touch while playing; the gallery
hides segments and shows video tiles with duration; scripted actions
video_open, delete_video, sweep; VideoViewerTest; VideoProbeTest asserts
the planned seek count. Docs: PHASE3D_DESIGN, PHASE3D_TEST_REPORT,
PHASE3D_PROGRESS, decisions D-032 to D-036, threat model Section 10,
roadmap, dependencies.
```

## Milestone D log (2026-10-20)

1. `media3-exoplayer` 1.11.1 `implementation`; `WAKE_LOCK` removal moved from the debug
   manifest to the main one; `VideoActivity` declared (not exported); version 0.6.0
   (`versionCode` 6).
2. Moved from the debug source set to main: `GuardedSegmentDataSource.kt` (`LiveBuffers`,
   the data source with a read-failure test hook, `SegmentSeekMap`,
   `SeekableSegmentExtractor` with the index rotation set on the track format, CP-2) and
   `VideoFirstFrame.kt` (with a failure test hook). The probe `VideoProbeActivity` runs over
   them unchanged.
3. New in main: `VideoItems.kt` (the recording gate, `classify` through `Vault.itemHeader`,
   `checkSegments` by length, `thumbnail` through `VideoFirstFrame` with the segment buffer
   freed once the sample is queued, `durationLabel`, `delete` index first, `sweep` over the
   object list taken at its start, skipped behind the gate or an unreadable index);
   `VideoActivity.kt` (secure `SurfaceView` sized to the picture, tap to play or pause,
   `SeekBar` through `locate`, elapsed and total, Delete behind the confirmation,
   `keepScreenOn` while playing, the position ticker touching the idle timer, no load
   retries, the probe's measurements and an optional `seeks=<n>` script after the end);
   `GalleryActivity.kt` reworked (one classification per object on the viewer thread,
   segments dropped from the list, video tiles with thumbnail, play mark, and duration,
   damaged video tiles open the viewer); `LockController.scheduleSweep` after an unlock or
   a migration; `MainActivity` actions `video_open=<hex | latest>[:seeks=<n>]`,
   `delete_video=<hex>`, `sweep`; `V3Probe.run` holds the recording gate.
4. `VideoViewerTest` (androidTest): the T2 cases of `docs/PHASE3D_DESIGN.md` Section 7
   over two recordings, and the recording-right-after-unlock case of the checkpoint.
5. Build (`d-all.sh d1 skip`, `build-d1.log`): BUILD SUCCESSFUL, lint 0 errors (86
   warnings; the new ones are the `SetTextI18n` and `UseKtx` kinds the existing code
   carries). Class scan: debug 2637 media3 classes, benchmark and release 2577,
   `androidx.camera.video` 0. Merged manifests: release and benchmark `CAMERA` only, six
   activities, `LockActivity` the only exported one, 1 disabled service, no receiver or
   provider; debug adds `ScriptActivity` and `VideoProbeActivity`. Resolved release tree:
   the seven media3 artifacts of the trial. APK sizes: debug 20 255 736, benchmark
   15 718 163, release 15 709 971 bytes.
6. T2 runs (`t2-d1` to `t2-d8`). The emulator had exited between the build and the first
   run (no error in its log; rebooted with `emu-start.sh`, 38 s). Findings on the way, each
   fixed and rerun on the two video classes (`t2-class.sh`):
   - `VideoProbeTest` asserted exactly ten seeks; a 10 s clip the loaded emulator cut into
     three segments gives nine distinct landings. The probe now records the planned count
     and the test asserts against it (at least nine). Pre-existing fragility, not a
     playback defect.
   - `VideoViewerTest` called `onActivity` on an activity the lock had destroyed; it keeps
     the activity reference instead, as `VideoProbeTest` does.
   - With no activity in front the app's background lock fires 700 ms after the last one
     stopped, which locked the vault under the second recording (`ENCRYPT_FAILED`, vault
     closed). The test waits that out and unlocks before recording or launching again
     (`ensureUnlocked`). App behaviour as designed in 3A.
   - The allocation hook hit the index load before any segment opened; the test now sets
     it after the clip ended and seeks, which reopens segment 0 and fails there.
   - **Sweep defect.** With the index's first chunk damaged, the header peek fails and the
     first sweep version skipped that object as "not an item", then deleted the clip's two
     segments as orphans. The sweep now stops when any object's first chunk fails to
     decrypt (`skipped (object <hex> PV_ERR_AUTH_FAILED)` in the passing run); only
     `NOT_ITEM` and the `ITEM_*` codes pass as non-items. A damaged tile whose header is
     unreadable opens the photo viewer's error state, which offers Delete. Design 4.4 and
     4.6, D-035 updated.
   - The viewer rounded the clip position to milliseconds before the seek-landing check;
     it now keeps microseconds as the probe does.
   Passing run of the two classes (`t2-d7`, logcat kept as `t2-d7-viewer-logcat.txt`):
   gallery classified 5 objects with 1 shown; thumbnail 192x256 (codec
   `c2.goldfish.h264.decoder`, buffer live 363 ms, total 867 ms in the test process); the
   lock during the slowed thumbnail decrypt took 1468 ms and ended the load with
   `CANCELLED`; viewer first frame 668 ms, 3 transitions, boundary gaps 33, 33, 33 ms, four
   seeks landing on the chosen key frame (latency 223 to 708 ms), peak 1 live buffer
   (1.94 MB), PSS peak 79.5 MiB; the codec applied the rotation (`size 960x1280
   unapplied_rotation=0` for the 1280x960 source); manual seek to 3422 ms landed on the
   segment 1 start at 3122 ms in 235 ms; damaged segment 1 stopped playback with
   `AUTH_FAILED` after 89 frames of segment 0; delete removed the index and 4 segments;
   the sweep skipped behind the damaged index and removed 2 orphans after the interrupted
   delete; the recording-after-unlock case kept every segment.

## Milestone C log (2026-10-20)

1. Xiaomi half of the thumbnail trial after the checkpoint approval (`phone-p3d.sh
   install-debug` with the developer's tap, `record <pin> 12000` aimed at a neutral lit
   scene, `thumb <pin> latest:n=5`; logs `build/p3d/phone/A1-record.txt`, `A2-thumb.txt`):
   clip 11.6 s, 4 segments, 29 fps, key counts agree on every segment; hardware decoder
   `c2.qti.avc.decoder` in buffer mode, total 405 to 535 ms per run, buffer live 284 to
   336 ms, decode 43 to 51 ms, bitmap 256x192 turned to 192x256, mean luma 58. Figures in
   `docs/PHASE3D_DESIGN.md` Section 2.3; the `ImageReader` route is not needed.
2. Core: `pv_object_item_header` in `pv.h` and `viewer.cpp` (a sink that keeps the first
   bytes and refuses the rest, so the decrypt stops after the first chunk; the `PV_ERR_IO`
   of the refusal is mapped back once the first write arrived; the length is computed
   first and the header checked against it; `WipedBuffer` for the chunk). JNI
   `nativeObjectItemHeader` (8 fields), `NativeBridge`, `Vault.itemHeader` returning
   `ItemPeek`.
3. T1: `test_viewer_buffer` gained `item_headers`: one chunk, three chunks, no payload;
   non-item and empty objects; hand-written headers with rotation 45, media type 9, item
   version 2, and a payload length off by one; a tampered first chunk and a tampered later
   chunk (the peek passes, the verify fails); files cut below one chunk of overhead
   (`TRUNCATED`), inside the first chunk (`AUTH_FAILED`), and right after a full chunk
   (`MALFORMED_CHUNK`, no room for a final chunk); wrong key, missing object, arguments.
   Two expectations corrected on the way (the boundary cut), recorded in the design test
   plan. `t1.sh e`: Debug **18/18**, Release **18/18**, 0 warnings.

   ```
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 18/18
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 18/18
   ```

4. Checkpoint amendments applied to `docs/PHASE3D_DESIGN.md` (status line, 4.3, 4.6, 7,
   8), `docs/DECISIONS.md` (D-032 to D-036 approved, D-035 and D-036 amended),
   `docs/THREAT_MODEL.md` Section 10 (approved), `docs/ROADMAP.md` (3D active; the two T3
   clips kept on the phone as the 3E regression clips, the capture flow takes the gate).

## Milestone B log (2026-10-20)

`docs/PHASE3D_DESIGN.md` written: Milestone A results; dependency and manifest plan;
source layout; player pipeline with own controls, position, and seek; the index rotation
applied through the format; buffer lifetime from the measurements; lock and idle rules;
gallery classification through an item-header peek with segments hidden; thumbnail rule;
delete order and the unlock sweep; the one core addition (`pv_object_item_header`) with its
JNI shape; scripted actions; plaintext locations; failure handling; test plan; checkpoint
items CP-1 to CP-7; seams; threat model deltas. `docs/DECISIONS.md` D-032 to D-036
(proposed). `docs/THREAT_MODEL.md` Section 10 (proposed). `docs/ROADMAP.md` row 3D.
Milestone C waits for approval.
