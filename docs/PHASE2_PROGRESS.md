# Phase 2 progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE2_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-13)

Milestones A to F done, including T3 on the Xiaomi (second session of 2026-10-13, phone
connected, developer providing the taps). T1 14/14 Debug and Release, T2 30 tests on the
emulator (31 after the D-015 test, 1 skipped without a PIN, passed in the PIN run), lint
clean, scripted activity runs, lock-bound PIN procedure, benchmark build figures on the
emulator and the phone,
Android-to-Windows item vectors, video prototype and feasibility report with phone figures
all done. One phone-found defect fixed (D-015). The
Milestone B checkpoint was approved by the developer on 2026-10-13 with four amendments
(item sink rules, `PV_ERR_NOT_ITEM`, the `VideoOutput` restriction gate with lint, video
low-space handling), all applied to `docs/PHASE2_DESIGN.md` before Milestone C started.
Nothing is committed; the suggested commit message is at the end of the test report.

Open: the developer has not updated Android Studio (still Ladybug Feature Drop, build 242),
which cannot open an AGP 8.9 project. Command line builds do not need it. The benchmark
APK is the build installed on the phone at the end of T3; reinstalling the debug APK needs
another on-screen tap. 52 test objects (about 190 MB) remain in the phone vault.

## Milestone C log (2026-10-13)

Core additions (all additive, `src/vault_core/include/pv/pv.h`): error codes 21 to 23
(`PV_ERR_ITEM_MALFORMED`, `PV_ERR_ITEM_UNSUPPORTED`, `PV_ERR_NOT_ITEM`), the item header
constants and struct, `pv_item_header_encode`, `pv_item_header_decode`,
`pv_item_header_check_len`, `pv_source_memory_new` and `_free`, `pv_sink_item_new`,
`_finish`, `_abort`. Implementation in `src/vault_core/src/item.cpp` (header codec and item
sink) and `streams.cpp` (memory source). `pv verify` now prints the item fields or
`item: unknown`. New host tests `test_memory_source`, `test_item_header`,
`test_item_objects`; `test_cli` checks the new verify line; `test_golden` reads the
extended manifest and decrypts item vectors through the item sink. Four item vectors were
added to `tests/fixtures/v1` with `make_vectors --items` (the eight Phase 1 vectors are byte
for byte unchanged; their manifest lines gained six `-` columns).

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --items   # once
ctest --preset windows-x64-tests-debug            # 14/14 passed, 28 s
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release          # 14/14 passed, 11 s
```

No compiler warnings at `/W4` in either configuration.

## Milestone A log (2026-10-13)

1. `git status`: clean on `main` at `eac9f2e phase 1 completion`. Working directory
   `D:\repo\PrivateVault`.
2. Phase 1 document fixes from the brief, Section 1, applied in place:
   `docs/PHASE1_TEST_REPORT.md` checklist row T2 now says 17/17; the Architecture section
   says `setUnlockedDeviceRequired(true)` applies only with a secure lock screen (D-010);
   Files changed lists D-001 to D-011; `docs/DECISIONS.md` preamble no longer mentions
   pending checkpoint items. No recorded result was changed.
3. Dependency metadata check (brief, Section 4.2). Google Maven group indexes were read with
   `curl`; the AARs were downloaded to `build/deps-check/` (not committed) and
   `META-INF/com/android/build/gradle/aar-metadata.properties` read with `unzip -p`:

   | Artifact | minCompileSdk | minAGP |
   |---|---|---|
   | CameraX 1.6.2 (core, camera2, lifecycle, view, video) | 36 | 8.9.1 |
   | CameraX 1.5.3 | 35 | 8.6.0 |
   | CameraX 1.4.2 | 34 | 1.0.0 |
   | media3-muxer and media3-common 1.11.1 | 36 | 1.0.0 |
   | media3-muxer and media3-common 1.10.1 | 36 | 1.0.0 |

   Newest stable at check time: CameraX 1.6.2, media3 1.11.1, AGP 8.9.3 in the 8.9 line
   (newest AGP overall 9.4.1). Details in `docs/DEPENDENCIES.md`.
4. Toolchain change per P2-1, option U (D-012): `android/settings.gradle.kts` AGP 8.8.2 to
   8.9.3; `android/gradle/wrapper/gradle-wrapper.properties` Gradle 8.10.2 to 8.11.1 with
   the published SHA-256 `f397b287...51c6`; `android/app/build.gradle.kts` compileSdk 35
   to 36. targetSdk 35, minSdk 28, Kotlin 2.1.10, NDK, CMake, vcpkg unchanged. The wrapper
   jar, `gradlew`, and `gradlew.bat` at tag `v8.11.1` are byte for byte those of `v8.10.2`
   (jar SHA-256 `2db75c40...8046` matches the published `gradle-8.11.1-wrapper.jar.sha256`),
   so those files were not touched.
5. Regression gate:

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug
   ctest --preset windows-x64-tests-debug           # 11/11 passed, 39 s
   cmake --build --preset windows-x64-tests-release
   ctest --preset windows-x64-tests-release         # 11/11 passed, 22 s
   emulator -avd Pixel_8_API_35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
   cd android
   gradlew.bat --version                            # downloaded Gradle 8.11.1 into D:\Android\gradle
   gradlew.bat assembleDebug assembleDebugAndroidTest   # BUILD SUCCESSFUL in 3m 32s (AGP 8.9.3 resolved from Google Maven)
   $env:ANDROID_SERIAL = "emulator-5554"
   gradlew.bat connectedDebugAndroidTest            # 17 tests, 0 failures, 0 errors, 0 skipped (Pixel_8_API_35, Android 15)
   ```

   APK after the change (`aapt2`): `compileSdkVersion 36`, `minSdkVersion 28`,
   `targetSdkVersion 35`, `allowBackup=false`, no `uses-permission`, one activity.
   Side effects: none. `D:\Android\Sdk\build-tools` still holds 35.0.0 and 37.0.0 and
   `platforms` still holds android-35 and android-36; AGP installed nothing (only the
   `.knownPackages` metadata file was rewritten). Gradle 8.11.1 and the AGP 8.9.3 artifacts
   were downloaded into `GRADLE_USER_HOME` (drive D).
