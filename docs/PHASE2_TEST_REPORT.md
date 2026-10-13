# Phase 2 test report

Date: 2026-10-13. Format per `PHASE2_BRIEF.md` Section 16. Every result below comes from a
command that was run; nothing is inferred. "Not verified" means exactly that.

## Status

**Complete.** The core additions (T1), the Android photo capture path, failure handling,
leak scan, and video prototype pass on Windows and the emulator (T2), and the physical
device tier (T3) ran on the Xiaomi 2201116SG on 2026-10-13 with the developer providing
the on-screen taps: 10 scripted and 11 manual captures at 3264x2448, verify after
force-stop and after reboot, kill during encrypt, background at the shutter, recents
thumbnail, permission handling, both video probes, the Phase 1 tamper actions, and the
benchmark build. One defect was found by the phone run and fixed (the leak scan created
empty external directories, D-015). Two phone steps did not produce the behaviour the brief
lists and are reported as observed: the "camera busy" step ended with MIUI killing the
backgrounded app instead of a camera-in-use error, and low storage was not produced on
the phone (filling its storage is out of bounds). Remaining items are the documented
limits of a prototype.

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, JDK, NDK, libsodium | As in `docs/PHASE1_TEST_REPORT.md` (unchanged) |
| Gradle | 8.11.1 (was 8.10.2), SHA-256 pinned in the wrapper |
| AGP, Kotlin plugin | 8.9.3 (was 8.8.2), 2.1.10 (kotlin-stdlib resolved to 2.1.20 by CameraX) |
| compileSdk / targetSdk / minSdk | 36 / 35 / 28 (compileSdk was 35) |
| CameraX | 1.6.2 (core, camera2, lifecycle, view; video in the debug variant) |
| media3-muxer | 1.11.1 (debug variant); 1.9.0 arrives in the release variant through camera-video |
| androidx.activity, androidx.test:rules | 1.10.1, 1.6.1 |
| Android Studio | AI-242 (Ladybug Feature Drop): cannot open the project after D-012; not updated during Phase 2 |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), cameras: back id 10 LIMITED virtual scene 1280x960 orientation 90, front id 1 FULL emulated 1920x1440 orientation 270 |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14 (V816.0.9.0.TKCMIXM), arm64-v8a, Keystore `TRUSTED_ENVIRONMENT`, no screen lock; back camera 3264x2448 stills, hardware H.264 encoder `c2.qti.avc.encoder`; main logcat buffer 256 KiB (logs were streamed during every run) |

## Architecture changes

- **C ABI (additive):** error codes 21 to 23; `pv_item_header` with `encode`, `decode`,
  `check_len`; `pv_source_memory_new`/`free`; `pv_sink_item_new`/`finish`/`abort`. No
  existing function, code, or behaviour changed; the eight Phase 1 golden vectors are byte
  for byte unchanged and still decrypt on both platforms.
- **Item header:** 48 bytes at the start of an object's plaintext (magic `PVITEM01`,
  version, `header_len` 48 to 4096, media type, rotation, width, height, capture time,
  payload length, reserved). Encrypted and authenticated with the media. Unknown items
  (Phase 1 objects) are classified as `PV_ERR_NOT_ITEM`.
- **Capture pipeline:** `CaptureActivity` (`PreviewView`, `FLAG_SECURE`) takes a picture in
  memory; `CaptureSession` (process scoped, one encrypt thread, queue of 2) hands the
  `ImageProxy` plane buffer to JNI; the core reads the JPEG in place through the memory
  source behind the item header; the object is verified by decrypting into the item sink
  and a hash sink; the proxy is closed on every path.
- **Video prototype:** debug source set only. V3b (encoder surface as the `Preview`
  provider) with `FragmentedMp4Muxer` per 3 s segment, each segment an item object, plus an
  index object; V2 probe over a proxy file descriptor. See
  `docs/VIDEO_CAPTURE_FEASIBILITY.md`.
- **Build:** `benchmark` build type (non-debuggable, optimized native code, debug signing);
  lint with `RestrictedApi` as an error; debug-only native hooks behind
  `PV_ANDROID_DEBUG_HOOKS`.

## Build and tests

