# PrivateVault Phase 2 Summary

**Status:** Complete. All acceptance criteria pass on Windows, the Android emulator, and a physical Xiaomi phone.
**Date:** 2026-10-13.
**Detailed record:** `docs/PHASE2_TEST_REPORT.md`, `docs/PHASE2_DESIGN.md`, `docs/VIDEO_CAPTURE_FEASIBILITY.md`, `docs/DECISIONS.md` (D-012 to D-015).

## What was built

1. **In-app photo capture** (CameraX 1.6.2)
   - Capture screen with preview, shutter, and front/back toggle. `FLAG_SECURE` blanks screenshots and the recents thumbnail.
   - In-memory JPEG capture. The camera's direct buffer is passed to the C++ core through JNI and read in place, so app code makes no Kotlin copy.
   - Each photo becomes one encrypted `.pvo` object, verified right after it is written.
   - No plaintext files, no MediaStore entries, no gallery copy.
2. **Media item header**: 48 bytes at the start of the encrypted payload, holding type, rotation, width, height, capture time, and payload length. It is encrypted and authenticated with the media. The v1 object format is unchanged.
3. **C ABI additions** (additive only): item header encode and decode, a memory source, and an item sink that verifies without holding the plaintext. Error codes 21 to 23.
4. **Failure handling**:
   - Bounded capture queue (2) and a free-space precheck.
   - Encryption runs on a process-scoped thread, so it survives backgrounding.
   - A process kill leaves only a `.partial`, which the next open removes.
5. **Leak scan**: walks app-visible storage for JPEG and MP4 markers, checks MediaStore rows owned by the app, and reviews logcat.
6. **Video feasibility prototype** (debug build only) and report.
7. **Toolchain**: AGP 8.9.3, Gradle 8.11.1, compileSdk 36. Required by CameraX 1.6 and media3. Android Studio Ladybug can no longer open the project.

## Video findings

- CameraX `Recorder` only writes to a file or a seekable descriptor. Probed through a proxy descriptor, the platform muxer rewrites earlier offsets, which a sequential encrypted writer cannot serve. Not a path.
- A custom `VideoOutput` needs a restricted CameraX API (`getMediaSpec`). Not usable.
- **Working path**: a MediaCodec encoder surface fed by the `Preview` use case, `FragmentedMp4Muxer` per 3 s segment, each segment encrypted as one object, plus an index object. Measured on the phone with its hardware encoder: 1600x1200 at 4.8 Mbit/s, 4 segments, every segment decoded after decryption, nothing written in plaintext.
- Gaps:
  - No viewfinder while recording.
  - Segments are buffered in Java memory.
  - No audio.
  - Encoder and container key-frame counts differ, which matters for seeking.
- Recommendation: full video as its own phase, after the viewer, because capture and playback share the index format. Estimated at four milestones.

## Test results

| Tier | Where | Result |
|---|---|---|
| T1 automated | Windows, Debug and Release | 14/14 pass |
| T2 automated | Emulator, API 35 | 31 tests, 0 failures, 1 skipped (PIN test, passed in its own run) |
| Lock-bound key | Emulator with a temporary PIN | Pass: works unlocked, "Device locked" while locked |
| Cross-platform | Android-made item objects decrypted on Windows | Pass |
| T3 device | Xiaomi 2201116SG, Android 13, MIUI | Pass (details below) |

T3 on the phone:

- 21 captures at 3264x2448, all verified.
- Verify passed after force-stop and after reboot.
- A kill during encrypt left nothing behind.
- Recents card blank.
- Tamper, truncation, and wrong key all rejected.
- Leak scan clean.

Benchmark (release-optimized) build on the phone, against the Phase 1 debug build:

| Operation | Phase 1 debug | Phase 2 benchmark |
|---|---|---|
| Encrypt 1 MB | 156 ms | 43 ms |
| Encrypt 100 MB | 20 s | 1.3 s |
| Encrypt a 2.6 MB photo | n/a | 45 to 59 ms |

## Defects found and fixed

- **D-013:** dependencies added `ACCESS_NETWORK_STATE` and an exported receiver to the manifest. Both were removed with merge rules. The manifest now carries `CAMERA` only.
- **D-015 (phone only):** the leak scan created the empty external directories it scanned, which MediaStore indexed after a reboot. Fixed and covered by a test.

## Known limits

- MIUI kills the app in the background when another camera app is opened. The user returns to a closed vault.
- On the Xiaomi, JPEGs arrive already rotated (rotation 0, width and height swapped). On the emulator the rotation field carries the angle. A viewer must handle both.
- Java heap rises by about one JPEG per capture until garbage collection. Possibly CameraX rotating in the Java heap on this phone. Unverified; a heap dump would settle it.
- Camera-stack memory (HAL, ISP, image buffers, CameraX internals) holds plaintext outside app control and cannot be wiped.
- The release build carries media3 1.9.0 through `camera-video`, while the debug build uses 1.11.1. APK size grew from 4.1 to 17.6 MB.
- There is no delete action. 52 test objects remain on the phone.
- Metadata stays visible (sizes, counts, timestamps). There is no in-app lock and no independent security review.

## Carry forward to Phase 3 planning

1. In-app lock: app PIN, biometric, or both. The developer's phone has no screen lock, so the device credential is not an option there. Covers lock on background and timeout, and reopening after a MIUI process kill.
2. Protected viewer for photos: decrypt into memory, apply rotation, `FLAG_SECURE`, clear decoded images on background.
3. Viewer scope: photos only, or also play the prototype video segments to settle the index format and seeking early.
4. Video capture placement: its own phase after the viewer (recommended) or the second half of Phase 3.
5. Housekeeping:
   - Heap dump during a capture burst.
   - Delete actions (per object and whole vault).
   - Choose one media3 line and keep `camera-video` out of release.
