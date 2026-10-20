# Phase 3C test report

Date: 2026-10-20. Format per `PHASE3C_BRIEF.md` Section 12. Every result below comes from a
command that was run; nothing is inferred. "Not run" means exactly that. Measured values
are marked; everything else is design reasoning.

## Status

**Complete.** The key-frame mismatch of Phase 2 is explained with box-level evidence (a
reading difference between the framework extractor and media3, not a lost flag); the
segment, GOP, and fragment settings were chosen from measurements on both devices and
confirmed by the key-frame counts agreeing across encoder, container, media3, and index on
every segment recorded since; index v1 is implemented in the core with T1 tests and golden
vectors and mirrored on Android; playback from guarded memory through ExoPlayer works on the
emulator and on the Xiaomi with first frame, boundary gaps, seek latency, and memory
measured; a lock during playback and a lock during a segment decrypt release the player and
free the app-owned buffers; the plaintext locations are listed; `FLAG_SECURE` holds; the
leak scan is clean on both devices; release and benchmark builds define no media3 class and
their manifests are unchanged. Two media3 behaviours (no `tfdt` or `sidx` in the muxer
output; the last-sample duration rule) were found, worked around in the probe, and handed to
3E. The phone cleanup through the app's delete action removed the 45 video objects and kept the photos ("Phone cleanup").

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, NDK, libsodium, Gradle 8.11.1, AGP 8.9.3, Kotlin, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2 | As in `docs/PHASE3B_TEST_REPORT.md` (unchanged) |
| JDK for Gradle | Eclipse Temurin 21.0.12.1+1 at `D:\tools\jdk-21` through `JAVA_HOME` (Android Studio AI-262 bundles JDK 25, which Gradle 8.11.1 refuses; `docs/DEPENDENCIES.md`) |
| media3 | 1.11.1: `media3-muxer` and `media3-extractor` (3B, 3C Milestone A), `media3-exoplayer` (Milestone D), all `debugImplementation` |
| App version | 0.5.0 (`versionCode` 5) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), software H.264 codecs, Keystore `SOFTWARE`, no screen lock |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, `c2.qti.avc.encoder`, hardware decoder, Keystore `TRUSTED_ENVIRONMENT`, no screen lock |

## Key-frame findings (brief Section 5; measured)

`docs/PHASE3C_DESIGN.md` Section 2. Twelve clips, 48 segments, both devices, fragment
500, 1000, 3000 ms times I-frame interval 1 and 2 s. The muxer writes every encoder key frame
as a per-sample sync flag in `trun` (flags `0x701`, `sample_is_non_sync_sample` clear on the
key frames); media3's `FragmentedMp4Extractor` reports those; the framework `MediaExtractor`
reports sync only for the first sample of each fragment (its count equals the `moof` count in
every clip). Hypothesis H1 (a reading difference) supported; H2 (flag lost in the muxer), H3
(conversion), and H4 (encoder) rejected. `KEY_I_FRAME_INTERVAL` is counted in frames at the
configured rate (2.18 s apart on the phone at 14 fps delivered against 30 configured).
Fragments start only on key frames.

## Chosen settings (CP-1; measured behind each)

| Setting | Value | Evidence |
|---|---|---|
| Segment | 3 GOPs, cut on the encoder's actual key-frame output (the key frame after the third GOP starts the next segment) | segments of 3100 to 3334 ms on both devices; first sample a key frame on every segment of every run (encoder flag and container flag) |
| GOP | sync request one GOP (1 s) after the last key frame; `KEY_I_FRAME_INTERVAL` 10 s backstop | key frames 1033 to 1124 ms apart (the request lands on the next frame or two) |
| Fragment | 1000 ms | one `moof` per GOP; framework count equals the encoder count on every full segment |
| Frame rate given to the encoder | measured over an `ImageReader` for 1.5 s before the encoder exists | emulator 29 to 30 fps; phone 24 fps on the aimed clip; the reader needs `USAGE_GPU_SAMPLED_IMAGE` on the Xiaomi (Milestone E), otherwise the fallback 30 is used and the index records the delivered rate of the encoder output |
| Bitrate | 4 Mbit/s requested | emulator 4.2 to 5.6, phone 4.85 to 4.90 Mbit/s delivered |