6. Emulator camera inventory (`pm list features`, `dumpsys media.camera` on
   `Pixel_8_API_35`, Android 15): two camera devices.

   | Camera id | Facing | Hardware level | Sensor orientation | Pixel array | Notes |
   |---|---|---|---|---|---|
   | 1 (device 0) | front | FULL | 270 | 1920x1440 (max-resolution mode 6048x4024) | logical multi-camera over physical ids 4 and 5; capabilities include RAW, ULTRA_HIGH_RESOLUTION_SENSOR, STREAM_USE_CASE |
   | 10 (device 1) | back | LIMITED | 90 | 1280x960 | `hw.camera.back = virtualscene`, BACKWARD_COMPATIBLE and RAW only |

   Features present: `camera`, `camera.any`, `camera.front`, `camera.autofocus`,
   `camera.flash`, `camera.level.full`, `camera.concurrent`, `camera.capability.raw`,
   `manual_sensor`, `manual_post_processing`. The AVD config has
   `hw.camera.front = emulated`, `hw.camera.back = virtualscene`.
7. CameraX and media3 class files inspected with `javap` to settle the video options
   (`docs/PHASE2_DESIGN.md` Section 8.1): `VideoOutput` is a public interface whose only
   abstract method is `onSurfaceRequested(SurfaceRequest)`; `VideoCapture.withOutput` is
   public; `FragmentedMp4Muxer` is `@UnstableApi` and takes an `OutputStream` or
   `WritableByteChannel`; `Recorder.prepareRecording` accepts only file, descriptor, or
   MediaStore output options.

## Milestone B log (2026-10-13)

`docs/PHASE2_DESIGN.md` written: photo pipeline and components, item header layout (48
bytes, `header_len` 48 to 4096), item sink, memory source, C ABI additions with error codes
21 to 23, JNI entry points and buffer handling, failure table, leak-scan design, video
options with the V3 custom `VideoOutput` variant first, prototype plan, build variants,
threat model deltas, test plan, open items. `docs/DECISIONS.md` D-012. `docs/DEPENDENCIES.md`
updated for Gradle 8.11.1, AGP 8.9.3, and the metadata check.

Checkpoint report sent to the developer. Milestone C waits for approval.

## Toolchain inventory delta (2026-10-13)

Same machine as Phase 1 (`docs/PHASE1_PROGRESS.md`). Changes: Gradle 8.11.1 in
`D:\Android\gradle\wrapper\dists`; AGP 8.9.3 in the Gradle cache. `adb devices` at the
start of the session: no device attached; the Xiaomi is needed again for Milestone F. The
emulator was started headless for the regression run and stopped afterwards.

## Milestone D log (2026-10-13)

