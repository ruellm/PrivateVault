# Phase 3B test report

Date: 2026-10-14. Format per `PHASE3B_BRIEF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Not run" means exactly that.

## Status

**Complete except the heap investigation.** The core additions (T1) and the Android gallery,
viewer, delete, and dependency cleanup (T2, instrumented and host-driven) pass on Windows
and the emulator, and the device tier (T3) ran on the Xiaomi 2201116SG on 2026-10-14 with
the developer providing the install taps and the by-hand actions: every photo of the
55-object vault upright under the one rule, the lock cases, blank recents cards, captures
without `camera-video` in both builds, leak scans at zero, and the benchmark unlock time at
the final KDF default. The heap investigation (P3-12) was skipped at the developer's request
for now; the procedure is built and dry-run on the emulator (Section "Heap investigation").
The phone keeps its vault (58 objects after the three new captures) and the debug build 0.4.0.

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, JDK, NDK, libsodium, Gradle, AGP, Kotlin, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2 | As in `docs/PHASE3A_TEST_REPORT.md` (unchanged) |
| Dependencies | `camera-video` removed from every variant; media3 1.11.1 in debug only (Section "Dependency route") |
| App version | 0.4.0 (`versionCode` 4) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), Keystore `SOFTWARE`, no screen lock |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, Keystore `TRUSTED_ENVIRONMENT`, no screen lock; `RLIMIT_MEMLOCK` 64 MiB for the app process |
| Android Studio | AI-242 (Ladybug) during this session; the developer is updating it for the heap investigation |

## Architecture changes

- **Core (additive, Android-free):** error codes 25 `PV_ERR_BUFFER_TOO_SMALL` and 26
  `PV_ERR_CANCELLED`; `pv_object_plaintext_size` and `pv_object_plaintext_size_file` (exact
  plaintext length from the header and the file length, no decrypt);
  `pv_vault_decrypt_to_buffer` (length check first, then the Phase 1 decrypt into the
  caller's buffer with a cancel flag polled per chunk; every failure after the stream
  started wipes the whole buffer); `pv_secure_alloc` and `pv_secure_free` (`sodium_malloc`);
  `pv_vault_delete_object` (partial first, then the object, then a directory sync). New
  `src/viewer.cpp`; `fs` gained `file_size` and `sync_dir`. `pv inspect` prints
  `plaintext <n>`. No existing function, code, or vector changed.
- **Android:** `GalleryActivity` (3-column `GridView`, one tile per object newest first,
  lazy thumbnails, labelled tiles for large, unknown, damaged, out-of-memory, cancelled),
  `ViewerActivity` (`PhotoView` with fit, 2x zoom, pan, quarter turn by matrix; Delete behind
  an in-activity confirmation), `PhotoLoader` (the pipeline: length, 32 MiB cap, guarded
  buffer, item header, EXIF tag through the platform `ExifInterface`, `ImageDecoder` at the
  target size with `ALLOCATOR_SOFTWARE` and a mutable bitmap, free; the orientation rule;
  erase helper), `ThumbCache` (32 entries, erase on eviction and clear), `Vault.close()` sets
  the viewer cancel flag before it waits and clears it after, `Vault.viewerLoad`,
  `deleteObject`, `listObjects`, `plaintextSize`; JNI entry points of the design Section 8;
  scripted actions `view_all`, `gallery`, `delete_object=<hex>`, `gc`, `wait=<ms>`,
  `alloc_probe`, `orient_probe`; both new activities not exported, `FLAG_SECURE`, routed to
  the lock screen when not unlocked. `camera-video` excluded; the Phase 2 V2 probe and the
  V3a attempt deleted.
- **Design and decisions:** `docs/PHASE3B_DESIGN.md` (approved with four changes, Section 18
  lists the implementation deltas), D-022 to D-027, `docs/THREAT_MODEL.md` Section 8.

## Dependency route and APK sizes (brief 4.6, P3-10)

Route: an exclusion of `camera-video` on `camera-view` (D-022); the explicit debug
`camera-video` dependency removed; `media3-muxer` 1.11.1 stays `debugImplementation`.
`PreviewView` works without `camera-video`: captures through `CaptureActivity` on the
emulator in the debug build (8 in Milestone A, 3 plus 10 in Milestone D) and in the
benchmark build (1, by taps). The own `SurfaceView` fallback (brief 4.6.4) was not needed.

| APK | Classes `androidx.media3` | Classes `androidx.camera.video` | Size before (3A) | Size after |
|---|---|---|---|---|
| debug | 626 (muxer 1.11.1 and its two libraries) | 445 then 0 | 19 036 751 | 17 608 236 |
| benchmark | 557 then 0 | 443 then 0 | 13 822 758 | 10 578 545 |
| release (unsigned) | 0 | 0 | not built in 3A | 10 570 353 |

Class counts come from the dex `class_defs` of each APK (`build/p3b/dexclasses.py`). Guava
left the benchmark and release variants with media3; `listenablefuture` resolves to its real
1.0 artifact there and the class is present. Merged manifests of the three variants:
`CAMERA` plus the app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; exported
activities: `LockActivity` (all), `ScriptActivity` (debug only); `MainActivity`,
`GalleryActivity`, `ViewerActivity`, `CaptureActivity` not exported; `MetadataHolderService`
not exported. R8 stays off (CP-8); a hardening item in `docs/ROADMAP.md`.

## Build and tests

Windows (repository root, `VCPKG_ROOT` and `VCPKG_DEFAULT_BINARY_CACHE` set):

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release
```

