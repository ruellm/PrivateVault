# PrivateVault Stage 3B Brief: Protected Photo Viewer and Cleanup

**Document role:** Implementation brief for Stage 3B. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3B matters, this brief wins. Baseline: Phase 1 and 2 records, Stage 3A records (`docs/PHASE3A_DESIGN.md`, `docs/PHASE3A_TEST_REPORT.md`), `docs/DECISIONS.md` D-001 to D-021.

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`). Everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Decisions continue from D-022.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| 3A | PIN lock, PIN-derived key, lock rules, erase vault (done) |
| **3B** | **Photo viewer, delete photo, dependency cleanup, heap check (this brief)** |
| 3C | Video groundwork: key-frame investigation, index format, seeking, playback probe |
| 3D | Video playback in the viewer |
| 3E | Video capture as a feature |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

## 2. Starting point (verified, do not re-litigate)

- Stage 3A: provider 3 envelope (ops 2 / 256 MiB, D-021), `LockController` with states and triggers, `LockActivity` exported launcher, `MainActivity` and `CaptureActivity` not exported, `ScriptActivity` debug only, `Vault` read-write guard so `close()` waits for the call in flight, `FLAG_SECURE` on all activities, confirmations as in-activity views (no `Dialog` windows).
- `LockController` posts `LOCKED_UI` to registered activities; a viewer activity joins the same rule.
- Item header (48 bytes, Phase 2): media type, rotation, width, height, capture time, payload length. Parsed only after decryption returns `OK`.
- Phone-observed: Xiaomi JPEGs arrive pre-rotated (rotation 0, width and height swapped); emulator JPEGs carry the angle in the rotation field. Java heap rises about one JPEG per capture on the Xiaomi until GC, cause unverified.
- Phone vault: 55 objects. Some are Phase 1 synthetic objects without an item header, including two of 100 MB.
- Dependencies: release and benchmark carry `camera-video` 1.6.2 and media3 1.9.0, most likely through `camera-view`; debug uses media3 1.11.1 for the video prototype. APK 17.6 MB.
- Debug builds take about 12.5 s per unlock on the Xiaomi at the default KDF; benchmark about 0.85 s.

## 3. Decisions (final)

| ID | Decision |
|---|---|
| P3-9 | Gallery grid with in-memory thumbnails; single-photo viewer, fit to screen, zoom up to 2x on the screen-size bitmap. No full-resolution or region decoding. No persistent thumbnails. |
| P3-10 | Remove `camera-video` from every variant, including debug. Delete the Phase 2 V2 probe code that used `Recorder`. Keep media3 on one version (latest 1.11.x) for the debug video prototype's muxer; media3 enters release only when video ships (3D/3E). |
| P3-11 | Delete one photo from the viewer, behind an in-activity confirmation. |
| P3-12 | Heap investigation of the capture burst on the Xiaomi. |
| P3-14 | `FLAG_SECURE` on every window; no `Dialog`, popup, or toast that shows content. |

## 4. Architecture

### 4.1 Viewer pipeline (per photo)

```
pv_object_plaintext_size(id) -> n        exact, from file length and chunk size, header validated first
pv_secure_alloc(n) -> buffer             sodium_malloc; plain malloc fallback if it fails (record which)
pv_vault_decrypt_to_buffer(id, buffer)   OK only after FINAL; on any failure the buffer is wiped
pv_item_header_decode                    media_type must be 1 (JPEG)
JNI NewDirectByteBuffer over the payload after the item header
ImageDecoder.createSource(buffer)        setTargetSize to view size, ALLOCATOR_SOFTWARE
apply the orientation rule (4.3)
pv_secure_free(buffer)                   wipe then free, right after decode
```

- Software bitmaps only, so the app can overwrite pixels with `eraseColor(0)` before `recycle()`.
- Bitmaps live only in the viewer and a bounded thumbnail cache (for example 32 entries at 256 px). Both are erased and dropped on activity `onStop` and on `LOCKED_UI`.
- A decode in progress when the lock starts: the result is discarded and its buffer wiped. Decrypt calls go through the `Vault` guard like every other core call.
- Outside app control, for the threat model: decoder internal buffers, GPU textures, render and compositor buffers. `FLAG_SECURE` keeps them out of screenshots and recents; it does not erase them.

### 4.2 Gallery

- Lists objects without decrypting them; order by file modification time, newest first (already visible metadata).
- Thumbnails decode lazily as cells appear, on one background thread, through the same pipeline.
- Size cap: an object whose plaintext size is above a cap (proposed 32 MiB) is shown as a "large item" tile and never decrypted for display. This keeps the Phase 1 synthetic 100 MB objects out of memory.
- An object that decrypts but has no item header, or a media type other than 1, is shown as an "unknown item" tile; its buffer is wiped at once.
- An object that fails to decrypt (tamper, truncation) is shown as a "damaged" tile with the error name.

### 4.3 Orientation rule

Milestone A builds a debug action `orient_probe` that, for each photo object, records:
- header rotation, width, height;
- the JPEG EXIF orientation tag (read with `ExifInterface(InputStream)` over the in-memory buffer);
- decoded dimensions from `ImageDecoder` and from `BitmapFactory.decodeStream` over the same buffer.

From those results the design states one rule that shows photos upright on both devices without rotating twice. To verify in Milestone A: whether `ImageDecoder` applies EXIF orientation by itself. T2 covers the rule with committed synthetic JPEG fixtures carrying hand-built EXIF orientation values 1, 3, 6, 8, combined with header rotations 0, 90, 180, 270.

### 4.4 Delete one photo

- From the viewer, unlocked, behind an in-activity confirmation.
- `pv_vault_delete_object(id)` removes the file (and a matching `.partial` if one exists). Not found is reported by name.
- The object's DEK lives in its own header, so this is file removal, not cryptographic erasure. Document that flash storage gives no erasure guarantee.
- Scripted debug action `delete_object=<id>`.

### 4.5 C ABI additions (additive only)

```c
int  pv_object_plaintext_size(pv_vault* v, const uint8_t id[16], uint64_t* out);
int  pv_vault_decrypt_to_buffer(pv_vault* v, const uint8_t id[16], uint8_t* buf, uint64_t cap);
int  pv_secure_alloc(size_t n, void** out);
void pv_secure_free(void* p, size_t n);
int  pv_vault_delete_object(pv_vault* v, const uint8_t id[16]);
```

Final names at the checkpoint. New error codes after 24 (for example buffer too small, object not found). D-011 status/handle split for anything handle-like. To verify in Milestone A: whether `sodium_malloc` succeeds for 5 to 32 MiB on both devices (it locks memory; `RLIMIT_MEMLOCK` may refuse).

### 4.6 Dependency cleanup

1. Record `gradlew :app:dependencies` for debug, benchmark, and release runtime classpaths; confirm the path that brings `camera-video` and media3 into release.
2. Remove `camera-video` from all variants (exclusion on `camera-view` if that is the route). Delete the V2 probe code.
3. Prove `PreviewView` still works: R8-optimized benchmark build, all capture tests on emulator and Xiaomi.
4. If `PreviewView` needs `camera-video` at runtime: drop `camera-view` and draw the viewfinder through `Preview.setSurfaceProvider` into the app's own `SurfaceView` (public CameraX API). Record which route was taken.
5. Debug: media3 on one pinned 1.11.x version; the Phase 2 video prototype test still passes.
6. Verify the release and benchmark APKs contain no `androidx.media3` and no `androidx.camera.video` classes. Record APK sizes before and after. Recheck merged manifests: `CAMERA` only; only `LockActivity` exported outside debug.

### 4.7 Heap investigation

- Goal: find what allocates about one JPEG-sized Java array per capture on the Xiaomi, and whether it holds image data.
- Method: debug build on the Xiaomi, 10 captures of a neutral scene. Java allocation recording (Android Studio profiler if the installed version opens the project, otherwise Perfetto) for arrays above 1 MB with allocation stacks, plus a heap dump before and after GC. Record which tool and why.
- Handling: heap dumps and traces contain image data. Keep them under D: outside the repository, delete after analysis, never commit or attach them.
- Report: allocating class and method, retention, and whether a public CameraX setting avoids the copy. Change capture code only if a public setting removes it and all capture tests stay green; otherwise record it in the threat model as a CameraX-internal copy.

## 5. Failure handling

| Situation | Behaviour | Test |
|---|---|---|
| Lock during decrypt or decode | Result discarded, buffer wiped, viewer gone | T2 |
| Decrypt fails (tamper, truncation) | Buffer wiped; "damaged" tile or error in viewer; no partial image | T1, T2 |
| Object above the size cap | "large item" tile, never decrypted for display | T2 |
| No item header or unknown media type | "unknown item" tile, buffer wiped | T2 |
| `sodium_malloc` fails | Fallback recorded and used, or clean error | T2 |
| Out of memory on decode | Error shown, buffers wiped, app stays usable | T2 (test hook) |
| Delete of a missing object | Not-found reported; gallery refreshed | T1, T2 |
| Delete interrupted | File gone or present, never half; gallery consistent on next open | T2 |

Test hooks live only in `androidTest` or debug-only code.

## 6. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 green; dependency trees (4.6.1); `orient_probe` on emulator and Xiaomi; `ImageDecoder` EXIF behaviour and direct-buffer behaviour; `sodium_malloc` 5 to 32 MiB on both; trial build with `camera-video` excluded (does `PreviewView` start?) | Report, continue |
| B | `docs/PHASE3B_DESIGN.md`: viewer pipeline, gallery rules and size cap, orientation rule from the probe, ABI, delete semantics, dependency route, heap investigation plan, test plan; threat model deltas | **Stop for approval** |
| C | Core additions, T1 tests | T1 green, Debug and Release |
| D | Gallery, viewer, orientation, bitmap clearing, delete photo, dependency cleanup; T2 | T2 green on emulator |
| E | T3 on Xiaomi, heap investigation, leak scan, docs, `docs/PHASE3B_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3B_PROGRESS.md` current after each milestone.

## 7. Tests

**T1 (Windows):** all 16 existing tests. `pv_object_plaintext_size` exact for empty, 1 byte, chunk boundaries, multi-chunk, and a 100 MB object without decrypting. `pv_vault_decrypt_to_buffer`: round trip, buffer too small, tamper and truncation fail with the buffer zeroed. `pv_secure_alloc`/`free` argument checks. Delete: object removed, not found, partial removed. All golden vectors.

**T2 (emulator):** all 53 existing tests. Synthetic JPEG fixtures for every orientation case; decoded size bounded by target size; bitmaps erased and recycled after `onStop` and after lock; viewer and gallery windows carry `FLAG_SECURE`; lock during a slowed decrypt discards the result and wipes the buffer; size cap, unknown item, and damaged tiles; delete and not-found; `leak_scan` before and after a viewer session that opens every photo and scrolls the gallery (no new files, zero JPEG or MP4 markers, zero app-owned MediaStore rows; account for the test runner's `additional_test_output` directory as in 3A); release and benchmark APKs free of media3 and `camera-video` classes; merged manifests per variant; Phase 2 video prototype test on debug.

**T3 (Xiaomi, developer taps installs; benchmark build for timings and daily use):**
- Gallery of the 55-object vault: photos upright in portrait and landscape; synthetic objects show as large or unknown tiles.
- Open, zoom, delete one test photo.
- Home, screen off, MIUI camera case while the viewer is open: locked, nothing restored.
- Recents card blank on gallery and viewer.
- Peak memory while viewing a 3264x2448 photo and while scrolling the gallery.
- Unlock time on the benchmark build at the final KDF default (ops 2 / 256 MiB).
- Capture tests on the benchmark build after the dependency cleanup.
- Heap investigation (4.7).
- `leak_scan`: zero findings.

## 8. Acceptance criteria

1. Phase 1, 2, and 3A T1 and T2 suites pass.
2. Photos display upright on emulator and Xiaomi under one documented rule.
3. Viewer and thumbnails decrypt and decode in memory only; buffers wiped after decode; bitmaps erased on stop and lock.
4. Objects above the size cap are never decrypted for display; unknown and damaged items are handled without crashing.
5. `FLAG_SECURE` on every window; recents blank on the Xiaomi for gallery and viewer.
6. `leak_scan` clean after viewer sessions on emulator and Xiaomi.
7. Delete one photo works; interrupted delete leaves a consistent vault.
8. `camera-video` absent from every variant; release and benchmark contain no media3; debug on one media3 version; manifests unchanged; APK sizes recorded; capture still works on both devices.
9. Heap investigation result recorded with evidence.
10. C ABI changes additive; all golden vectors decrypt on both platforms.
11. Threat model updated: decoder and GPU memory, size cap, delete is not erasure, heap finding.
12. Report separates demonstrated facts from assumptions.

## 9. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. No system-wide installs, no Android Studio upgrade, no SDK installs beyond what is needed, without asking.
4. No changes to phone settings. Phone installs need the developer's tap; ask when needed. Delete only test photos on the phone; no vault erase without agreement.
5. No home-grown cryptography. No change to v1 objects, the item header, or envelope providers 1 to 3.
6. Test captures use neutral scenes only. Throwaway PINs only; no PIN, key bytes, image bytes, or hashes of real captures in logs, files, reports, or the repository.
7. Heap dumps, traces, and decrypted buffers never go into the repository, logs, or reports; store on D: outside the repository and delete after analysis.
8. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
9. **Code:**
   - No redundant `#include` lines for standard headers already available through the project's own headers.
   - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
10. No 3C or later work (video playback, index format, video capture feature). Leave seams and document them.
11. If a device or dependency is unavailable, continue and report the exact blocker.
12. Before finishing, run `git status` and `git diff`; no secrets, build output, heap dumps, captured images, or large files in the tree.

## 10. Report (`docs/PHASE3B_TEST_REPORT.md` and final message)

Status; environment; architecture changes; dependency route taken and APK sizes; exact commands; T1, T2, T3 tables; orientation probe results per device; viewer and gallery memory per device; benchmark unlock time at the final KDF default; heap investigation result; defects with decision IDs; known limits; acceptance checklist with evidence; files changed; suggested commit message.
