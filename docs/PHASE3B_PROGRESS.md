# Phase 3B progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3B_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-14)

Stage 3B complete and committed (`9675bb8`, `93cbc41`, `d9a1445`). Milestones A to D on
Windows and the emulator, Milestone E on the Xiaomi with the developer's taps (Section
"T3 session log"); the heap investigation moved to Stage 3E. The phone holds debug build
0.4.0 and its vault of 58 objects. The emulator was stopped at the end of each session.

## Milestone A log (2026-10-14)

1. `git status` on `main` at `22e0c28`: no tracked file modified; one untracked file
   supplied by the developer (`PHASE3B_BRIEF.md`). Treated as clean and continued.
2. T1 on the unchanged tree (`VCPKG_ROOT`, `VCPKG_DEFAULT_BINARY_CACHE` set; logs
   `build/p3b/t1a-*.log`, not committed):

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug
   ctest --preset windows-x64-tests-debug            # 16/16 passed, 61 s
   cmake --build --preset windows-x64-tests-release
   ctest --preset windows-x64-tests-release          # 16/16 passed, 20 s
   ```

   No compiler warnings in either configuration.
3. Dependency trees on the unchanged tree (brief 4.6.1; `build/p3b/deps-{debug,benchmark,release}.txt`):

   ```
   cd android
   gradlew.bat :app:dependencies --configuration debugRuntimeClasspath      # and benchmarkRuntimeClasspath, releaseRuntimeClasspath
   ```

   Route confirmed: in `benchmarkRuntimeClasspath` and `releaseRuntimeClasspath`,
   `androidx.camera:camera-view:1.6.2` depends on `androidx.camera:camera-video:1.6.2`,
   which depends on `androidx.media3:media3-muxer:1.9.0`, `media3-container:1.9.0`,
   `media3-common:1.9.0`, and `com.google.guava:guava:32.0.1-android` (resolved to
   33.3.1-android through media3-common). Nothing else brings media3 or guava into those
   variants. In `debugRuntimeClasspath` the explicit `camera-video` and `media3-muxer:1.11.1`
   raise media3 to 1.11.1. Google Maven lists 1.11.1 as the newest 1.11.x of `media3-muxer`
   (checked 2026-10-14); `camera-view` has no newer stable than 1.6.2.
4. Baseline Android build and T2 on the unchanged tree (emulator `Pixel_8_API_35` booted
   headless, `ANDROID_SERIAL` pinned; `build/p3b/build-a.log`, `t2-a.log`, `t2-a-results.xml`):

   ```
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark   # up to date from 3A; lint 0 errors, 53 warnings
   gradlew.bat connectedDebugAndroidTest    # 53 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest), 134 s
   ```

   APK sizes before any change: debug 19 036 751 bytes, benchmark 13 822 758 bytes (copies
   kept as `build/p3b/app-{debug,benchmark}-before.apk`).
5. Measurement hooks added (debug variant only, kept for Milestones C and D): JNI
   `nativeProbeSecureAlloc`, `nativeProbeDecryptDirect`, `nativeProbeFreeDirect` under
   `PV_DEBUG_HOOKS` in `jni_bridge.cpp`; `ViewerProbes.kt` in the debug source set
   (`orient_probe`, `alloc_probe`); `ScriptActivity` accepts `alloc_probe` any time and
   `orient_probe` after the unlock.
6. Trial removal of `camera-video` (brief 4.6.2, Milestone A gate "does `PreviewView`
   start?"): `camera-view` now excludes `camera-video` and the explicit debug dependency is
   gone; the Phase 2 V2 probe (`Recorder` over a proxy descriptor) and the V3a attempt
   (custom `VideoOutput`) were deleted from `DebugVideoProbes.kt` because they need
   `camera-video`; `v2_probe` left the action lists. Build and checks
   (`build/p3b/build-trial2.log`, `deps-trial2-*.txt`, `dexclasses.py`):

   ```
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease   # BUILD SUCCESSFUL; lint 0 errors, 54 warnings
   gradlew.bat :app:dependencies --configuration <variant>RuntimeClasspath   # camera-video absent from all three; media3 only in debug (1.11.1); guava only in debug
   python -I build/p3b/dexclasses.py <apks>    # classes defined per package, from the dex class_defs
   ```

   | APK | Before | After | media3 classes | camera.video classes | `PreviewView`, `ListenableFuture` present |
   |---|---|---|---|---|---|
   | debug | 19 036 751 | 19 039 140 | 626 (muxer 1.11.1 for the prototype) | 445 then 0 | yes |
   | benchmark | 13 822 758 | 10 529 341 | 557 then 0 | 443 then 0 | yes |
   | release (unsigned) | not built in 3A | 10 521 149 | 0 | 0 | yes |

   `com.google.guava:listenablefuture` now resolves to the real 1.0 artifact in benchmark
   and release (before, guava supplied the class and the 9999.0 placeholder was selected);
   `ListenableFuture` is defined in every APK. The one new lint warning is
   `ExifInterface` on the probe's use of `android.media.ExifInterface` (the probe reads from
   an in-memory stream; the androidx class is not a dependency of the main source set).
7. Emulator probes with the trial debug build (`build/p3b/emu-probe.sh`, logs
   `E1-setup.txt` to `E4-video.txt`; throwaway PIN from the README):

   ```
   adb -s emulator-5554 install -r app-debug.apk; adb shell pm grant com.privatevault.app android.permission.CAMERA
   am start -n com.privatevault.app/.ScriptActivity --es actions setup_pin=<pin>,alloc_probe,open,capture_photo_n=2,flip,capture_photo_n=2
   settings put system user_rotation 1; am start ... unlock_pin=<pin>,open,capture_photo_n=1,flip,capture_photo_n=1; user_rotation 0
   am start ... unlock_pin=<pin>,orient_probe,open,list
   am start ... unlock_pin=<pin>,open,video_probe,leak_scan
   ```

   - `alloc_probe`: `sodium_malloc` succeeds at 5, 8, 16, 32, and 64 MiB (3, 4, 10, 17, 28 ms
     to allocate, which includes the 0xdb fill; 1 to 6 ms to wipe and free). A second
     `mlock` on the block fails with errno 12 (`ENOMEM`): `RLIMIT_MEMLOCK` is 65 536 bytes
     (hard and soft). `sodium_malloc` ignores its own `mlock` failure, so the block is
     guarded and wiped but not locked.
   - `PreviewView` after the removal: `camera open, facing=back, capture resolution=1280x960`
     through `CaptureActivity`, 8 captures with `verify=ok` and `buffer=DIRECT` (back 1280x960,
     front 1920x1440, both lenses, one capture while the display was rotated).
   - `orient_probe`, 8 JPEG objects: see the table in `docs/PHASE3B_DESIGN.md` Section 5.1.
     In every case the EXIF orientation tag inside the JPEG equals the header rotation
     (6 for 90, 8 for 270, 1 for 0), `BitmapFactory` reports the stored (unrotated) size,
     and `ImageDecoder` reports and decodes the rotated size: `ImageDecoder` applies the EXIF
     orientation on its own. `ImageDecoder.createSource(ByteBuffer)` accepted the direct
     buffer over `sodium_malloc` memory (no exception, mutable `ARGB_8888` software bitmap
     with `ALLOCATOR_SOFTWARE` and `isMutableRequired`), and freeing the buffer right after
     the decode was fine. Decode at sample size 4: 4 to 11 ms; decrypt of a 571 KB object
     into the direct buffer 5 to 8 ms (debug build).
   - `video_probe` (V3b only now): 288 frames, 4 segments, every segment parsed and its first
     frame decoded, index object written, `leak_scan` 0 findings, 0 new non-object files.
   - The second scripted run made more captures than listed because the rotation change
     recreated `CaptureActivity`, which reran its pending actions; the emulator's
     orientation sensor still reported portrait, so only the first of those captures
     carried rotation 0. Recorded as observed; the probe data is per object either way.
8. T2 on the trial tree (`build/p3b/t2-trial.log`, `t2-trial-results.xml`):
   `gradlew.bat connectedDebugAndroidTest`: **53 tests, 0 failures, 0 errors, 1 skipped**
   (`LockBoundKeyTest`, no emulator PIN), 98 s. The capture, leak scan, and lock tests
   pass with `camera-video` gone.
9. Toolchain facts for the design: Android Studio is still AI-242 (Ladybug Feature Drop)
   and cannot open the AGP 8.9 project, so the heap investigation (brief 4.7) uses
   `am dumpheap` and Perfetto (`perfetto` v25 on the phone, v46 on the emulator). The phone
   reports 7.6 GB `MemTotal` and `RLIMIT_MEMLOCK` 65 536 bytes (`ulimit -l` 64 KiB on both
   devices).

## Phone steps (Milestone E, T3; run on 2026-10-14 except `heap`)

The phone (`9c4424ba81c6`) is connected but its screen is dozing, MIUI refuses
`input keyevent` from the shell, every install needs the on-screen tap, and the vault PIN
is the developer's throwaway PIN, which is not recorded anywhere. The developer said he
will run these with the screen on. In this order, from the repository root in Git Bash
(`build/p3b/phone-p3b.sh`, outputs under `build/p3b/phone/`):

```
bash build/p3b/phone-p3b.sh install-debug         # debug 0.4.0; tap Install on the phone
bash build/p3b/phone-p3b.sh alloc                 # alloc_probe (sodium_malloc 5 to 64 MiB, mlock, RLIMIT_MEMLOCK); no PIN
bash build/p3b/phone-p3b.sh orient <pin>          # orient_probe over the 55-object vault: the Xiaomi row of design Section 5.1
bash build/p3b/phone-p3b.sh view <pin>            # view_all over every object, leak_scan, then the gallery screen; prints PSS
bash build/p3b/phone-p3b.sh capture <pin>         # two captures through PreviewView on the camera-video-free build, verify_all, leak_scan
bash build/p3b/phone-p3b.sh heap <pin>            # ten captures, am dumpheap, gc, second dump; dumps land in D:\pv-heap (outside the repository)
```

By hand on the phone while `bash build/p3b/phone-p3b.sh logcat` streams: Gallery, open a
photo, pinch to zoom, rotate the phone (portrait and landscape upright), Delete one test
photo; with the viewer open press Home, then screen off, then the MIUI camera case (Home,
system camera, Home, reopen): the lock screen must be in front each time and nothing of the
photo restored; recents card with the gallery and with the viewer in front (`adb exec-out
screencap -p` of the recents view). Then `install-benchmark` (second tap) for the unlock
time at ops 2 / 256 MiB, a capture, Gallery, viewer, and `dumpsys meminfo` while a
3264x2448 photo is shown and while scrolling. The heap investigation (design Section 10)
needs the updated Android Studio: attach its profiler with Java allocation recording to the
debug build before `heap <pin>`, and open the two dumps from `D:\pv-heap` in it afterwards;
delete the dumps and the recording when done. Finally `install-debug` again if the debug
build should stay on the phone.

`orient_probe` and `view_all` read every object once (decrypt into guarded memory, EXIF
read, decode, wipe); nothing is written, and only IDs, sizes, tag values, kinds, and times
are logged.

## Milestone D log (2026-10-14)

Android (`android/app/src/main`): JNI `nativeObjectPlaintextSize`, `nativeViewerLoad` (length,
guarded allocation, decrypt with the cancel flag, item header; the buffer reaches Kotlin
for JPEG items only, everything else is wiped in the call), `nativeViewerFree`,
`nativeSetViewerCancel`, `nativeDeleteObject`, debug `nativeSetViewerHooks`; `NativeBridge`
mirrors codes 25 and 26. `Vault.close()` sets the viewer cancel flag before the write lock
and clears it after; `Vault.listObjects`, `plaintextSize`, `viewerLoad` (returns a
`ViewerLoad` that frees its buffer exactly once and counts the frees), `deleteObject`. New:
`PhotoLoader` (the pipeline and the orientation rule, one viewer thread, erase helper, the
debug disagreement log line), `JpegExif` (platform `ExifInterface` over
`ByteBufferInputStream`), `ThumbCache`, `PhotoView`, `GalleryActivity`, `ViewerActivity`
(in-activity delete confirmation), `ViewerActions.viewAll`; `MainActivity` gains the
Gallery button and the actions `view_all`, `gallery`, `delete_object=<hex>`, `gc`; manifest
entries for the two activities (not exported); version 0.4.0 (code 4). Fixtures:
`tests/fixtures/exif/{base,orient_1,orient_3,orient_6,orient_8}.jpg`, `README.md`,
`tests/tools/make_exif_fixtures.py`; the test APK packages them as assets (READMEs excluded
from the asset merge through `ignoreAssetsPattern`).

New instrumented classes: `ViewerLoaderTest` (the 16 EXIF and rotation combinations with the
red-square check, decoded size bounded and never upscaled, swapped bound for a turned viewer,
large, unknown (plain, empty, video segment), damaged (tamper, truncation, non-JPEG payload),
allocation and decode failure hooks with the free counter, delete with partial, lock during
a slowed decrypt), `ViewerScreenTest` (`FLAG_SECURE` on both windows, thumbnails erased on
stop, viewer bitmap erased on stop, lock finishes the viewer), `ViewerLeakTest` (two real
captures, `view_all`, scan: no new files, no markers).

```
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease   # BUILD SUCCESSFUL; lint 0 errors, 67 warnings (SetTextI18n on the new screens, as on the lock screen)
gradlew.bat connectedDebugAndroidTest    # 63 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest), 209 s
```

Two defects found on the way and fixed: the test-asset merge rejected the two fixture
`README.md` files as duplicates (READMEs are now excluded from the assets), and the new leak
test could not reserve a capture slot because the lock tests before it leave the capture
session closed until the next unlock (the test reopens the session, as it adopts its vault
directly). Logged by the run (`build/p3b/t2-d3-logcat.txt`, 454 lines): `close took 1212 ms,
load result cancelled` for the lock during a slowed decrypt; every orientation case names
the red square at the expected corner; the disagreement line fires for the fixtures whose
tag and header differ, with the ID, the tag, and the header rotation only; 0 occurrences of
the test PINs, 0 hex runs over 32 characters. APK sizes: debug 17 262 460, benchmark
10 578 545, release 10 570 353 bytes; 0 media3 and 0 `camera.video` classes outside debug.

Host-driven steps on the emulator (`build/p3b/host-d.sh`, `host-ui.sh`, `emu-bench-capture.sh`;
outputs `H*.txt`, `U*.txt`, `U*.png`, `E1-heap.txt`, not committed; the uiautomator dumps
need `MSYS_NO_PATHCONV=1` in Git Bash or the `/sdcard` path is rewritten):

| Step | Result |
|---|---|
| Merged manifests (`aapt2`, three variants) | permissions `CAMERA` plus the app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; debug exports `ScriptActivity` and `LockActivity`, benchmark and release export `LockActivity` only; `MainActivity`, `GalleryActivity`, `ViewerActivity`, `CaptureActivity` not exported; `MetadataHolderService` not exported |
| Debug: setup, 3 captures, `encrypt_1mb`, `view_all`, `leak_scan`, `gallery` | `view_all: 3 photos, 1 other objects, buffers freed 6, bitmaps erased 6` (photos 960x1280 and 1080x1440 at screen size, `turn=0`, EXIF 6 and 8, header 90 and 270; thumbnails 192x256; 24 to 69 ms per load); `leak_scan: 0 findings`; `gallery: 4 objects listed` with one `unknown item` and three `photo` tiles |
| Screen captures | gallery, viewer, and the recents card with the viewer in front: black apart from the status bar (`pngstat.py`: one colour over 99.9 percent of the samples) |
| Viewer by tap (debug) | a photo tile opens the viewer (`1080x1440 decoded, turn 0`); Delete shows the in-activity confirmation (`Delete this photo? ...`, `DELETE`, `CANCEL`); the second Delete removes the object (`viewer: delete <id> PV_OK`), the gallery returns with `8 objects` |
| `list`, `leak_scan`, `video_probe`, `leak_scan` (debug) | 4 then 9 objects; V3b records 287 frames in 4 segments, every segment parsed and its first frame decoded, index written; `leak_scan: 0 findings` before and after, 0 new non-object files |
| Benchmark build (no `ScriptActivity`): keypad unlock, Gallery, viewer, Delete by `uiautomator` taps | `lock: unlocked in 970 ms` (ops 2 / 256 MiB, emulator); `gallery: 8 objects listed`; viewer `960x1280 decoded, turn 0`; delete `PV_OK`; gallery returns with 7 objects; gallery and viewer captures black |
| Debug: capture burst with the heap actions (`capture_photo_n=10,memory,wait=20000,gc,memory`) | `java_heap_peak` per capture 5.0, 6.1, 7.1, 8.0, 8.9 MB then back to 5.9 MB and rising again (about 1 MB per 571 KB capture until a collection); `gc: java_used 5604KiB -> 3786KiB`; native heap steady at 18.3 to 19.1 MB; `am dumpheap` before and after the collection worked (44 MB each, pulled to `D:\pv-heap`, deleted again unread: no tool here opens them, CP-9) |
| Benchmark build: capture through `PreviewView` (keypad unlock, `CAPTURE (CAMERA)`, `CAPTURE` by tap) | `camera open, facing=back, capture resolution=1280x960`; one capture `verify=ok` (570 464-byte JPEG, direct buffer, encrypt 136 ms); capture screen capture black. The first attempt tapped the centre of the top button, which lies under the status bar of the edge-to-edge window on this Pixel 8 image (about 118 px with the cutout); taps now aim at the lower part of a node |


## Milestone C log (2026-10-14)

Core (all additive, `src/vault_core`): `include/pv/pv.h` gains error codes 25
(`PV_ERR_BUFFER_TOO_SMALL`) and 26 (`PV_ERR_CANCELLED`), `pv_object_plaintext_size`,
`pv_object_plaintext_size_file` (the path form for `pv inspect`, which has no vault handle;
an addition against the approved list, recorded in design Section 7.1),
`pv_vault_decrypt_to_buffer` (length check first, buffer sink with cap and cancel checks,
wipe on every failure after the stream started), `pv_secure_alloc`, `pv_secure_free`,
`pv_vault_delete_object` (partial first, then the object, then the directory sync). New
`src/viewer.cpp`; `fs` gains `file_size` and `sync_dir`; `pv_error_name` learns both codes.
No existing function changed. `pv inspect` prints `plaintext <n>`.

Found while writing the tests: a file cut at a chunk boundary leaves a remainder of at
least `c` bytes, so the length check itself refuses it as `MALFORMED_CHUNK` (the Phase 1
decrypt calls the same cut `TRUNCATED`); a file cut inside its final chunk reads as a
shorter object and the decrypt refutes it with `AUTH_FAILED`. Design Section 7.1 and the
test expectations were corrected accordingly.

Tests: `tests/core/test_viewer_buffer.cpp` (lengths exact for 0, 1, c - 1, c, c + 1, 2c,
2c + 1, 3c + 123 at both chunk sizes and for a 100 MB object; round trips into exact and
larger buffers; too-small buffer untouched; tamper in three chunks, truncation inside the
final chunk, trailing byte, wrong key all leave a zero buffer; boundary cut, bytes forming
no chunk, too short, renamed, and missing objects refused with the buffer untouched; cancel
flag; guarded memory arguments, 1 byte and 32 MiB; delete with and without a partial, twice,
lone partial, other objects intact; argument checks; error names), `test_golden` (every
committed vector also reports its exact length and decrypts into a buffer of that size with
the manifest hash), `test_cli` (`plaintext 131079`).

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug            # 17/17 passed, 37 s
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release          # 17/17 passed, 28 s
```