Implemented: `PvApp` (process-scoped `VaultSession` and `CaptureSession`), `CaptureActivity`
(`PreviewView`, shutter, flip, status, `FLAG_SECURE`, runtime `CAMERA` request, scripted
actions), `CaptureSession` (single encrypt thread, queue of 2, free-space precheck, peak
sampler, verify after encrypt), `LeakScan`, `ObjectActions` (`verify_all`, `list`),
`VideoProbes` seam, JNI entry points `nativeEncryptItem` (direct buffer),
`nativeEncryptItemArray`, `nativeDecryptVerifyItem`, `nativeDecryptFileItem`,
`nativeEncryptItemSampleFile`, debug-only `nativeDecryptToArray`, `nativeHashDirectBuffer`,
`nativeSetTestHooks` (behind `PV_ANDROID_DEBUG_HOOKS`, debug variant only). Manifest:
`CAMERA`, `camera.any` not required, `CaptureActivity` not exported, merge rules for
`ACCESS_NETWORK_STATE`, `InitializationProvider`, `ProfileInstallReceiver` (D-013).
Build: CameraX 1.6.2, activity 1.10.1, media3-muxer 1.11.1 (debug), test rules 1.6.1,
`benchmark` build type, `lint { error += "RestrictedApi" }`.

```
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug    # BUILD SUCCESSFUL; lint 0 errors, 11 warnings (GradleDependency x4, UseKtx x2, SetTextI18n x3, OldTargetApi, MissingApplicationIcon)
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest       # 30 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest without a PIN)
```

T2 classes: NativeInitTest 1, VaultTest 9, KeystoreTest 5, GoldenVectorTest 2 (12 vectors,
4 of them items; export of 6 vectors), CaptureTest 3, CaptureFailureTest 7, LeakScanTest 2,
LockBoundKeyTest 1. Emulator findings: every capture plane buffer is a
`java.nio.DirectByteBuffer` (`isDirect=true`, `hasArray=false`); back camera captures are
1280x960, about 570 KB JPEG, rotation 90; encrypt 8 to 80 ms; verify after encrypt ok;
storage-full ballast test: 177 files, `PV_ERR_IO` with errno 28, no `.partial`; unbind
during capture: `ERROR_CAMERA_CLOSED`; leak scan before and after: 0 findings, new files
only `objects/*.pvo`.

Scripted runs through the real activities (`build/emu-*.sh`, outputs under `build/emu/`,
not committed): `open,capture_photo_n=3,verify_all,list,leak_scan,memory` (3 captures,
verify_all 4 of 4, leak_scan 0, PSS 62 MB during the run); front camera (`flip`): 1920x1440,
rotation 270, direct buffer, encrypt 105 ms; kill during a slowed encrypt
(`test_hook_slow=1500`): one `.partial` (196 787 bytes) present during and after
`am force-stop`, removed by the next open, `verify_all` 15 of 15; recents thumbnail with the
capture screen in front: both `screencap` of the screen and the recents card are black
(`FLAG_SECURE`); permission revoked with `pm revoke` plus `user-fixed`: `camera:
PERMISSION_DENIED`, `capture refused: PERMISSION_DENIED`, no crash; re-granted: capture ok.
`/proc/<pid>/fd` during a capture (`run-as ls -l`): 164 entries, none under `/data/user`
other than the usual app and dex files, no file outside the vault directory open;
`dumpsys media.camera`: CONNECT and DISCONNECT lines for device 10 by this package.
Logcat scan of all collected logs (609 lines): 0 hex runs longer than 32 characters, no
base64 runs (the 6 regex hits are file paths); 30 distinct object IDs.

## Milestone E log (2026-10-13)

`android/app/src/debug/java/com/privatevault/app/DebugVideoProbes.kt`: V3 prototype and V2
probe, debug source set, installed through `VideoProbes` by reflection from `PvApp`. Results
and measurements in `docs/VIDEO_CAPTURE_FEASIBILITY.md`. Headlines: V3a (custom
`VideoOutput`) fails at bind with "MediaSpec can't be null" (restricted method required);
V3b (encoder surface as the `Preview` provider) records 10 s at 1280x960, 3.3 Mbit/s, four
segments of about 1 to 1.4 MB, 41 to 83 ms encrypt each, every segment parses and decodes
its first frame after decryption; V2 (`Recorder` over `openProxyFileDescriptor`) is
accepted by the recorder but writes with 70 back-patches out of 421 writes. Lint found one
restricted call in the prototype during development (`SurfaceRequest.getExpectedFrameRate`),
removed. D-014 records the segment buffering choice.

## Milestone F log (2026-10-13)