Android (`android/`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME`, `VCPKG_ROOT` set, emulator booted headless):

```
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest
python -I tests/tools/make_exif_fixtures.py tests/fixtures/exif     # run once, fixtures committed
```

Host-driven steps: `build/p3b/host-d.sh`, `host-ui.sh`, `emu-bench-capture.sh`, `emu-probe.sh`
(not committed; the commands are also in `docs/PHASE3B_PROGRESS.md`).

### T1, host automated (Windows, CTest)

Before any change: 16/16 Debug and Release. After Milestone C: Debug **17 passed, 0 failed**
(37 s), Release **17 passed** (28 s). No warnings at `/W4`.

| Test | Covers | Result |
|---|---|---|
| test_viewer_buffer (new) | plaintext length exact for 0, 1, c - 1, c, c + 1, 2c, 2c + 1, 3c + 123 at chunk sizes 4096 and 65536 and for a 100 MB object, each equal to the decrypt length and to the path form; round trips into exact and larger buffers (bytes past the plaintext untouched); `out_len` NULL; too-small buffer refused with the buffer untouched; tamper in the first, middle, and final chunk, truncation inside the final chunk, a trailing byte, wrong key: each fails with its code and leaves a zero buffer; boundary cut, bytes forming no chunk, file too short, renamed object, missing object: refused before any byte is written; cancel flag set: `CANCELLED` and a zero buffer, cleared: round trip; `pv_secure_alloc` 0 and NULL refused, 1 byte and 32 MiB accepted, free of NULL ignored; delete with a stale partial (both gone), twice (`NOT_FOUND`), a lone partial (removed, still `NOT_FOUND`), other objects intact; argument checks; error names | Pass |
| test_golden | 8 Phase 1 and 4 Phase 2 vectors unchanged; each also reports its exact length without decrypting and decrypts into a buffer of that length with the manifest hash; envelope vectors as 3A | Pass |
| test_cli | as 3A plus `inspect` prints `plaintext 131079` | Pass |
| other 14 tests | unchanged | Pass |

### T2, Android automated (emulator)

Final run: **63 tests, 0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`, no emulator
PIN), 209 s. The 53 Phase 1 to 3A tests are unchanged and pass. Earlier run: 63 tests with
one failure in the new leak test (capture slot not reservable after the lock tests;
Section "Defects").

| Class | Tests | Result |
|---|---|---|
| ViewerLoaderTest (new) | the 16 combinations of EXIF 1, 3, 6, 8 with header rotation 0, 90, 180, 270 on the committed fixtures: the loader reports the tag and the rotation, the turn is the header rotation only when the tag is 1, and the red square lands at the corner one turn gives (never two); decoded size bounded by a 300x300 target (300x225 from 1600x1200), 256 px thumbnail, never upscaled (1600x1200 at a 5000 target), swapped bound for a turned viewer; `large` for a 33 MiB object without a buffer; `unknown` for a plain object, an empty object, a video segment; `damaged` with `PV_ERR_AUTH_FAILED` for a tampered object, a `PV_ERR_` name for a cut one, `DecodeException` for a non-JPEG payload; allocation hook gives `NoMemory`, decode hook gives `NoMemory` with the buffer freed, then a load works; delete removes the object and its partial, `NOT_FOUND` after, `damaged NOT_FOUND` in the loader; lock during a slowed decrypt: `Cancelled`, close in 1 204 to 1 212 ms, loads on the closed vault cancelled | Pass (6) |
| ViewerScreenTest (new) | `FLAG_SECURE` on the gallery and viewer windows; two thumbnails erased when the gallery stops (cache empty, erase counter + 2); the viewer bitmap erased when the viewer stops; a lock finishes the viewer and erases the bitmap | Pass (3) |
| ViewerLeakTest (new) | two real captures, scan, `view_all` over every object (2 photos, 1 unknown), scan: 0 findings, no new files | Pass (1) |
| Phase 1 to 3A classes | 53 | Pass (52, 1 skipped) |