No compiler warnings at `/W4` in either configuration (`build/p3b/t1c2-*.log`).

## T3 session log (2026-10-14, the phone)

The developer committed the stage (`9675bb8`), woke the phone, tapped the installs, gave
the throwaway PIN in chat (not recorded here), and did the by-hand steps while logcat
streamed. Order: `install-debug`; `alloc` (app `RLIMIT_MEMLOCK` 64 MiB, `mlock` succeeds up to
64 MiB: the Milestone A figure of 64 KiB was the adb shell user's limit); `orient` (46
photos, rotation 0 with turned pixels, Orientation tag undefined, decoders agree); `view`
(46 photos, 2 large, 7 unknown, buffers freed 92, bitmaps erased 92, `leak_scan` 0, gallery
of 55 tiles, PSS 117.7 MB); `exif_tags` (a new debug probe, rebuilt and reinstalled with a
second tap: tag names only, no GPS); `capture` (`PreviewView` binds without `camera-video`,
two captures, 57 of 57 verify, `leak_scan` 0); by hand: gallery and viewer upright in
portrait and landscape, zoom and double tap, recents card blank with the viewer and with
the gallery in front, Home, screen off, and the MIUI camera case all end on the lock screen
(the screen-off case killed the process as in 3A), PSS 132.7 MB with a 2448x3264 photo
shown; `install-benchmark` (third tap): keypad unlock 970 ms at ops 2 / 256 MiB, one capture
(encrypt 44 ms, 816 ms to object), viewer at PSS 103.8 MB; `install-debug` again. Heap step
skipped at the developer's request. Results in `docs/PHASE3B_TEST_REPORT.md` (Section T3);
logs under `build/p3b/phone/` (0 PIN strings, 0 hex runs over 32 characters, 0 runtime
errors). Developer amendments applied: `docs/ROADMAP.md` Phase 5 gains "strip EXIF (or
rewrite a minimal header) before sharing"; `docs/THREAT_MODEL.md` T27 states the memory lock
per device and the EXIF contents.