Windows (repository root, `VCPKG_ROOT` and `VCPKG_DEFAULT_BINARY_CACHE` set):

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --items   # run once, item vectors
```

Android (`android/`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME`, `VCPKG_ROOT` set):

```
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest
gradlew.bat assembleBenchmark
```

### T1, host automated (Windows, CTest)

Debug: **14 passed, 0 failed, 0 skipped** (28 s). Release: **14 passed** (11 s). No warnings
at `/W4`. The 11 Phase 1 tests are unchanged except `test_golden` (reads the extended
manifest) and `test_cli` (checks the new `item:` line).

| Test | Covers | Result |
|---|---|---|
| test_memory_source (new) | single and multiple parts, empty parts and NULL pointers for them, empty source, reads stop at part boundaries (short reads), zero-capacity read, argument checks, use as encrypt input with the Phase 1 reader looping over short reads | Pass |
| test_item_header (new) | byte layout, round trip, every rotation and media type, extra bytes after the header, `header_len` 64 and 4096 accepted when present, rejected when the buffer is shorter; rejection of magic, version, media type, rotation, `header_len` below 48 and above 4096; check order; zeroed output on failure; short buffers; deterministic data is `NOT_ITEM`; encode rejects what decode rejects plus `header_len` other than 48; `check_len` including overflow; error names | Pass |
| test_item_objects (new) | items of 7 sizes through the memory source and the item sink, normal and 1 to 7 byte reads; the same through a vault directory with the BLAKE2b payload sink and wrong key abort; no magic (6 sizes, both read modes) and magic cut short of 12 bytes: nothing forwarded, payload aborted once, `NOT_ITEM`; `header_len` 4097, 65535, 47, 0, 12, and 2000 beyond the plaintext: aborted, `ITEM_MALFORMED`; `header_len` 64 present: skipped and accepted; rotation, `payload_len`, media type judged in finish; tamper (first, final, wrapped DEK), truncation (boundary, inside chunk, inside final, header), trailing byte, wrong key: fail inside the decrypt before any parse, payload aborted exactly once; double abort, null abort callback, argument checks | Pass |
| test_golden | 8 Phase 1 vectors (unchanged bytes) and 4 item vectors; item fields equal the manifest; payload hash equals the deterministic stream | Pass |
| test_cli | as Phase 1 plus `verify` prints `item: unknown` for a plain object | Pass |
| other 9 Phase 1 tests | unchanged | Pass |

### T2, Android automated (emulator)

`connectedDebugAndroidTest` on `Pixel_8_API_35`: **30 tests, 0 failures, 0 errors,
1 skipped** (the skipped one is `LockBoundKeyTest`, which needs a PIN; it passed in the
separate PIN run below). The 17 Phase 1 tests are unchanged except `GoldenVectorTest`.
Rerun after the D-015 change to the leak scan: **31 tests, 0 failures, 0 errors,
1 skipped** (the new `LeakScanTest.scanCreatesNoExternalDirectories` included). One
intermediate rerun failed `scanFindsNothingBeforeAndAfterCaptures` with two MediaStore
rows owned by the app; they were the stale rows of the `Android/data` and `Android/media`
directories that the pre-fix scan had created on the emulator (the uninstall after a test
run removes the directories but not the rows until the next media scan). After an emulator
restart the rows were gone and the suite passed.

| Class | Tests | Result |
|---|---|---|
| NativeInitTest, VaultTest, KeystoreTest | 1, 9, 5 (Phase 1) | Pass |
| GoldenVectorTest | 12 committed vectors (4 items: fields, payload hash, `header_len` 48; plain vectors also classified `NOT_ITEM` by the item path); export of 6 vectors (3 items) | Pass (2) |
| CaptureTest | 3 captures through `CaptureSession` into 3 verified items: `media_type` 1, rotation in {0,90,180,270}, `payload_len` equals the JPEG length, dimensions equal the proxy, pre-encrypt direct-buffer hash equals the decrypt-side payload hash, decrypted bytes start with `FF D8`, `BitmapFactory.decodeByteArray` gives the recorded size, no `.partial`; plane buffer directness logged; heap-array and direct paths give the same payload hash; Phase 1 object is `NOT_ITEM` | Pass (3) |
| CaptureFailureTest | encrypt failure by test hook: `PV_ERR_IO`, nothing left, next capture succeeds; queue bounded at 2; unbind during capture: `ERROR_CAMERA_CLOSED`, nothing written; stale `.partial` removed on open; low-space threshold check; storage full with ballast (177 files, 1 168 KiB free): encrypt fails `PV_ERR_IO` errno 28, no `.partial`, ballast removed; permission state readable | Pass (7) |
| LeakScanTest | 0 findings before and after 2 captures, the only new files are two `.pvo` objects; planted JPEG and `ftyp` markers are found and gone after removal; the scan creates none of the external app directories (D-015) | Pass (3) |
| LockBoundKeyTest | skipped without a PIN; see the PIN run | Skipped / Pass |