## Index format v1 (CP-2, CP-3, CP-4)

`docs/PHASE3C_DESIGN.md` Section 4, D-030. Magic `PVVINDEX`, 64-byte header, 48-byte segment
records each followed by its `u32` key offsets; bounds 16384 segments per index, 4096 keys
per segment, 8 MiB per index; codes 27 to 29; `pv_video_index_size`, `_encode`,
`_decode_header`, `_decode`, `_locate` in `src/vault_core/src/video_index.cpp`; the Phase 2
prototype magic stays `PV_ERR_NOT_INDEX`. Sample times inside a segment start at zero; the
index carries the clip offset. Measured sizes: 296 to 304 bytes for a 4-segment clip.

## Commands

```
# Windows core (T1)
cmake --build --preset windows-x64-tests-debug;  ctest --preset windows-x64-tests-debug      # 18/18
cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 18/18
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --video-index

# Android (T2), JAVA_HOME = D:\tools\jdk-21
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
gradlew.bat connectedDebugAndroidTest
gradlew.bat :app:dependencies --configuration debugRuntimeClasspath      # and releaseRuntimeClasspath
python -I build\p3b\dexclasses.py <three apks>
aapt2 dump xmltree --file AndroidManifest.xml <apk>

# Emulator scripted (debug build, throwaway PIN of README.md)
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_probe=12000,leak_scan
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,video_play=latest:hold=25000

# Xiaomi (build/p3c/phone-p3c.sh; the developer's throwaway PIN is given on the command line only)
phone-p3c.sh install-debug | record <pin> 12000 | play <pin> latest[:secure=0][:hold=<ms>] <log>
```

## T1 (Windows)

| Test | Debug | Release |
|---|---|---|
| The 17 existing tests | pass | pass |
| `test_video_index` (round trips 1, 3, 1000 segments; every header and segment rule; truncation at every length and a trailing byte; `header_len` 72 accepted, refused by the writer; 4096 keys per segment accepted, 4097 refused; 16384 segments accepted, 16385 refused; above 8 MiB refused by `size`, `encode`, `decode`; three segments with 4096 keys each accepted; `locate`; arguments; error names) | pass | pass |
| `test_golden` with the four new vectors (decode, re-encode to the same bytes, the index item through the item sink) | pass | pass |
| Total | **18/18** | **18/18** |

## T2 (emulator)

| Area | Result |
|---|---|
| The 63 existing tests | pass (`LockBoundKeyTest` skipped as before: no lock screen on the emulator) |
| `VideoIndexTest` (bridge round trip, committed vectors decoded and re-encoded, the index item read through a vault under the test key, the seek rule, rejections) | pass |
| `VideoProbeTest` (10 s recording at the approved settings: counts agree on every segment, first sample a key frame, index times equal the encoder log, container key times within the muxer shift; playback to the end, 3 transitions, 10 seeks each rendering the chosen key frame; lock during playback; lock during a slowed decrypt ends the open with `PV_ERR_CANCELLED`; leak scan clean; FLAG_SECURE and secure surface) | pass |
| Totals (`build/p3c/t2-t2e-results.xml`, and `t2-t2f-results.xml` with the final code) | **67 tests, 0 failures, 0 errors, 1 skipped** in both runs |
| Lint | 0 errors (`RestrictedApi` as error), 72 to 74 warnings |
| Classes defined (`dexclasses.py`) | debug 2637 `androidx.media3` classes; benchmark 0; release 0; `androidx.camera.video` 0 in all three |
| Merged manifests | debug adds `VideoProbeActivity` (not exported); `WAKE_LOCK` from `media3-exoplayer` removed in the debug manifest; no service, receiver, or provider added; benchmark and release unchanged (CAMERA permission, the four activities, the disabled CameraX service) |
| Scripted run | recording 12 s, 4 segments, counts agree, leak scan 0; playback: screen capture during playback and during the held last frame black (FLAG_SECURE), recents card black |