## Milestone E log (2026-10-14, without the phone)

Done: `docs/PHASE3B_TEST_REPORT.md` (T1 and T2 complete, T3 rows marked as waiting for the
developer with the exact commands, acceptance checklist with the phone-dependent rows
open), `docs/THREAT_MODEL.md` Section 8 (heap position from the emulator observation),
`docs/DEPENDENCIES.md`, `README.md`, `docs/ROADMAP.md`, `CLAUDE.md`, design Section 18.
Final build of every variant with lint after the last source change (`build/p3b/build-e.log`:
`BUILD SUCCESSFUL`, lint 0 errors, 65 warnings); final APK sizes debug 17 608 236, benchmark
10 578 545, release 10 570 353 bytes. The heap dumps of the emulator dry run were deleted
unread; `D:\pv-heap` is empty. The emulator was stopped. `git status` and `git diff` checked:
no secrets, build output, dumps, captures, or large files; the fixtures are 669 to 705 bytes
each. Not done, waiting for the developer: every T3 step and the heap investigation itself
(Section "Phone steps waiting for the developer").

## Checkpoint (2026-10-14)

Approved by the developer with four changes, applied to `docs/PHASE3B_DESIGN.md` (status
line names them), `docs/DECISIONS.md` (D-022 to D-027 marked approved; D-026 and D-027
amended), `docs/THREAT_MODEL.md` Section 8 (T27 wording: buffers guarded and wiped but not
locked, so they can reach zram), and `docs/ROADMAP.md` (R8 with JNI keep rules as a Phase 6
hardening item, CP-8): the EXIF tag is read with the platform `android.media.ExifInterface`
over an in-memory stream instead of an own parser (CP-5); the debug build logs one line when
the tag and the header rotation disagree (CP-1); the heap investigation uses the Android
Studio profiler the developer is installing, plus `am dumpheap`, with `hprof-conv` and
Studio or MAT as the fallback (CP-9). The developer runs the phone steps of Milestone A with
the screen on.