Logged on the emulator: `plane0 isDirect=true hasArray=false remaining=570914 1280x960
rotation=90 class=java.nio.DirectByteBuffer`; `buffer paths: [DIRECT, DIRECT, DIRECT]`;
`encrypt on full storage: PV_ERR_IO (3) os_error=28`; `unbind during capture:
ERROR_CAMERA_CLOSED`.

### T2, scripted through the real activities (emulator, debug build)

| Step | Result |
|---|---|
| `open,capture_photo_n=3,verify_all,list,leak_scan,memory` | 3 captures ok (570 to 572 KB JPEG, 1280x960, rotation 90, direct buffer, encrypt 11 to 35 ms, capture-to-object 1.5 to 1.6 s); `verify_all` 4 of 4; `leak_scan` 0 findings, MediaStore 0 rows; PSS 62 MB |
| `open,flip,capture_photo,memory,list` (front camera) | capture resolution 1920x1440, rotation 270, 55 678-byte JPEG, direct buffer, encrypt 105 ms, native heap peak 18.5 MB |
| Kill during encrypt (`test_hook_slow=1500`, `am force-stop` at 9 s) | `.partial` of 196 787 bytes present during and after the kill; next open removes it; `verify_all` 15 of 15 |
| Recents thumbnail with the capture screen in front | `screencap` of the capture screen is black (secure window) and the recents card is black |
| Permission revoked (`pm revoke` plus `user-fixed`) | `camera: PERMISSION_DENIED`, `capture refused: PERMISSION_DENIED`, no crash; after `pm grant` a capture succeeds |
| `/proc/<pid>/fd` during a capture | 164 descriptors; no open file outside the app's own code and the vault directory (listing kept in `build/emu/A-fd.txt`, not committed) |
| `dumpsys media.camera` | `CONNECT` and `DISCONNECT device 10 client for package com.privatevault.app` |
| `open,video_probe,list,leak_scan` | see the feasibility report; `leak_scan` 0 findings, no new non-object files |
| `open,v2_probe,leak_scan` | see the feasibility report; `leak_scan` 0 findings, no new files |

### T2, lock-bound key with a temporary PIN (brief 9.1, emulator)

`locksettings set-pin`; `LockBoundKeyTest` passes with `key mode lock-bound`; fresh app
vault created lock-bound; 1 MB encrypt and verify ok while unlocked; screen locked
(`isKeyguardShowing=true`); launch with `open,verify_all`: `[open] FAIL:
java.security.InvalidKeyException: Keystore operation failed <-
android.security.KeyStoreException: Device locked`; after unlock the same actions succeed;
`locksettings clear`. The emulator Keystore is software-backed (`SOFTWARE`), so this
exercises the code path, not hardware binding. **Pass (code path).**

### Android to Windows

Six vectors made on the emulator (`android_empty`, `android_three_chunks`,
`android_default_chunk`, `android_item_jpeg`, `android_item_empty`, `android_item_segment`)
pulled with `adb exec-out run-as ... cat` and decrypted by
`test_golden.exe D:\repo\PrivateVault\build\android-export`: all six ok, the three items
parsed with their fields. **Verified.**

### T3, device (Xiaomi 2201116SG, 2026-10-13)

Debug APK installed by the developer (MIUI on-screen tap), camera permission granted on
screen at the first launch of the capture screen. Runs were driven over adb through the
`actions` extra with logcat streamed to a file, because the main log buffer of this phone
(256 KiB) loses the app lines within minutes. Logs are under `build/phone/`, not committed.