## T3 (Xiaomi)

| Step | Result |
|---|---|
| Install 0.5.0 debug (developer's tap) | done; a second and third install for the meter fix, the secure switch, and the pixel sample went through the same way |
| Record at the approved settings | two clips; encoder, container, framework, media3, and index key counts equal on every segment; first sample a key frame; index times equal the encoder log; leak scan 0 (98 to 103 files) |
| Playback, seek, measure (hardware decoder) | eight runs; first frame 327 to 542 ms; boundary gaps 33, 33, 33 ms (one frame period; 16 ms once per run in two runs); 0 dropped frames; 10 of 10 seeks render the chosen key frame exactly, latency 142 to 373 ms; one live guarded buffer, 2.25 MB peak; PSS 149 to 161 MiB; `release()` to last buffer closed 0 to 5 ms (`docs/VIDEO_PLAYBACK_PROBE.md` Section 6) |
| Home during the probe | the stop released the player and its buffers; the background lock followed; launcher in front |
| Screen off during the probe | the stop released the player; the lock followed |
| MIUI camera | Home, then the camera from the launcher: the stop released the player, the camera opened, the lock followed |
| Recents card | blank PrivateVault card (`E7-recents.png`); screen capture during the hold empty |
| Picture on screen | first clip near-black (recorded with the camera facing the desk; surface mean luma 6); aimed clip mean luma 134 to 136 on the secure surface, decoded first frames 128 to 137; the developer was asked and had not answered by the end of the session; the pixel samples stand as the evidence |
| `leak_scan` | 0 findings after every recording and after the session |

## Playback measurements per device

| Measurement | Emulator (software decoder) | Xiaomi (hardware decoder) |
|---|---|---|
| First frame | 644 ms (835 to 2310 in other runs) | 327 to 542 ms |
| Boundary gap | 33, 33, 33 ms (one frame); under load 33, -37, 66 | 33, 33, 33 ms; 16 once in two runs |
| Seek latency | 183 to 702 ms | 142 to 373 ms |
| Seek landing | the chosen key frame exactly, 10 of 10 | the chosen key frame exactly, 10 of 10 |
| Live guarded buffers | peak 1 (1.75 MB) | peak 1 (2.25 MB) |
| PSS, native, Java heap peaks | 77.8, 17.9, 16.8 MiB | 161, 21.0, 11.2 MiB |
| Dropped frames | 4 of 306 | 0 of 177 and 0 of 295 |
| Segment decrypt at open (debug build) | 5 to 232 ms, typically 20 to 80 | inside the seek latency (3B viewer: 120 to 150 ms for 2 MB) |

## Plaintext locations during playback (CP-5)

`docs/PHASE3C_DESIGN.md` Section 5.3, `docs/THREAT_MODEL.md` Section 9. The app wipes the
guarded segment buffers (one live at a time, freed within 5 ms of `release()` returning on
both devices); ExoPlayer's sample queues and extractor scratch (Java heap), the decoder's
input and output buffers (native, platform), and the compositor are outside the app's
reach; `FLAG_SECURE` and the secure surface keep the frames out of captures and recents on
both devices.

## Defects and decisions

| Item | What | Where |
|---|---|---|
| media3 muxer writes no `tfdt` and no `sidx` | the extractor reports an unseekable stream and renumbers a fragment from zero after a seek to its offset; the first probe build rendered wrong frames after seeks inside a segment while the masked position looked right; fixed in the probe by a seek map from the fragment table and re-based sample times; 3E writes `tfdt` or `sidx` in its own muxing step | `docs/VIDEO_PLAYBACK_PROBE.md` Section 4, `docs/ROADMAP.md` |
| media3 muxer last-sample rule | the last sample of a fragment gets the previous sample's duration; container times shift after an uneven gap before a key frame (141 ms once under load); the index keeps the encoder times (D-030); seeks still land within one GOP | `docs/VIDEO_PLAYBACK_PROBE.md` Section 5 |
| `ImageReader` frame-rate meter got no frames on the Xiaomi | the reader needs `USAGE_GPU_SAMPLED_IMAGE`; the index now records the delivered rate when the meter fails | Milestone E, `DebugVideoProbes.kt` |
| Same-position seek renders nothing; first-frame callback can arrive late from an earlier seek | the probe plans distinct landings and waits on the frame listener | `VideoProbeActivity.kt` |
| `release()` returns before the loader closes an open data source | the probe waits for the close (0 to 5 ms measured) | `VideoProbeActivity.kt` |
| Scripted runs on the phone need the screen awake | a dozing screen, or the screen timing out during the 12 s PIN unlock, stops the activities and locks the vault (two runs lost) | this report, `docs/PHASE3C_PROGRESS.md` |
| Unlock time on the phone | debug-build unlock at the final KDF default (ops 2, 256 MiB) is 12 to 21 s on the phone, consistent with 3A and 3B (3B measured 12.8 s debug, 0.97 s benchmark); the benchmark build unlocks in under 1 s | known limit below |

Decisions: D-028 to D-031 approved and implemented as written; no new decision.

## Known limits (assumptions, not measured)

- The by-hand interrupt cases on the phone hit the probe while it held the last frame after
  the 11.6 s clip (the player alive and paused), not mid-clip; the code path (`onStop`
  releases, the lock follows) is the same.
- The scripted runs on the phone used the debug build, whose unlock at ops 2 / 256 MiB
  takes 12 to 21 s (consistent with 3A and 3B); the benchmark build unlocks in under 1 s.
  The wait only sets the timing of the by-hand actions; it does not enter the measurements.
- No measurement of the ExoPlayer sample-queue footprint beyond the Java heap peak.
- The emulator picture was checked by pixel sample, not by eye.

## Acceptance checklist (brief Section 10)

| # | Criterion | Evidence |
|---|---|---|
| 1 | Phase 1, 2, 3A, 3B T1 and T2 suites pass | T1 18/18 Debug and Release; T2 67 tests, 0 failures |
| 2 | Key-frame mismatch explained with log and box evidence | design Section 2, 12 clips, 48 segments |
| 3 | Settings chosen with measurements on both devices | design Section 3; counts agree on every segment since |
| 4 | Index v1 documented, in the core, T1 tests and golden vectors | design Section 4, `video_index.cpp`, `test_video_index`, 5 fixtures |
| 5 | Playback from decrypted memory on both devices with gaps, seek latency, memory measured | `docs/VIDEO_PLAYBACK_PROBE.md` Sections 3 and 6 |
| 6 | Lock during playback stops it, releases the player, wipes app-owned buffers | `VideoProbeTest`; phone Home, screen off, camera cases |
| 7 | Plaintext locations listed with which the app can wipe | design Section 5.3, threat model Section 9 |
| 8 | `FLAG_SECURE` holds; `leak_scan` clean on both | captures black or empty, recents black or blank; 0 findings everywhere |
| 9 | Release and benchmark unchanged: no media3, manifests unchanged | class scan 0; manifests identical to 3B apart from the version |
| 10 | Report separates facts from assumptions | this report, "Known limits" |

## Phone cleanup (measured)

After the developer's go, 45 objects were deleted through the app's `delete_object` action
(`build/p3c/phone-cleanup.sh`, log `E11-cleanup.txt`): the Phase 2 prototype clip of
2026-10-13 (4 segments, 1 index), the Milestone A matrix of 2026-10-19 (24 segments, 6
prototype indexes), and the Milestone E clips of 2026-10-20 (8 segments, 2 index v1 objects).
45 of 45 returned `PV_OK`; `list` shows 53 objects and no partial; every remaining object is
one of the 49 photos or the 4 Phase 1 synthetic samples of the earlier listing; the leak
scan after it: 0 findings, 58 files scanned. The phone keeps its vault and the debug build
0.5.0.

## Files changed

See `docs/PHASE3C_PROGRESS.md` (Milestone logs) and the suggested commit message there.