Logcat of the run (454 lines): 0 occurrences of the test PINs, 0 hex runs over 32
characters; the debug disagreement line fired for the fixtures whose tag and header differ
and carried the object ID, the tag, and the header rotation only.

### T2, host-driven (emulator, debug build unless noted)

| Step | Result |
|---|---|
| Merged manifests, three variants | as in "Dependency route" |
| Setup, 3 captures, `encrypt_1mb`, `view_all`, `leak_scan`, `gallery` | `view_all: 3 photos, 1 other objects, buffers freed 6, bitmaps erased 6`; photos at screen size 960x1280 and 1080x1440 with `turn=0` (EXIF 6 and 8, header 90 and 270), thumbnails 192x256, 24 to 69 ms per load; `leak_scan: 0 findings`; `gallery: 4 objects listed` (three `photo`, one `unknown item`) |
| Screen captures | gallery, viewer, and the recents card with the viewer in front: black apart from the status bar (`build/p3b/U2-viewer.png`, `U3-recents.png`) |
| Viewer by tap | a photo tile opens the viewer (`1080x1440 decoded, turn 0`); Delete shows the in-activity confirmation; the second Delete removes the object (`PV_OK`); the gallery returns with 8 objects |
| `video_probe` and `leak_scan` after it | V3b records 4 segments, every segment parsed and its first frame decoded, index written; `leak_scan: 0 findings`, 0 new non-object files |
| Benchmark build (no `ScriptActivity`), keypad and button taps | `lock: unlocked in 930 to 1106 ms` (ops 2 / 256 MiB on the emulator); Gallery, viewer `960x1280 decoded, turn 0`, Delete `PV_OK`; capture screen `camera open, facing=back, capture resolution=1280x960`, one capture `verify=ok` (570 464-byte JPEG, direct buffer, encrypt 136 ms); gallery, viewer, and capture screen captures black |
| Capture burst with the heap actions (debug) | `java_heap_peak` per capture 5.0, 6.1, 7.1, 8.0, 8.9 MB then back to 5.9 MB and rising again (about 1 MB per 571 KB capture until a collection), `gc: java_used 5604KiB -> 3786KiB`, native heap steady at 18.3 to 19.1 MB; `am dumpheap` before and after the collection produced two 44 MB dumps, pulled to `D:\pv-heap` and deleted unread (nothing on this machine opens them yet, CP-9) |
| Milestone A probes (trial build) | `alloc_probe`: `sodium_malloc` 5 to 64 MiB succeeds, `mlock` refused (`ENOMEM`, `RLIMIT_MEMLOCK` 65 536); `orient_probe` over 8 captures: EXIF tag equals the header rotation in every case, `ImageDecoder` applies it, `BitmapFactory` does not (design Section 5.1) |

### T3, device (Xiaomi 2201116SG, 2026-10-14, second session)

Debug build 0.4.0 installed by the developer (MIUI tap), screen kept on, runs driven over adb
through `ScriptActivity` with logcat streamed to files (`build/p3b/phone-p3b.sh`, outputs under
`build/p3b/phone/`, not committed). MIUI refuses shell input injection, so the viewer, lock
cases, recents, and the benchmark run were done by the developer by hand while the log
streamed. The throwaway PIN is not recorded. The heap step was skipped at the developer's
request (Section "Heap investigation").