## Milestone B log (2026-10-14)

`docs/PHASE3B_DESIGN.md` written: viewer pipeline, gallery rules and the 32 MiB cap,
orientation rule from the probe, C ABI additions with final names and error codes 25 and
26, delete semantics, dependency route taken (exclusion on `camera-view`, V2 and V3a
removed), heap investigation plan with the tools available, failure table, test plan,
checkpoint items CP-1 to CP-8, seams for 3C to 3E, threat model deltas. `docs/DECISIONS.md`
D-022 to D-027 (proposed). `docs/THREAT_MODEL.md` Section 8 (proposed). Checkpoint report
sent to the developer. Milestone C waits for approval.

## Toolchain inventory delta (2026-10-14)

Same machine as Phase 3A (`docs/PHASE3A_PROGRESS.md`). Session shell had `VCPKG_ROOT`,
`VCPKG_DEFAULT_BINARY_CACHE`, `ANDROID_HOME`, `JAVA_HOME`, `GRADLE_USER_HOME` set;
`ANDROID_NDK_HOME` is exported by the helper scripts under `build/p3b/` (not committed:
`t1.sh`, `emu-start.sh`, `a-android.sh`, `a-trial.sh`, `t2-only.sh`, `emu-probe.sh`,
`phone-p3b.sh`, `dexscan.py`, `dexclasses.py`). Emulator started headless for T2 and the
probes. No SDK component installed or changed. All output on D.
