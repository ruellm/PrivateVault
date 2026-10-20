# Phase 3D test report

Date: 2026-10-20. Format per `PHASE3D_BRIEF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Pending" means the step has not run. Measured
values are marked; everything else is design reasoning.

## Status

**Complete.** Video clips play in the gallery and viewer of every variant: `media3-exoplayer` 1.11.1 ships in release and benchmark with
own controls, the probe pieces of 3C moved into the main source set, the index rotation is
applied by the codec, a first-frame thumbnail decodes from the guarded segment buffer
through the platform codec, the gallery tells objects apart through a new core call that
reads one chunk, segments are not listed, delete removes the index first and then the
segments, and a sweep after every unlock removes orphan segments without touching a
recording in progress or a clip whose index is unreadable. T1 18/18 and T2 69 tests green;
the emulator captures are black and the leak scan clean. On the Xiaomi the benchmark build
plays the clips with the developer's confirmation by eye (the scene, upright, smooth),
first frame 154 to 168 ms on the hardware decoder, Home, screen off, the MIUI camera, and
recents each release the player and lock the vault, the recents card is blank, delete
removes index and segments, and the leak scan is clean. One sweep defect was found by T2
and fixed before the phone run (Section "Defects").

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, NDK, libsodium, Gradle 8.11.1, AGP 8.9.3, Kotlin 2.1.10, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2, JDK Temurin 21 at `D:\tools\jdk-21` | As in `docs/PHASE3C_TEST_REPORT.md` (unchanged) |
| media3 | 1.11.1: `media3-exoplayer` `implementation` (every variant, with `-common`, `-container`, `-database`, `-datasource`, `-decoder`, `-extractor`); `media3-muxer` and `media3-extractor` declared for debug |
| App version | 0.6.0 (`versionCode` 6) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), decoder `c2.goldfish.h264.decoder`, Keystore `SOFTWARE`, no screen lock |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, decoder `c2.qti.avc.decoder`, Keystore `TRUSTED_ENVIRONMENT`, no screen lock |

## Architecture changes

`docs/PHASE3D_DESIGN.md`. Core: `pv_object_item_header` (one additive call, no new
code). Android main: `GuardedSegmentDataSource.kt` (from debug; rotation on the track
format, read-failure hook), `VideoFirstFrame.kt` (from the Milestone A trial; failure
hook), `VideoItems.kt` (classification, segment checks, thumbnail, delete, sweep,
recording gate), `VideoActivity.kt`, `GalleryActivity.kt` reworked, `LockController`
sweep after unlock, `MainActivity` actions `video_open`, `delete_video`, `sweep`,
`Vault.itemHeader`, `NativeBridge.nativeObjectItemHeader`, manifest (`VideoActivity`,
`WAKE_LOCK` removal). Debug: `V3Probe` holds the gate; `VideoThumbProbe` and
`thumb_probe`; the probe records its planned seek count. Tests: `test_viewer_buffer`
(T1), `VideoViewerTest` (T2), `VideoProbeTest` adjusted.

## Dependency and manifest results (measured)

| Item | 3C | 3D |
|---|---|---|
| Release APK (unsigned) | 10 619 505 bytes | **15 709 971** bytes |
| Benchmark APK | 10 627 697 bytes | **15 718 163** bytes |
| Debug APK | 20 601 168 bytes | 20 255 736 bytes |
| media3 classes defined: release, benchmark, debug | 0, 0, 2637 | **2577, 2577, 2637** |
| `androidx/media3/muxer` and `/ui` references in the release and benchmark dex | 0 | 0 (debug: muxer 8, ui 0) |
| `androidx.camera.video` classes | 0 | 0 |
| Merged release and benchmark manifests | `CAMERA`; 5 activities; `LockActivity` the only export | `CAMERA` only (`WAKE_LOCK` removed by the main rule); **6 activities** (`VideoActivity` added, not exported); `LockActivity` the only export; 1 disabled service; no receiver or provider |
| Debug manifest | adds `ScriptActivity` (exported), `VideoProbeActivity` | the same, 8 activities |
| Native libraries | 3 per ABI | unchanged |
| Lint | 0 errors, 74 warnings | 0 errors, 86 warnings (`SetTextI18n`, `UseKtx` kinds as before) |

## Commands

```
# Windows core (T1), logs build/p3d/t1e-*.log
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug      # 18/18
cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release    # 18/18