| Step | Result on the phone |
|---|---|
| `alloc_probe` | `sodium_malloc` 5, 8, 16, 32, 64 MiB succeed (8 to 56 ms); `mlock` succeeds at every size: the app process has `RLIMIT_MEMLOCK` 67 108 864 (64 MiB), so the viewer buffer is locked on this phone. The 64 KiB read in Milestone A was the adb shell user's limit |
| `orient_probe` over the 55-object vault | 46 JPEG objects probed, 9 skipped: every object has header rotation 0 with the pixels already turned (31 portrait 2448x3264, 15 landscape 3264x2448), an Orientation tag with value 0 (undefined), `BitmapFactory` and `ImageDecoder` report the stored size, decode at sample 4 to 612x816 or 816x612; decrypt of a 4.4 MB object into guarded memory 177 to 182 ms (debug build). The rule applies its fallback (tag absent or undefined, header 0): no turn. Design Section 5.1 Xiaomi row confirmed |
| `exif_tags` (3 photos, names only) | 46 of 77 known tags present: Make, Model, Orientation, DateTime, DateTimeOriginal, DateTimeDigitized, SubSecTime (three), OffsetTime, OffsetTimeOriginal, ImageWidth, ImageLength, PixelXDimension, PixelYDimension, XResolution, YResolution, ResolutionUnit, YCbCrPositioning, ExifVersion, FlashpixVersion, ColorSpace, ComponentsConfiguration, ExposureTime, FNumber, ExposureProgram, ISOSpeedRatings, ShutterSpeedValue, ApertureValue, BrightnessValue, ExposureBiasValue, MaxApertureValue, MeteringMode, LightSource, Flash, FocalLength, FocalLengthIn35mmFilm, SceneType, SensingMethod, ExposureMode, WhiteBalance, SceneCaptureType, InteroperabilityIndex, JPEGInterchangeFormat, JPEGInterchangeFormatLength, Compression; an embedded thumbnail. **No GPS tag** (none of 15 GPS tags, `getLatLong` false), no MakerNote, Software, UserComment, ImageUniqueID, Artist, Copyright, ImageDescription |
| `view_all` (every object through the pipeline), `leak_scan`, `gallery` | `view_all: 46 photos, 9 other objects, buffers freed 92, bitmaps erased 92`: photos at screen size 1080x1440 (portrait) and 1080x810 (landscape), `turn=0`, thumbnails 192x256; 178 ms per thumbnail and 196 ms per screen-size load on average (max 297 and 272, debug build); the two 100 MB objects `large item` without a buffer, the seven Phase 1 and video prototype objects `unknown item`; `leak_scan: 0 findings, 60 files scanned`; `gallery: 55 objects listed`; PSS with the gallery on screen 117.7 MB (graphics 49.6 MB, Java heap 10.3 MB, native heap 12.6 MB) |
| `capture_photo_n=2`, `verify_all`, `leak_scan` (debug) | `camera open, facing=back, capture resolution=3264x2448`: `PreviewView` binds without `camera-video`; two captures of 2.76 MB, direct buffers, encrypt 143 and 165 ms, `verify=ok`; `verify_all: 57 of 57 ok` (39 s, two 100 MB objects included); `leak_scan: 0 findings, 62 files scanned` |
| Gallery and viewer by hand | 57 tiles: large and unknown tiles for the synthetic objects, thumbnails for the rest; a portrait and a landscape photo open upright, upright again after turning the phone to landscape and back (the viewer reloads at the new size: three `viewer:` lines for the first photo); pinch zoom and double tap work (developer's observation) |
| Recents card, viewer in front | blank white card (developer's observation; `build/p3b/phone/P-recents-viewer.png` holds the whole recents view and was not opened here). Opening recents stopped the viewer and locked the vault (`locking (background)`, `locked`) |
| Recents card, gallery in front | blank (developer's observation) |
| Home with the viewer open | `locking (background)`, `locked`; process alive; reopening showed the lock screen with no gallery or photo behind it |
| Screen off with the viewer open | `locking (background)` through the activity-stop path, `locked`; MIUI then killed the process (new pid on relaunch, as in Phase 3A); the relaunch showed the keypad |
| MIUI camera case (viewer open, Home, system camera, Home, reopen) | `locking (background)`, `locked`; process survived; the lock screen was in front on return (as in Phase 3A) |
| PSS while viewing a 2448x3264 photo (debug) | 132.7 MB (graphics 59.2 MB, Java heap 11.6 MB, native heap 16.7 MB) |
| Benchmark build 0.4.0 (second tap): keypad unlock, capture, Gallery, viewer | `lock: unlocked in 970 ms` at ops 2 / 256 MiB; `camera open` at 3264x2448, one 3.18 MB capture, direct buffer, encrypt 44 ms, capture-to-object 816 ms, `verify=ok`; viewer with a portrait photo at PSS 103.8 MB (graphics 55.0 MB, Java heap 3.9 MB, native heap 18.7 MB) |
| Logcat of the session (all phone logs) | 0 occurrences of the PIN, 0 hex runs over 32 characters, 0 `AndroidRuntime` errors |
| Delete on the phone | not done by hand (two test photos were deleted through the viewer on the emulator in both builds; the phone vault keeps its 58 objects) |

## Orientation probe results per device

| Device | Camera, display | Header rotation | EXIF tag | `ImageDecoder` output | Rule applied | Result |
|---|---|---|---|---|---|---|
| Emulator | back, portrait | 90 | 6 | turned (960x1280) | tag present: no extra turn | upright, once |
| Emulator | front, portrait | 270 | 8 | turned (1440x1920) | tag present: no extra turn | upright, once |
| Emulator | back, display rotated | 0 | 1 | as stored (1280x960) | tag 1: header turn 0 | upright |
| Xiaomi | back, portrait (31) and landscape (15) | 0 | present, value 0 (undefined) | as stored, pixels already turned by the HAL (2448x3264 or 3264x2448) | tag undefined: header turn 0 | upright, confirmed on 46 objects and by the developer on screen |

Fixtures (emulator, T2): all sixteen EXIF and rotation combinations upright by the rule.

EXIF contents of a Xiaomi capture (names only, `exif_tags` probe): make, model, an
undefined Orientation, capture date and time with sub-seconds and UTC offsets, pixel
dimensions, exposure settings, and an embedded thumbnail; **no GPS tag**, no maker note,
software, comment, unique ID, or author tag. The emulator writes make, model, and a real
Orientation tag. All of it lives inside the encrypted payload; stripping it before sharing
is a Phase 5 item (`docs/ROADMAP.md`).

## Viewer and gallery memory per device

Emulator (debug build, `dumpsys meminfo` after `view_all` and the gallery): PSS 57 to 65 MB
with the capture screen bound; a 1280x960 photo decodes to 960x1280 for the viewer (3.5 MB
bitmap) and 192x256 for a thumbnail; the guarded buffer for a 571 KB object is freed within
the load (24 to 69 ms).

Xiaomi (`dumpsys meminfo`): gallery with 55 tiles on screen 117.7 MB PSS (graphics 49.6 MB);
viewer with a 2448x3264 photo decoded to 1080x1440: 132.7 MB PSS in the debug build
(graphics 59.2 MB, Java heap 11.6 MB, native heap 16.7 MB) and 103.8 MB in the benchmark
build (graphics 55.0 MB, Java heap 3.9 MB, native heap 18.7 MB). Per object in the debug
build: decrypt of a 4.4 MB object into guarded memory about 180 ms, thumbnail load 178 ms
and screen-size load 196 ms on average (`view_all`, 46 photos). The guarded buffer is
locked on this phone (`RLIMIT_MEMLOCK` 64 MiB).

## Benchmark unlock time at the final KDF default

Emulator, benchmark build, ops 2 / 256 MiB: 930, 970, 1 106 ms through the keypad. Xiaomi,
benchmark build 0.4.0, the same vault: **970 ms** through the keypad (`lock: unlocked in
970 ms`); the debug build took 12.6 to 13.1 s on the same unlocks, as in Phase 3A.

## Heap investigation (P3-12)

Not done: skipped on the phone at the developer's request for now. The procedure is built
and dry-run on the emulator (the `wait=<ms>` and `gc` actions, `am dumpheap` around them,
dumps to `D:\pv-heap`; `bash build/p3b/phone-p3b.sh heap <pin>` runs it on the phone); the
emulator shows the same shape as the phone in Phase 2 (about one capture of Java heap per
shot until a collection). The phone's two new captures in this session peaked at 7.4 and
7.9 MB of Java heap (debug) and 8.4 MB (benchmark) during the encrypt.
The allocating class, the retention, and whether the array holds image data need the
Android Studio profiler recording on the phone (design Section 10, route a), with route b
(`hprof-conv` and Studio or MAT) as the fallback. Hypothesis from the CameraX 1.6.2 source,
not yet confirmed: the in-memory JPEG path copies the `ImageProxy` into a `byte[]` to read
its EXIF. The threat model records the open position.

## Defects found and fixed

- Test arithmetic (T1): a file cut at a chunk boundary leaves a remainder of at least `c`
  bytes, so the length check refuses it as `MALFORMED_CHUNK` while the Phase 1 decrypt
  calls it `TRUNCATED`; a cut inside the final chunk fails as `AUTH_FAILED`. The test and
  design Section 7.1 were corrected; no core change.
- The test-asset merge rejected the two fixture `README.md` files as duplicates; READMEs are
  excluded from the packaged assets.
- The new leak test could not reserve a capture slot because the lock tests before it leave
  the capture session closed until the next unlock; the test reopens the session, as it
  adopts its vault directly. Not an app defect.
- Host script: the uiautomator dump path was rewritten by Git Bash (`MSYS_NO_PATHCONV`),
  button labels appear in capitals, and the first button of the vault screen lies under the
  status bar of the edge-to-edge window on the Pixel 8 image; the taps aim lower now. Not
  app defects.

## Known limits and unresolved risks

- The heap investigation has not run (above). Delete was not exercised by hand on the
  phone (emulator only, both builds).
- Guarded buffers are locked on the Xiaomi (64 MiB limit) and not on the emulator (64 KiB
  limit); where the lock is refused their pages can reach zram while they exist. Decoder
  scratch, render node, GPU texture, and compositor copies are outside the app on every
  device (threat model Section 8).
- Xiaomi captures carry make, model, timestamps, exposure settings, and an embedded
  thumbnail in their EXIF (no GPS); it stays inside the encrypted payload until a sharing
  phase strips it.
- `Vault.close()` sets a process-wide viewer cancel flag for the length of the close; a
  viewer load on another `Vault` instance at that moment (tests only) would be cancelled too.
- The thumbnail cache is emptied whenever the gallery stops, so returning from the viewer
  redecodes the visible thumbnails.
- Delete removes a directory entry; flash blocks may persist until reuse; no erasure claim.
- The debug build still carries media3 1.11.1 and guava for the video prototype.
- The construction has not been independently reviewed.

## Phase 3B acceptance checklist (brief Section 8)

| # | Criterion | Result |
|---|---|---|
| 1 | Phase 1, 2, and 3A T1 and T2 suites pass | Pass (T1 16/16 before and 17/17 after; T2 53 of the 63, 1 skipped as before) |
| 2 | Photos display upright on emulator and Xiaomi under one documented rule | Pass on both: emulator (probe, 16 fixture cases, `view_all`) and Xiaomi (`orient_probe` over 46 photos, `view_all`, portrait and landscape photos upright on screen in both phone orientations) |
| 3 | Viewer and thumbnails decrypt and decode in memory only; buffers wiped after decode; bitmaps erased on stop and lock | Pass (T1 wipe on failure; T2 free counter, erase counters on stop and lock; no file path exists in the viewer code) |
| 4 | Objects above the size cap never decrypted for display; unknown and damaged items handled without crashing | Pass (T2 `large` without a buffer, `unknown` three kinds, `damaged` three kinds, out of memory two hooks) |
| 5 | `FLAG_SECURE` on every window; recents blank on the Xiaomi for gallery and viewer | Pass: emulator (two window tests, three black captures, recents card black) and Xiaomi (blank white card with the viewer in front, blank card with the gallery in front) |
| 6 | `leak_scan` clean after viewer sessions on emulator and Xiaomi | Pass on both (Xiaomi: 0 findings after `view_all` over 55 objects and after the captures, 60 and 62 files scanned) |
| 7 | Delete one photo works; interrupted delete leaves a consistent vault | Pass (T1 partial and object order, `NOT_FOUND`; T2 delete through the confirmation on debug and benchmark); not exercised by hand on the phone |
| 8 | `camera-video` absent from every variant; release and benchmark contain no media3; debug on one media3 version; manifests unchanged; APK sizes recorded; capture still works on both devices | Pass (class counts, trees, manifests, sizes; captures in debug and benchmark on the emulator and on the Xiaomi) |
| 9 | Heap investigation result recorded with evidence | **Not done** (skipped on the phone at the developer's request for now); the emulator dry run and the procedure are recorded |
| 10 | C ABI changes additive; all golden vectors decrypt on both platforms | Pass (Windows: `test_golden` with the buffer path; emulator: `GoldenVectorTest` unchanged and passing) |
| 11 | Threat model updated: decoder and GPU memory, size cap, delete is not erasure, heap finding | Pass (Section 8; memory lock per device, EXIF contents, the heap row states the open position) |
| 12 | Report separates demonstrated facts from assumptions | Pass (this document) |

## Files changed

- Root: `README.md`, `CLAUDE.md`.
- `src/vault_core/`: `include/pv/pv.h`, `src/viewer.cpp` (new), `src/fs.h`, `src/fs.cpp`,
  `src/pv_init.cpp`, `CMakeLists.txt`; `src/desktop_cli/main.cpp`.
- `tests/core/`: `test_viewer_buffer.cpp` (new), `test_golden.cpp`, `test_cli.cpp`,
  `CMakeLists.txt`; `tests/tools/make_exif_fixtures.py` (new); `tests/fixtures/exif/` (new:
  `base.jpg`, `orient_{1,3,6,8}.jpg`, `README.md`).
- `android/`: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
  `app/src/main/cpp/jni_bridge.cpp`,
  `app/src/main/java/com/privatevault/app/{GalleryActivity,ViewerActivity,PhotoLoader,PhotoView,ThumbCache,JpegExif,ByteBufferInputStream,ViewerActions,HeapActions}.kt`
  (new), `{Vault,NativeBridge,MainActivity,CaptureActivity}.kt`,
  `app/src/debug/java/com/privatevault/app/{ViewerProbes.kt (new),ScriptActivity.kt,DebugVideoProbes.kt}`,
  `app/src/androidTest/.../{ViewerLoaderTest,ViewerScreenTest,ViewerLeakTest}.kt` (new).
- `docs/`: `PHASE3B_DESIGN.md`, `PHASE3B_PROGRESS.md`, this report (new); `DECISIONS.md`
  (D-022 to D-027), `DEPENDENCIES.md`, `THREAT_MODEL.md`, `ROADMAP.md`.

## Suggested commit message

For the T3 session, on top of the Phase 3B commit `9675bb8`:

```
Phase 3B T3 on the Xiaomi: orientation row confirmed, lock cases, benchmark

alloc_probe: the app process has a 64 MiB memory lock limit, so the viewer
buffer is locked on this phone (the emulator refuses the lock). orient_probe
over 46 photos: rotation 0, pixels turned by the HAL, Orientation tag
undefined, both decoders agree; the rule applies no turn. exif_tags probe
(debug): names only, no GPS tag, make, model, timestamps, exposure values,
embedded thumbnail. view_all over 55 objects, leak scans at zero, captures
without camera-video in both builds, Home, screen off, MIUI camera case,
blank recents cards, PSS figures, benchmark unlock 970 ms at ops 2/256 MiB.
Heap investigation deferred. Threat model T27 and residual risks, roadmap
Phase 5 (strip EXIF before sharing), test report, progress updated.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

For the whole stage (already committed as `9675bb8`):

```
Phase 3B: photo gallery and viewer, delete one photo, camera-video removed

Core (additive): exact plaintext length from the header and file length,
decrypt into a caller buffer with a cancel flag and a wipe on every failure
after the stream starts, guarded memory through sodium_malloc, delete of
one object with its stale partial. Error codes 25 and 26. pv inspect prints
the plaintext length. T1 17/17 Debug and Release; every golden vector also
decrypts through the buffer path.

Android: gallery grid listed without decrypting, thumbnails and the viewer
bitmap decoded from guarded memory at the target size and erased on stop,
lock, and eviction; one orientation rule (the EXIF tag, else the header
rotation) checked on 16 synthetic fixture cases; large, unknown, damaged,
and out-of-memory tiles; Vault.close cancels a viewer decrypt within one
chunk; Delete behind an in-activity confirmation; FLAG_SECURE everywhere.
camera-video excluded from every variant (media3 and guava leave release
and benchmark; benchmark 13.8 to 10.6 MB), the V2 and V3a probe code
deleted. T2 63 tests on the emulator; host-driven gallery, viewer, delete,
recents, benchmark-build keypad run and capture; leak scans at zero.

Docs: design (approved with four changes), decisions D-022 to D-027, threat
model Section 8, dependencies, roadmap, progress, test report with the
Xiaomi T3 results. The heap investigation is deferred.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