| Step | Result on the phone |
|---|---|
| 10 scripted captures (`open,capture_photo_n=10,verify_all,list,leak_scan,memory`) | 10 objects; capture resolution 3264x2448; every plane buffer direct (`buffer=DIRECT`); JPEG 2.67 to 2.68 MB; encrypt 269 to 301 ms (debug build); capture-to-object 1.49 to 2.20 s; `verify_all` 22 of 22 including the two Phase 1 objects (1 MB and 100 MB, reported as unknown items with matching hashes); `leak_scan` 0 findings |
| 11 manual captures by tap (5 portrait, 5 landscape, 1 with Home pressed at the shutter) | All 11 verified. Portrait shots are delivered as 2448x3264 and landscape shots as 3264x2448, both with `rotation_degrees` 0: on this phone CameraX delivers the JPEG already rotated for the target rotation, so the orientation shows in the swapped width and height rather than in the rotation field. Encrypt 139 to 246 ms |
| Background at the shutter | The capture whose shutter was followed by Home completed and verified (object `e57b...`, 2.96 MB, `verify=ok`); the capture screen rebound the camera on return |
| Force-stop, relaunch | `opened existing vault`, `verify_all` 22 of 22 (32 s) |
| Reboot, relaunch | `opened existing vault`, `verify_all` 40 of 40 (152 s, cold caches, two 100 MB objects); `leak_scan` reported 1 MediaStore files row, which was the empty `Android/media/<pkg>` directory the scan itself had created (D-015); fixed, directory removed, row gone after a rescan |
| Kill during encrypt (`test_hook_slow=1500`, `am force-stop` at 9 s) | `.partial` of 196 787 bytes present during and after the kill; next open removed it; `list` shows no partial, 22 objects |
| Camera busy (system camera opened while our capture screen was in the background) | MIUI killed the app process while it was in the background with the system camera in front (the next return showed a fresh main screen; the log shows a new process start); no camera-in-use error reached a live activity. The camera service log shows the reverse case: when our capture screen came to the front, the service evicted the system camera (`EVICT device 0 client held by package com.android.camera`) and our bind succeeded. The "error name shown, button disabled" path of the brief was therefore not reached on this phone and stays verified by the emulator unbind test only (`ERROR_CAMERA_CLOSED`) |
| Recents thumbnail (`FLAG_SECURE`) | With the capture screen in front, the PrivateVault card in the MIUI app switcher is blank white (`build/phone/F-recents2.png`); a `screencap` of the capture screen itself returns 0 bytes |
| Permission | Granted on screen at the first capture-screen launch; the denied path was verified on the emulator only |
| Tamper, truncation, wrong key, security level | PASS (`PV_ERR_AUTH_FAILED`), PASS (`PV_ERR_TRUNCATED`), PASS (`PV_ERR_WRONG_VMK`); `TRUSTED_ENVIRONMENT`, not lock-bound; `link()` denied errno 13 |
| `/proc/<pid>/fd` during a capture | 184 descriptors; apart from system, framework, and app code files only `/proc/<pid>/timerslack_ns`; no file outside the vault directory open (`build/phone/A-fd.txt`) |
| `dumpsys media.camera` during a capture | device 0 open by `com.privatevault.app`, consumer `SurfaceView[CaptureActivity]`; CONNECT and DISCONNECT lines for this package |
| `video_probe`, `v2_probe` | Ran; figures in `docs/VIDEO_CAPTURE_FEASIBILITY.md` Section 7 |
| Benchmark build (installed over the debug app, developer tap) | see Memory and performance; `run-as` refused (`package not debuggable`); `leak_scan`: 0 findings, 55 files. That build still carried the first version of the D-015 fix, which used `getExternalMediaDirs()` to find volumes and so recreated the empty `Android/media/<pkg>` directory; the directory was removed again, the second fix (volumes from `StorageManager`) was built, installed with a further tap, and its `leak_scan` left no directory behind (checked with `ls` over adb) |
| Low storage | Not produced on the phone (filling its storage is out of bounds); emulator only |
| Test objects | 52 objects (about 190 MB) remain in the phone vault; the app has no delete-vault action, so nothing was deleted |

### APK checks (debug and benchmark APKs, `aapt2`)