# Android (T2), JAVA_HOME = D:\tools\jdk-21, logs build/p3d/build-d1.log, t2-d8.log
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
gradlew.bat connectedDebugAndroidTest                                                        # 69 tests
gradlew.bat :app:dependencies --configuration {debug,benchmark,release}RuntimeClasspath
python -I build\p3b\dexclasses.py <three apks>
aapt2 dump xmltree --file AndroidManifest.xml <apk>

# Emulator scripted (debug build, throwaway PIN of README.md), build/p3d/emu-3d.sh and emu-3d-caps.sh
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_probe=30000,leak_scan
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,gallery
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_open=latest:seeks=6
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,thumb_probe=latest:n=5,open

# Xiaomi (build/p3d/phone-p3d.sh; the developer's throwaway PIN on the command line only)
phone-p3d.sh install-debug | record <pin> 12000 <log> | thumb <pin> latest:n=5 <log> | actions <pin> <list> <log>
phone-p3d.sh install-benchmark | log-start <name> | log-stop <name> | recents <png> | meminfo
```

## T1 (Windows)

| Test | Debug | Release |
|---|---|---|
| The 18 existing tests | pass | pass |
| `test_viewer_buffer`, new `item_headers`: one chunk, three chunks, no payload; non-item and empty objects (`NOT_ITEM`); rotation 45 and payload length off by one (`ITEM_MALFORMED`), media type 9 and item version 2 (`ITEM_UNSUPPORTED`); tampered first chunk (`AUTH_FAILED`); tampered later chunk (peek `PV_OK`, verify `AUTH_FAILED`); cut below one chunk of overhead (`TRUNCATED`), inside the first chunk (`AUTH_FAILED`), right after a full chunk (`MALFORMED_CHUNK`); wrong key (`WRONG_VMK`); missing (`NOT_FOUND`); null arguments | pass | pass |
| Total | **18/18** | **18/18** |

## T2 (emulator)

| Area | Result |
|---|---|
| The 67 existing tests (`VideoProbeTest` over the moved classes included) | pass (`LockBoundKeyTest` skipped as before) |
| `VideoViewerTest.galleryViewerDeleteAndSweep` (clip 1: gallery with one video tile, four segments hidden, thumbnail 192x256 erased on lock; lock during the slowed thumbnail decrypt ends with `CANCELLED` in 1.5 s; viewer `FLAG_SECURE` and secure surface, playback through 3 transitions with gaps 33, 33, 33 ms, four scripted seeks landing on the chosen key frame, pause and resume, a manual seek, a lock during playback releasing the player with no live buffer; damaged segment 1 stops playback with `AUTH_FAILED` after 89 frames of segment 0; delete removes the index and 4 segments. Clip 2: thumbnail failure hook gives the generic tile; read-failure hook and allocation hook on a reopened segment take the error path with the player released; a damaged index gives a damaged tile, the viewer's error state, and a skipped sweep; an interrupted delete hides the clip and the next unlock's sweep removes 2 orphans; leak scan clean) | pass, 99 s |
| `VideoViewerTest.recordingRightAfterUnlockLosesNoSegment` | pass, 12 s |
| Totals (`build/p3d/t2-d8-results.xml`) | **69 tests, 0 failures, 0 errors, 1 skipped**, 349 s |
| Host-driven: merged manifests, class scan, APK sizes | table above |
| Scripted run (`E-caps.txt`, `E3-read30.txt`) | gallery: 15 objects classified, 2 shown; captures of the gallery, the playing picture, and the held frame black (40 465 of 40 488 samples `000000`); recents card black; leak scan 0 findings; 30 s clip: 8 transitions, 9 opens and 9 closes, read-ahead of about 20 s then one buffer held open while the loader waits (design 4.3) |

## T3 (Xiaomi)

| Step | Result |
|---|---|
| Install the Milestone A debug build (developer's tap) | done (version string 0.5.0 then) |
| Record clip 1 at the approved settings (`A1-record.txt`) | 11.6 s, 4 segments, 29 fps, 7.1 MB, key counts agree on every segment, leak scan 0 (63 files) |
| Thumbnail trial on the hardware decoder (`A2-thumb.txt`) | buffer mode works: 405 to 535 ms per run, buffer live 284 to 336 ms, decode 43 to 51 ms, bitmap 256x192 turned to 192x256, mean luma 58 |
| Install the 0.6.0 debug build (developer's tap), record clip 2 (`T1-record2.txt`) and clip 3 (`T2-record3.txt`) | done after the phone was reattached; clip 2: 11.5 s, 4 segments, 29 fps, key counts agree; clip 3: 5.5 s, 2 segments; leak scan 0 after each (68, 71 files) |
| Gallery on the debug build (`T3-gallery.txt`) | 66 objects classified in **413 ms**, 56 shown: 3 video tiles with durations 0:06, 0:12, 0:12 and no segment tile, 49 photos, 2 large items, 2 unknown items; thumbnail 192x256 in 446 ms; the sweep after the unlock took 515 ms over 66 objects and deleted nothing |
| Viewer on the debug build, clip 2, six scripted seeks (`T4b-viewer.txt`) | first frame **408 ms**; boundary gaps 33, 33, 33 ms; 0 dropped of 345; codec applied the rotation (`size 1200x1600 unapplied_rotation=0`); 6 of 6 seeks on the chosen key frame at 33 to 502 ms; one live buffer at a time (2.06 MB peak), opens equal closes; PSS peak 161 MiB |
| Benchmark build over the debug build (developer's tap), vault kept | done; `versionName` 0.6.0, no debuggable flag; the vault opened with the same PIN (unlock 0.9 to 1.0 s) |
| Picture confirmed by eye (benchmark build, clip 2) | **the developer saw the scene, upright, playing smoothly** |
| Gallery on the benchmark build (`T5-byhand.txt`) | 66 objects classified in 115 ms; three video thumbnails in 214 to 382 ms each (buffer live 186 to 272 ms) |
| Play, pause, resume, seek by hand (benchmark, clip 3 and clip 2) | first frame 154 to 168 ms; boundary gaps 33, 16 and 33, 33, 16 ms; 0 dropped frames; pause and resume logged; a drag of the bar seeked through the index rule (69 ms to the frame) |
| Home during playback | `onStop` released the player (0 ms, no live buffer, opens equal closes), the background lock followed 0.8 s later |
| Screen off during playback | the stop released the player, the background lock followed (the stop beat the screen-off broadcast, as in 3C) |
| MIUI camera during playback (Home, camera from the launcher, back) | the stop released the player, the lock followed; PrivateVault came back at the lock screen |
| Recents during playback (`T6-recents.png`) | the developer saw the PrivateVault card white and blank; the capture holds the recents view (1080x2400, mostly black with the launcher's red); opening recents stopped the viewer, released the player, and the lock followed |
| Peak memory during playback | app sampler PSS peak 140 to 155 MiB during playback (benchmark), 161 MiB (debug); the two-second `dumpsys meminfo` sampler read up to 141 MB during playback and 353 MB during the PIN unlock (Argon2id, 256 MiB) |
| Delete one clip (clip 3, benchmark build, `T7-delete.txt`) | index `PV_OK`, then segments 0 and 1 `PV_OK`; the gallery came back with the two 0:12 tiles; clips 1 (`3c55a432...`) and 2 (`39b46d41...`) stay on the phone as the 3E regression clips |
| `leak_scan` (benchmark build, the vault screen button, run twice) | **0 findings, 68 files scanned**; MediaStore rows owned by the app 0, 0, 0 |

## Playback, seek, and memory figures per device (measured)

| Measurement | Emulator (T2 run, `t2-d7-viewer-logcat.txt`) | Emulator (scripted, 12 s and 30 s clips) | Xiaomi debug build (`T4b-viewer.txt`) | Xiaomi benchmark build (`T5-byhand.txt`, `T6-recents.txt`) |
|---|---|---|---|---|
| First frame | 668 ms | 960 ms, 856 ms | 408 ms | 154 to 168 ms (five runs) |
| Boundary gaps | 33, 33, 33 ms | 99, -40, 33 ms; 66, 16, 66, 9, -33, 149, 183, 33 ms (under load) | 33, 33, 33 ms | 33, 16; 33; 33, 33, 16 ms |
| Seek latency and landing | 223 to 708 ms, the chosen key frame exactly, 4 of 4 | 205 to 659 ms, 6 of 6 | 33 to 502 ms, 6 of 6 exactly | one drag of the bar, 69 ms to the frame |
| Live guarded buffers | peak 1 (1.94 MB) | peak 1 (1.41 MB) | peak 1 (2.06 MB) | peak 1 (1.98 to 2.06 MB); opens equal closes in every run |
| PSS, native heap, Java heap peaks | 79.5, 20.7, 10.7 MiB | 64.2 to 67.6, 18.3, 16.1 MiB | 161, 21, 8.6 MiB | 140 to 155, 25 to 28, 8 to 9 MiB |
| Dropped frames | 17 of 283 | 3 of 203; 4 of 424 | 0 of 345 | 0 in every run |
| Thumbnail | 192x256 in 281 to 867 ms, buffer live 152 to 505 ms | | 405 to 535 ms (trial), 446 ms (gallery), buffer live 284 to 336 ms | 214 to 382 ms, buffer live 186 to 272 ms |
| Gallery classification | 5 objects in well under a second (sweep over the same: 8 to 35 ms) | 15 objects | 66 objects in 413 ms (sweep 515 ms) | 66 objects in 115 ms (sweep 26 ms) |
| `release()` to the last buffer closed | 0 ms | 9 ms | 0 ms | 0 ms |

## Plaintext locations

`docs/PHASE3D_DESIGN.md` Section 5, `docs/THREAT_MODEL.md` Section 10. Measured: one
guarded segment buffer live at a time (never two in any run, emulator and the 3C phone
runs), freed within 9 ms of `release()` returning in the scripted run and 0 to 5 ms in
the test run; the thumbnail's segment buffer freed before the decoder is waited on;
ExoPlayer's sample queue holds about 20 s of a long clip on the emulator (not wiped by
the app); `FLAG_SECURE` and the secure surface keep the frames out of captures and the
recents card on the emulator, and the recents card is blank on the Xiaomi by the
developer's eye.

## Defects and decisions

| Item | What | Where |
|---|---|---|
| Sweep deleted the segments of a clip whose index had a damaged first chunk | the header peek fails on such an index, and the first sweep version passed over it as a non-item; the sweep now skips whenever any object's first chunk fails to decrypt; found by T2 in Milestone D | `VideoItems.sweep`, design 4.6, D-035 |
| Boundary cut of an object | `pv_object_item_header` reports `MALFORMED_CHUNK` for a file cut right after a full chunk (no room for a final chunk), the 3B length rule; the design test plan first said otherwise | design Section 7 |
| `VideoProbeTest` assumed ten seeks | a 10 s clip the loaded emulator cut into three segments gives nine landings; the probe records the planned count and the test asserts against it | `VideoProbeActivity`, `VideoProbeTest` |
| Background lock under a test recording | with no activity in front the app locks 700 ms after the last one stopped (3A rule); the test waits it out and unlocks | `VideoViewerTest.ensureUnlocked` |
| Read-ahead larger than the load control | ExoPlayer extracted six segments (about 20 s) of a 30 s clip at once and held the next buffer open while paused; recorded, not changed | design 4.3, threat model 10.3, roadmap (3E) |
| Emulator exited between the build and T2 | no error in its log; rebooted | `docs/PHASE3D_PROGRESS.md` |
| Phone dropped off adb before the 0.6.0 install | reattached by the developer; T3 completed | `docs/PHASE3D_PROGRESS.md` |
| The scripted entry reports "finished" as soon as the viewer opens | the phone viewer run was captured with a long-running logcat instead (`phone-p3d.sh log-start`) | `build/p3d/phone-p3d.sh` |

Decisions: D-032 to D-036 approved at the checkpoint, D-035 and D-036 amended; D-035
tightened after the sweep defect.

## Known limits (assumptions, not measured)

- The read-ahead bound for long clips comes from ExoPlayer's load control and was
  measured on one 30 s clip on the emulator only.
- The emulator picture was checked by capture and by the codec's reported size, not by
  eye; the by-eye check was done on the phone.
- The by-hand interrupt cases on the phone (Home, screen off, camera, recents) all reach
  the viewer through `onStop`; the screen-off and camera locks were logged with the
  background reason because the stop beat the broadcast, as in 3C.
- The peak PSS during playback is the app's own 200 ms sampler; the 2 s `dumpsys`
  sampler agrees within a few MB. The 353 MB reading is the PIN unlock, not playback.
- A codec failure during playback is not forced; the handler is exercised through the
  read-failure hook.
- H.265 (index codec 2) is accepted by the index and gives the generic tile; no such clip
  exists.

## Acceptance checklist (brief Section 8)

| # | Criterion | Evidence |
|---|---|---|
| 1 | Phase 1, 2, 3A to 3C T1 and T2 suites pass | T1 18/18 Debug and Release; T2 69 tests, 0 failures |
| 2 | Videos appear as single gallery tiles with thumbnail and duration; segments not listed | T2 gallery cases; scripted run: 15 objects, 2 shown |
| 3 | Playback, pause, seek in the release-shaped build on emulator and Xiaomi; picture confirmed by eye on the phone | emulator: T2 and scripted; phone: benchmark build, the developer saw the scene upright and smooth, pause, resume, and the bar seek logged |
| 4 | Lock during playback or thumbnail work releases the player and wipes app-owned buffers | T2 lock cases (no live buffer, `CANCELLED` on the thumbnail decrypt) |
| 5 | Damaged or missing segments never produce output from unverified data | T2: playback stops at the damaged segment with no frame from it; by construction (`viewerLoad` after the final chunk) |
| 6 | Delete removes index and segments; interrupted delete leaves no gallery entry; sweep removes orphans | T2 delete and sweep cases |
| 7 | `FLAG_SECURE` during playback; recents blank; `leak_scan` clean on both devices | emulator: captures and recents black, leak scan 0; phone: recents card blank by eye, leak scan 0 findings in 68 files |
| 8 | Release and benchmark manifests unchanged (`CAMERA` only, only `LockActivity` exported); APK size recorded | table above; 15.7 MB |
| 9 | Core changes additive; all golden vectors pass | one new call; `test_golden` in T1 |
| 10 | Threat model updated: player buffers, thumbnail, idle rule | `docs/THREAT_MODEL.md` Section 10 |
| 11 | Report separates demonstrated facts from assumptions | this report, "Known limits" |

## Phone state after Stage 3D

Benchmark build 0.6.0 over the kept vault: 49 photos, 4 Phase 1 samples, and the two
regression clips for 3E (`3c55a432...`, 11.6 s, and `39b46d41...`, 11.5 s, each 4 segments
and one index): 63 objects. Nothing else was deleted. The emulator holds the 0.6.0 debug
build with a throwaway-PIN vault of two scripted clips.

## Files changed

Core: `src/vault_core/include/pv/pv.h`, `src/vault_core/src/viewer.cpp`,
`tests/core/test_viewer_buffer.cpp`. Android main: `build.gradle.kts`, `AndroidManifest.xml`,
`jni_bridge.cpp`, `NativeBridge.kt`, `Vault.kt`, `GuardedSegmentDataSource.kt` (moved from
debug), `VideoFirstFrame.kt` (new), `VideoItems.kt` (new), `VideoActivity.kt` (new),
`GalleryActivity.kt`, `LockController.kt`, `MainActivity.kt`. Android debug:
`AndroidManifest.xml`, `DebugVideoProbes.kt`, `ScriptActivity.kt`, `VideoProbeActivity.kt`,
`VideoThumbProbe.kt` (new). Tests: `VideoViewerTest.kt` (new), `VideoProbeTest.kt`. Docs:
`PHASE3D_DESIGN.md`, `PHASE3D_PROGRESS.md`, this report, `DECISIONS.md` (D-032 to D-036),
`THREAT_MODEL.md` Section 10, `ROADMAP.md`, `DEPENDENCIES.md`. Helper scripts and logs
under `build/p3d/` are not committed. The suggested commit message is in
`docs/PHASE3D_PROGRESS.md`.
