# Phase 3B test report

Date: 2026-10-14. Format per `PHASE3B_BRIEF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Not run" means exactly that.

## Status

**Partially complete.** The core additions (T1) and the Android gallery, viewer, delete, and
dependency cleanup (T2, instrumented and host-driven) pass on Windows and the emulator. The
device tier (T3) and the heap investigation have **not run**: the phone needs the
developer's screen wake, install taps, and throwaway PIN, and the heap investigation needs
the Android Studio profiler the developer is installing (checkpoint, CP-9). The scripts and
the exact sequence for those steps are in `docs/PHASE3B_PROGRESS.md`, Section "Phone steps
waiting for the developer". The phone was not touched in this stage: it holds the Phase 3A
debug build 0.3.0 and its 55-object vault.

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, JDK, NDK, libsodium, Gradle, AGP, Kotlin, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2 | As in `docs/PHASE3A_TEST_REPORT.md` (unchanged) |
| Dependencies | `camera-video` removed from every variant; media3 1.11.1 in debug only (Section "Dependency route") |
| App version | 0.4.0 (`versionCode` 4) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), Keystore `SOFTWARE`, no screen lock |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14: connected, screen dozing, not used (Section "T3") |
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

### T3, device (Xiaomi 2201116SG)

**Not run.** The phone was connected throughout, its screen dozing; MIUI refuses shell
input injection including the wake key, every install needs the developer's tap, and the
vault PIN is the developer's throwaway PIN. The developer said he will run the steps with
the screen on. Scripts: `build/p3b/phone-p3b.sh` (`install-debug`, `alloc`, `orient <pin>`,
`view <pin>`, `capture <pin>`, `heap <pin>`, `install-benchmark`, `logcat`); the sequence and
the by-hand steps (zoom, rotation, delete one test photo, Home, screen off, MIUI camera
case, recents cards, PSS while viewing a 3264x2448 photo and while scrolling, unlock time on
the benchmark build, benchmark captures) are in `docs/PHASE3B_PROGRESS.md`. Rows this
report will carry once run: gallery of the 55-object vault with the two 100 MB objects as
`large` and the 1 MB objects as `unknown`; the Xiaomi row of the orientation table; lock
behaviour with the viewer open; recents cards; memory; unlock time; captures on the
camera-video-free benchmark build; heap investigation; `leak_scan`.

## Orientation probe results per device

| Device | Camera, display | Header rotation | EXIF tag | `ImageDecoder` output | Rule applied | Result |
|---|---|---|---|---|---|---|
| Emulator | back, portrait | 90 | 6 | turned (960x1280) | tag present: no extra turn | upright, once |
| Emulator | front, portrait | 270 | 8 | turned (1440x1920) | tag present: no extra turn | upright, once |
| Emulator | back, display rotated | 0 | 1 | as stored (1280x960) | tag 1: header turn 0 | upright |
| Xiaomi | | 0 (Phase 2) | not measured | | assumed: tag 1 or absent, header 0 | assumed upright until `orient <pin>` runs |

Fixtures (emulator, T2): all sixteen EXIF and rotation combinations upright by the rule.

## Viewer and gallery memory per device

Emulator (debug build, `dumpsys meminfo` after `view_all` and the gallery): PSS 57 to 65 MB
with the capture screen bound; a 1280x960 photo decodes to 960x1280 for the viewer (3.5 MB
bitmap) and 192x256 for a thumbnail; the guarded buffer for a 571 KB object is freed within
the load (24 to 69 ms). Xiaomi: not measured (T3).

## Benchmark unlock time at the final KDF default

Emulator, benchmark build, ops 2 / 256 MiB: 930, 970, 1 106 ms through the keypad. Xiaomi:
not measured in this stage (Phase 3A measured 833 to 876 ms per stretch on this phone).

## Heap investigation (P3-12)

Not done. The procedure is built and dry-run on the emulator (the `wait=<ms>` and `gc`
actions, `am dumpheap` around them, dumps to `D:\pv-heap`); the emulator shows the same
shape as the phone in Phase 2 (about one capture of Java heap per shot until a collection).
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

- T3 and the heap investigation have not run (above).
- The Xiaomi half of the orientation rule is an assumption until `orient_probe` runs there.
- Guarded buffers are not locked in memory (`RLIMIT_MEMLOCK` 64 KiB on both devices), so
  their pages can reach zram while they exist; decoder scratch, render node, GPU texture,
  and compositor copies are outside the app (threat model Section 8).
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
| 2 | Photos display upright on emulator and Xiaomi under one documented rule | Pass on the emulator (probe, 16 fixture cases, `view_all`); **not verified on the Xiaomi** (T3 not run; the rule covers the Phase 2 observation by construction) |
| 3 | Viewer and thumbnails decrypt and decode in memory only; buffers wiped after decode; bitmaps erased on stop and lock | Pass (T1 wipe on failure; T2 free counter, erase counters on stop and lock; no file path exists in the viewer code) |
| 4 | Objects above the size cap never decrypted for display; unknown and damaged items handled without crashing | Pass (T2 `large` without a buffer, `unknown` three kinds, `damaged` three kinds, out of memory two hooks) |
| 5 | `FLAG_SECURE` on every window; recents blank on the Xiaomi for gallery and viewer | Pass on the emulator (two window tests, three black captures, recents card black); **Xiaomi not verified** |
| 6 | `leak_scan` clean after viewer sessions on emulator and Xiaomi | Pass on the emulator (instrumented and host-driven); **Xiaomi not run** |
| 7 | Delete one photo works; interrupted delete leaves a consistent vault | Pass (T1 partial and object order, `NOT_FOUND`; T2 delete through the confirmation on debug and benchmark; `unlink` is atomic, the partial goes first) |
| 8 | `camera-video` absent from every variant; release and benchmark contain no media3; debug on one media3 version; manifests unchanged; APK sizes recorded; capture still works on both devices | Pass on the emulator (class counts, trees, manifests, sizes, captures in debug and benchmark); **Xiaomi capture not run** |
| 9 | Heap investigation result recorded with evidence | **Not done** (needs the Studio profiler on the phone); the emulator dry run and the procedure are recorded |
| 10 | C ABI changes additive; all golden vectors decrypt on both platforms | Pass (Windows: `test_golden` with the buffer path; emulator: `GoldenVectorTest` unchanged and passing) |
| 11 | Threat model updated: decoder and GPU memory, size cap, delete is not erasure, heap finding | Pass (Section 8; the heap row states the open position) |
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
model Section 8, dependencies, roadmap, progress, test report. T3 on the
Xiaomi and the heap investigation wait for the developer.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