- Permissions: `android.permission.CAMERA` and the app-private
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` only. `ACCESS_NETWORK_STATE` (from media3) is
  removed by a merge rule (D-013).
- `uses-feature android.hardware.camera.any required=false`.
- Components: `MainActivity` (exported, launcher), `CaptureActivity` (not exported),
  `MetadataHolderService` (CameraX, disabled, not exported). The profile installer provider
  and its exported receiver are removed (D-013). No `FileProvider`.
- `allowBackup=false`, both backup rule references present, `compileSdkVersion 36`,
  `targetSdkVersion 35`, `minSdkVersion 28`; benchmark APK is not `debuggable`.
- Native libraries: `libprivatevault_jni.so` plus CameraX's `libimage_processing_util_jni.so`
  and `libsurface_util_jni.so` for `arm64-v8a` and `x86_64`.
- Source grep (`src/main`, `src/debug`): no `OutputFileOptions`, `OnImageSavedCallback`,
  `FileOutputStream`, or `openFileOutput`; `MediaStore` appears only in the read-only query
  of the leak scan.
- Lint (`lintDebug`, `RestrictedApi` as error): 0 errors, 11 warnings (4 newer dependency
  versions available, `OldTargetApi`, `MissingApplicationIcon`, 2 `UseKtx`, 3 `SetTextI18n`).

## Plaintext exposure

| Check (brief Section 7) | Emulator | Xiaomi |
|---|---|---|
| 1 Directory walk before and after captures: only new files are `objects/*.pvo` | Pass (instrumented and scripted) | Pass (after 10 scripted captures and after the benchmark captures: new files only `objects/*.pvo`) |
| 2 Content scan for `FF D8 FF` and `ftyp` in the first 64 KiB of every file, after photos and after both video probes | Pass, 0 findings | Pass, 0 findings after photos, after `video_probe`, after `v2_probe`, and after the benchmark run (55 files) |
| 3 MediaStore images, video, files rows owned by the app | Pass, 0 rows | Pass after the fix: 0 rows. Before the fix the reboot scan showed 1 files row, the empty directory the scan had created (D-015), no media |
| 4 Logcat: hex runs longer than 32 and base64 runs longer than 44 characters | Pass: 0 hex runs; 6 regex hits are file paths; no hash is logged by the app in any variant | Pass: 749 lines, 0 hex runs, 5 regex hits are overlay file paths in the descriptor listing, 54 object IDs |
| 5 Recents thumbnail with `FLAG_SECURE` | Pass: black card and black screen capture | Pass: blank white card in the MIUI app switcher; `screencap` of the capture screen returns nothing |
| 6 Merged manifest | Pass (see APK checks) | same APK |
| 7 `/proc/<pid>/fd` during capture, `dumpsys media.camera` | Recorded (see scripted runs) | Recorded (see T3) |

Not verifiable without root, on any device: camera HAL and vendor dumps under
`/data/vendor`, MIUI system services, kernel and GPU buffers, swap and zram, the
`ImageReader` buffer after close, CameraX internal copies.

## Memory and performance

Xiaomi 2201116SG, back camera, 3264x2448 (the highest size the camera offers), JPEG 2.56
to 3.72 MB:

| Measure | Debug build | Benchmark build |
|---|---|---|
| Encrypt time per capture | 139 to 301 ms | 45 to 59 ms |
| Capture-to-object | 1.10 to 2.20 s (first capture after bind up to 2.2 s) | 727 to 781 ms (first 1.61 s) |
| Native heap peak during a capture | 24.2 to 44.9 MB | 24.4 to 52.3 MB, rising with every capture until a collection ran (the debug run fell back to 24 MB after four captures; the benchmark run did not collect within ten) |
| Java heap peak | 7.6 to 20.4 MB | 9.9 to 36.3 MB, same pattern |
| PSS | 102.6 MB after `verify_all` with the capture screen bound; 158.7 MB after the video probe | 123.3 MB after ten captures |

Phone benchmark build against the Phase 1 phone debug figures:

| Operation | Phase 1 debug (phone) | Phase 2 benchmark (phone) |
|---|---|---|
| Encrypt 1 MB | 156 ms | 43 ms |
| Encrypt 100 MB | 19 976 ms | 1 307 ms |
| Verify | 31 990 ms for 1 MB plus 100 MB | 4 246 ms for 42 objects (two 1 MB, two 100 MB, 38 photos of about 2.7 MB) |

Emulator, back camera, about 570 KB JPEG at 1280x960 (the highest size that camera offers):

| Measure | Debug build | Benchmark build |
|---|---|---|
| Encrypt time per capture | 8 to 80 ms | 29 to 80 ms |
| Capture-to-object | 1 483 to 1 660 ms (the emulator still capture takes about 1.5 s) | 1 483 to 1 759 ms |
| Native heap peak during a capture | 10.7 to 18.6 MB (higher in the real activity than in the test harness, which has no preview) | 17.6 to 18.3 MB |
| Java heap peak | 5 to 10.4 MB | 4.6 to 10.5 MB |
| PSS after the run | 62 to 66 MB (with the capture screen) | 33.6 MB (after returning) |
| Front camera, 1920x1440 | 55 678-byte JPEG, encrypt 105 ms, native heap peak 18.5 MB | not run |

Benchmark build against the Phase 1 debug figures, emulator:

| Operation | Phase 1 debug (emulator) | Phase 2 benchmark (emulator) |
|---|---|---|
| Encrypt 1 MB | 108 ms | 33 ms |
| Encrypt 100 MB | 3 584 ms | 816 ms |
| Verify both | 6.1 s | 1 258 ms |

Chunk size stayed at 64 KiB (design Section 14); the phone encrypt times above (45 to
59 ms for 2.6 MB in the benchmark build, about 45 MB/s) leave no reason to change it.

## Video feasibility summary

A custom `VideoOutput` cannot drive `VideoCapture` with public API only (it fails at bind
because the restricted `getMediaSpec()` must return a value), so the prototype hands the
encoder surface to the `Preview` use case instead (no on-screen viewfinder while
recording). That path records 10 s at 1280x960 and 3.3 Mbit/s into four fragmented MP4
segments, each encrypted as one item object in 41 to 83 ms, every segment parsing and
decoding its first frame after decryption, with no file outside the vault. The `Recorder`
over a proxy descriptor records but back-patches 70 of 421 writes, so it needs a
random-write store and is not a path. Recommendation: full video as its own phase (or the
second half of Phase 3) after the viewer, with a viewfinder mirror, native segment
buffering, index and recovery rules, and audio as the four milestones. Details and
measurements: `docs/VIDEO_CAPTURE_FEASIBILITY.md`.

## Defects found and fixed

- The leak scan created the empty external directories it then scanned, which the phone
  indexed after a reboot as a MediaStore row owned by the app (D-015). Fixed: paths are
  built instead of requested from the `Context` getters, and every owned row is named.
- Lint `RestrictedApi` caught `SurfaceRequest.getExpectedFrameRate` in the prototype
  (removed); the developer's gate worked as intended.
- The merged manifest carried `ACCESS_NETWORK_STATE` and an exported profile-installer
  receiver from dependencies; removed with merge rules (D-013).
- `tools:node="remove"` entries made lint report `MissingClass`; suppressed on those two
  elements only.
- Scripted capture actions had to be routed through `MainActivity` because `am start`
  cannot reach a non-exported activity; the handoff list is in `MainActivity`.
- The connected test run uninstalls the app; the scripted steps reinstall the APK first.

## Known limits and unresolved risks

- MIUI kills the app while it is in the background with another camera app in front; the
  camera-in-use error path was not reached on the phone, and a user returning to the app
  finds the main screen with the vault closed (the Phase 1 flow requires "Create or open
  vault" again). An in-app lock and a reopen-on-resume rule are Phase 3 items.
- On the phone the delivered JPEG is already rotated (width and height swap between
  portrait and landscape, `rotation_degrees` stays 0); on the emulator the rotation field
  carries 90 or 270. A viewer must apply both the field and the dimensions.
- Phase 3 item: during a burst of captures the Java heap rises by about one JPEG per
  capture (up to 36 MB after ten 2.6 MB captures in the benchmark build, native heap up to
  52 MB) although the photo path makes no Kotlin copy. Together with the phone delivering
  pre-rotated JPEGs with `rotation_degrees` 0, this suggests CameraX may be rotating the
  JPEG in the Java heap on this device. Not verified; a heap dump taken during a capture
  burst settles it. Not a leak by the measurements taken (the debug run fell back after
  four captures).
- Phase 3 item: the app has no delete-vault action, so the 52 test objects (about 190 MB)
  made during T3 remain in the phone vault until the app data is cleared or the action is
  added.
- Low storage on the phone was not produced (emulator only).
- Video: no viewfinder during recording; Java-side copies in the muxer and the segment
  buffer; low-space stop at segment boundaries implemented but not exercised; sync-frame
  counts from the encoder and the extractor differ (2/4/4/3 against 2/2/2/2) and were not
  investigated; `FragmentedMp4Muxer` is `@UnstableApi`.
- Phase 3 item: the release variant carries media3 1.9.0 classes through `camera-video`
  that it never calls, while the debug variant uses media3 1.11.1 for the prototype; the
  debug APK is 17.6 MB against 4.1 MB in Phase 1. The viewer phase decides the media3 line
  and whether `camera-video` stays out of the release variant.
- `LockBoundKeyTest` deletes the app's Keystore key, so the app vault made before it cannot
  be opened afterwards; the scripted PIN procedure removes the vault first. The test is
  skipped unless a PIN is set.
- Kotlin VMK copy, D-007, D-008: unchanged from Phase 1.
- No independent review.

## Phase 2 acceptance checklist (brief Section 13)

| # | Criterion | Result |
|---|---|---|
| 1 | Toolchain decision applied; Phase 1 T1 and T2 still pass | Pass (AGP 8.9.3, Gradle 8.11.1, compileSdk 36; T1 11/11 then 14/14, T2 17/17 then 31 with 1 skipped) |
| 2 | Photo stored as one v1 object with a valid item header; verify passes; decrypted bytes decode as a JPEG of the recorded size | Pass: emulator (`CaptureTest` decodes the JPEG in memory) and phone (31 captures, every header parsed with the recorded fields and every object verified; the in-memory decode is the instrumented test, which did not run on the phone by rule) |
| 3 | No app code path writes plaintext media; no file, MediaStore, or `OutputFileOptions` output in the source | Pass (grep, design) |
| 4 | `leak_scan` finds nothing on emulator and Xiaomi | Pass on both after D-015 (the only phone finding was the empty directory the scan had created, removed) |
| 5 | Merged manifest: `CAMERA` only, no `INTERNET`, storage, media, or location permissions; backup disabled | Pass |
| 6 | Every Section 6 failure leaves a complete object or nothing; no `.partial` survives an open | Pass for every case produced: encrypt failure, storage full (emulator), unbind, kill during encrypt (emulator and phone), permission denied (emulator), queue limit, low-space precheck, backgrounding at the shutter (phone). Camera busy on the phone ended in a MIUI process kill rather than a camera error; nothing was left on disk |
| 7 | `FLAG_SECURE`; recents thumbnail blank on the device | Pass on the emulator and on the Xiaomi (blank card in the app switcher) |
| 8 | C ABI additive; Phase 1 golden vectors decrypt on both platforms | Pass |
| 9 | New core code passes T1; Android-made item objects decrypt and parse on Windows | Pass |
| 10 | T3 passes on the Xiaomi, or the blocker and remaining steps are explicit | Pass (see T3); the two steps that behaved differently from the brief are reported as observed |
| 11 | Lock-bound key mode exercised on the emulator with a PIN | Pass |
| 12 | Benchmark-build performance measured on the Xiaomi and compared with Phase 1 debug figures | Pass: 1 MB 43 ms (was 156), 100 MB 1 307 ms (was 19 976), verify 4.2 s for 42 objects (was 32 s for two); captures encrypt in 45 to 59 ms |
| 13 | `docs/VIDEO_CAPTURE_FEASIBILITY.md` complete with V3 and V2 evidence, recommended architecture, placement | Pass (emulator and phone evidence) |
| 14 | Threat model updated | Pass (`docs/THREAT_MODEL.md` Section 6) |
| 15 | Report separates demonstrated facts from assumptions | Pass (this document) |

## Files changed

- Root: `README.md`, `CLAUDE.md`.
- `src/vault_core/`: `include/pv/pv.h`, `src/item.cpp` (new), `src/streams.cpp`,
  `src/pv_init.cpp`, `CMakeLists.txt`.
- `src/desktop_cli/main.cpp` (verify prints item fields).
- `tests/core/`: `test_memory_source.cpp`, `test_item_header.cpp`, `test_item_objects.cpp`
  (new), `test_golden.cpp`, `test_cli.cpp`, `CMakeLists.txt`; `tests/tools/make_vectors.cpp`;
  `tests/fixtures/v1/`: four `item_*.pvo` (new), `manifest.txt`, `README.md`.
- `android/`: `settings.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`,
  `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/cpp/{CMakeLists.txt,jni_bridge.cpp}`,
  `app/src/main/java/com/privatevault/app/{PvApp,CaptureSession,CaptureActivity,LeakScan,ObjectActions,VideoProbes}.kt`
  (new), `{NativeBridge,Vault,MainActivity}.kt`, `app/src/debug/java/com/privatevault/app/DebugVideoProbes.kt`
  (new), `app/src/androidTest/.../{TestSupport,CaptureTest,CaptureFailureTest,LeakScanTest,LockBoundKeyTest}.kt`
  (new), `GoldenVectorTest.kt`.
- `docs/`: `PHASE2_DESIGN.md`, `PHASE2_PROGRESS.md`, `VIDEO_CAPTURE_FEASIBILITY.md`, this
  report (new); `DECISIONS.md` (D-012 to D-015), `DEPENDENCIES.md`, `THREAT_MODEL.md`,
  `ROADMAP.md`, `PHASE1_TEST_REPORT.md` (Section 1 fixes).

## Suggested commit messages

For the T3 session only (the leak scan fix, D-015, the phone results, and the document
fixes requested at the start of the session), on top of the Phase 2 commit below:

```
Phase 2 T3 on the Xiaomi: leak scan fix, phone results, report

LeakScan no longer calls the Context getters that create the external
directories it scans; the first scan on the phone had created an empty
Android/media directory that the media indexer reported after a reboot
(D-015). Owned MediaStore rows are now named in the scan output.

Phone run: 10 scripted and 11 manual captures at 3264x2448 with direct
plane buffers, verify after force-stop and reboot, kill during encrypt,
background at the shutter, blank recents card, tamper actions, both video
probes (hardware encoder, 4.8 Mbit/s), benchmark build figures (1 MB
43 ms, 100 MB 1.3 s, 2.6 MB captures encrypted in 45 to 59 ms). Camera
busy on MIUI ends in a process kill, reported as observed.

Docs: test report (T3, exposure, performance, checklist, Phase 3 items
on heap growth and the missing delete-vault action), feasibility report
Section 7 with phone figures and the sync-frame risk, design Sections 3,
5.1, 2.3, 8.4, 14, progress log, D-015.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

For the whole phase, if committed as one:

```
Phase 2: in-app photo capture into the vault, item header, video feasibility

Core: additive C ABI for a 48-byte media item header inside the object
plaintext (encode, decode, length check), a memory source over caller
parts, and an item sink that splits header and payload after FINAL.
Error codes 21 to 23. pv verify prints item fields. Four item golden
vectors; the Phase 1 vectors are unchanged.

Android: CameraX 1.6.2 capture screen with FLAG_SECURE; JPEG bytes read
in place from the direct plane buffer by JNI and encrypted as one item
object; process-scoped capture session with a bounded queue, free-space
precheck, and verify after encrypt; leak scan of app-visible storage and
MediaStore; manifest merge rules for dependency-added entries; benchmark
build type; lint with RestrictedApi as an error; debug-only video
prototype (encoder surface through Preview, fragmented MP4 segments as
item objects) and the Recorder proxy-descriptor probe.

Toolchain: AGP 8.9.3, Gradle 8.11.1, compileSdk 36 (D-012).

Tests: T1 14/14 Debug and Release; T2 30 tests on the emulator plus the
scripted activity runs, lock-bound PIN procedure, benchmark figures, and
Android-to-Windows item vectors; T3 on the Xiaomi 2201116SG.
Docs: Phase 2 design, progress, feasibility report, test report; threat
model, roadmap, dependencies, decisions D-012 to D-015, README, CLAUDE.md;
Phase 1 report fixes.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