- Lock-bound key (brief 9.1), emulator: `locksettings set-pin`, `LockBoundKeyTest` passes
  (`key mode lock-bound`), fresh app vault created lock-bound, 1 MB encrypt and verify ok
  while unlocked, screen locked with `KEYCODE_SLEEP` (`isKeyguardShowing=true`), launch
  with `open,verify_all`: `[open] FAIL: java.security.InvalidKeyException: Keystore
  operation failed <- android.security.KeyStoreException: Device locked`; after unlock the
  same actions succeed; `locksettings clear`. The emulator Keystore is software-backed, so
  this exercises the code path only. The PIN used is not recorded here.
- Benchmark build (brief 9.2), emulator only (phone absent): `gradlew.bat assembleBenchmark`,
  `aapt2` shows `debuggable` absent and `CAMERA` the only permission; `run-as` refused
  (`package not debuggable`). Results from logcat: 1 MB encrypt 33 ms, 100 MB encrypt 816 ms,
  verify of both 1258 ms (Phase 1 debug emulator figures: 108 ms, 3584 ms, 6.1 s); 10
  captures, encrypt 29 to 80 ms each, capture-to-object 1483 to 1759 ms (the emulator camera
  itself takes about 1.5 s per still), native heap peak 17.6 to 18.3 MB, Java heap peak 4.6
  to 10.5 MB, PSS 33.6 MB after the run; verify_all 12 of 12; leak_scan 0.
- Android-to-Windows: six vectors exported by `GoldenVectorTest.exportVectorForWindows`
  (three plain, three item-wrapped) pulled with `adb exec-out run-as ... cat` and decrypted
  by `test_golden.exe D:\repo\PrivateVault\build\android-export`: all six ok, items parsed.
- Chunk size: Windows CLI on a 5 MB random file, three runs each, 64 KiB 231 to 278 ms,
  256 KiB 182 to 258 ms (both include the Argon2id interactive open). Default kept.
- T3 on the Xiaomi: run in the second session, see the T3 log below.
- Docs: design Section 14, threat model Section 6, roadmap, README, CLAUDE.md, decisions
  D-012 to D-014, dependencies, this log, the feasibility report, the test report.

## T3 log (2026-10-13, second session, Xiaomi 2201116SG)

Setup: `ANDROID_SERIAL=9c4424ba81c6`; debug APK installed with the developer's tap
(version 0.2.0 over the Phase 1 0.1.0, vault and Keystore key kept); camera permission
granted on screen. The phone's main logcat buffer is 256 KiB and lost the first run's lines
within minutes, so every later run streamed `adb logcat -s PrivateVault:I AndroidRuntime:E`
to a file (`build/phone-lib.sh`, outputs under `build/phone/`, not committed).

Runs, in order: `open,capture_photo_n=10,verify_all,list,leak_scan,memory` with
`/proc/<pid>/fd` and `dumpsys media.camera` captured while the camera was bound;
`open,verify_all,list` after `am force-stop`; `open,test_hook_slow=1500,capture_photo` with
`am force-stop` at 9 s, then `open,list`; `open,tamper,truncation,wrong_key,security_level`;
`open,video_probe,list,leak_scan,memory`; `open,v2_probe,leak_scan`; manual captures by the
developer (5 portrait, 5 landscape, 1 with Home at the shutter) with the log streamed; the
camera-busy attempt (system camera in front, app in background: MIUI killed the process);
recents screenshot with the capture screen in front (`exec-out screencap -p`, blank card);
`adb reboot` then `open,verify_all,list,leak_scan,memory` (40 of 40, 152 s; the scan
reported the empty `Android/media/<pkg>` directory it had created, D-015); fix, rebuild
(`assembleDebug assembleDebugAndroidTest assembleBenchmark lintDebug`, lint clean), T2
rerun on the emulator; benchmark APK installed with a developer tap; a second fix (the
first still called `getExternalMediaDirs()`, which recreated the directory during the
benchmark run), new test `scanCreatesNoExternalDirectories`, rebuild, T2 rerun on the
emulator (31 tests, 0 failures, 1 skipped, after an emulator restart cleared stale rows),
final benchmark APK installed with another tap and `open,leak_scan` confirmed no directory;
`open,security_level,encrypt_1mb,encrypt_100mb,verify_all,list` and
`open,capture_photo_n=10,memory,leak_scan` on the benchmark build. Results in
`docs/PHASE2_TEST_REPORT.md` (T3, memory and performance) and
`docs/VIDEO_CAPTURE_FEASIBILITY.md` Section 7.

Device changes made and undone: the empty `Android/data/<pkg>` and `Android/media/<pkg>`
directories created by the first leak scan were removed (`run-as rmdir` and the shell
user), and the stale MediaStore row was cleared with a `scan_file` call. No setting was
changed. The emulator was booted headless for the T2 rerun and stopped afterwards.
