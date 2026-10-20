# PrivateVault Stage 3D Brief: Video Playback in the Viewer

**Document role:** Implementation brief for Stage 3D. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3D matters, this brief wins. Baseline: Phase 1 and 2 records, Stage 3A to 3C records (`docs/PHASE3C_DESIGN.md`, `docs/VIDEO_PLAYBACK_PROBE.md`, `docs/PHASE3C_TEST_REPORT.md`), `docs/DECISIONS.md` D-001 to D-031.

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`), JDK `D:\tools\jdk-21` through `JAVA_HOME` in scripts. Everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Decisions continue from D-032.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| 3A to 3C | PIN lock, photo viewer, video index and playback probe (done) |
| **3D** | **Video playback in the gallery and viewer, release build (this brief)** |
| 3E | Video capture as a feature, heap investigation |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

## 2. Starting point (verified, do not re-litigate)

- Index v1 in the core: encode, decode, locate (codes 27 to 29). An index is an item with `media_type = 3`; segments are items with `media_type = 2`.
- Debug playback probe over ExoPlayer: guarded segment data source, seek map built from the fragment table, extractor wrapper that re-bases sample times after a seek. Needed because the media3 muxer writes no `tfdt` and no `sidx`; recordings made before 3E have neither.
- Probe results on the Xiaomi: first frame 327 to 542 ms, one-frame boundary gaps, 10 of 10 seeks exact, one guarded buffer live at a time, lock cases release the player.
- 3B viewer pipeline: decrypt into guarded memory with a cancel flag, cancellation inside `Vault.close()`, `FLAG_SECURE`, in-activity confirmations, gallery tiles (photo, large, unknown, damaged).
- Release and benchmark carry no media3 today. R8 is off (D-024 area, hardening item).
- The phone vault holds 49 photos and 4 Phase 1 samples; no video objects. Recordings for testing come from the debug V3b prototype (Section 7).

## 3. Decisions (defaults; confirm or change at the checkpoint)

| ID | Decision | Default |
|---|---|---|
| P3D-1 | Player library in release | `media3-exoplayer` only, pinned to the debug version. No `media3-ui`; own controls. Manifest merge rules strip anything it adds; release manifest stays `CAMERA` only. |
| P3D-2 | Gallery representation | One tile per index object, showing a play mark and the duration. Segment objects are hidden from the gallery. |
| P3D-3 | Video thumbnail | First key frame of the first segment, decoded in memory to 256 px, same cache and erase rules as photo thumbnails. A generic video tile if the decode fails. |
| P3D-4 | Controls | Tap to play or pause, seek bar, elapsed and total time. No speed control, no audio (3E). |
| P3D-5 | Delete a video | Delete the index first, then each segment. Segments that no index references are orphans and are removed by a sweep at unlock. |
| P3D-6 | Seek handling | Carry the probe's fragment-table seek map and time re-basing into the main source set. Seeks go through `pv_video_index_locate`. |

## 4. Architecture

### 4.1 Player pipeline

- `VideoActivity` (not exported, routes to the lock screen unless `UNLOCKED`, `FLAG_SECURE`), output to a `SurfaceView` with `setSecure(true)`.
- Load the index object through the 3B pipeline, decode it with the core, then build an ExoPlayer playlist with one item per segment over the guarded segment data source (CP-6 of 3C).
- At most the current and next segment decrypted at once. A segment buffer is wiped and freed once playback has moved past it, on seek away, on pause timeout if the design proposes one, and on release.
- Lock (background, screen off, idle, manual) and activity `onStop`: stop, release the player, wipe buffers, cancel any decrypt in flight. The idle timer counts playback as activity, so a video longer than the idle timeout does not lock mid-play (design states the rule).

### 4.2 Gallery

- Index objects become video tiles (P3D-2, P3D-3). Segment objects are not listed.
- An index that fails to decode, or that names a segment that is missing or damaged, shows a damaged tile with the reason. Playback of a damaged video stops at the first bad segment with an error, never shows partial output from an unverified segment.
- Prototype indexes from Phase 2 (none remain on the phone) stay unknown items.

### 4.3 Thumbnails

- Decrypt the first segment into guarded memory, extract the first key frame with MediaCodec into an `ImageReader` or a software bitmap at 256 px, wipe and free the buffer. Same thumbnail cache, erased on `onStop` and on lock.
- Thumbnail work runs on the gallery's background thread and is cancelled by the lock like photo work.

### 4.4 Delete

- From the video viewer, behind an in-activity confirmation (P3D-5).
- Order: index, then segments. An interrupted delete leaves orphan segments and no index, so nothing appears in the gallery; the unlock sweep removes them. The sweep only deletes segment objects that no surviving index names.
- Scripted debug action `delete_video=<index id>`.

### 4.5 Core additions (if needed)

Additive only. Candidates: a call that lists segment IDs referenced by an index (for the sweep), or a helper that maps a time to a segment and key-frame offset beyond `locate`. Settle at the checkpoint. New error codes after 29.

### 4.6 Plaintext locations

Same list as the probe (guarded segment buffer, extractor and sample buffers in the Java heap, codec input buffers, output surface), plus the video thumbnail bitmap. The threat model states which the app wipes.

## 5. Failure handling

| Situation | Behaviour | Test |
|---|---|---|
| Lock during playback | Stop, release, wipe, lock screen | T2, T3 |
| Lock during thumbnail decode | Cancelled, buffer wiped | T2 |
| Missing or damaged segment | Damaged tile or playback stops at that segment with an error; no output from an unverified segment | T2 |
| Index damaged | Damaged tile | T1, T2 |
| Delete interrupted | No gallery entry; orphans removed at next unlock | T2 |
| Decoder error | Error shown, player released, buffers wiped | T2 (test hook) |
| Out of memory | Error shown, buffers wiped, app usable | T2 (test hook) |

## 6. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 green; dependency tree and merged manifests with `media3-exoplayer` in release (trial); APK size before and after; MediaCodec first-frame thumbnail trial on both devices | Report, continue |
| B | `docs/PHASE3D_DESIGN.md`: player pipeline, buffer lifetime, idle rule during playback, gallery and thumbnail rules, delete order and sweep, any core additions, manifest and dependency plan, test plan; threat model deltas | **Stop for approval** |
| C | Core additions (if any), T1 | T1 green, Debug and Release |
| D | Video viewer, gallery tiles, thumbnails, delete, sweep, seek map moved to main; T2 | T2 green on emulator |
| E | T3 on Xiaomi, docs, `docs/PHASE3D_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3D_PROGRESS.md` current after each milestone.

## 7. Tests

**T1 (Windows):** all 18 existing tests, plus tests for any core additions.

**T2 (emulator):** all 67 existing tests. Record with V3b, then in the main viewer: tile shown with duration, thumbnail decoded and bounded; play through all segments with one-frame boundary gaps; seek to segment start and within a segment lands on the located key frame; pause and resume; lock during playback and during thumbnail decode; damaged segment and damaged index; delete and interrupted delete with the sweep; `FLAG_SECURE` on the video window; release and benchmark manifests `CAMERA` only and the APK contains only the expected media3 artifacts; `leak_scan` clean.

**T3 (Xiaomi; Claude Code runs the scripts and tells the developer what to tap):**
- Record two clips with the debug build, aimed at a neutral lit scene.
- Install the benchmark build over it (same signing key, vault kept). The developer confirms by eye that the picture shows the scene and plays smoothly.
- Gallery tile and thumbnail; play, pause, seek; first-frame time and seek latency.
- Home, screen off, MIUI camera case during playback: player released, lock screen on return.
- Recents card blank during playback.
- Delete one clip.
- Peak memory during playback.
- `leak_scan`: zero findings.

## 8. Acceptance criteria

1. Phase 1, 2, and 3A to 3C T1 and T2 suites pass.
2. Videos appear as single gallery tiles with a thumbnail and duration; segments are not listed.
3. Playback, pause, and seek work in the release-shaped build on emulator and Xiaomi; the developer confirms the picture by eye on the phone.
4. Lock during playback or thumbnail work releases the player and wipes app-owned buffers.
5. Damaged or missing segments never produce output from unverified data.
6. Delete removes index and segments; an interrupted delete leaves no gallery entry and the sweep removes orphans.
7. `FLAG_SECURE` holds during playback; recents blank; `leak_scan` clean on both devices.
8. Release and benchmark manifests unchanged (`CAMERA` only, only `LockActivity` exported); APK size recorded.
9. Core changes additive; all golden vectors pass.
10. Threat model updated: player buffers, thumbnail, idle rule during playback.
11. Report separates demonstrated facts from assumptions.

## 9. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. The developer works on Windows. Run phone and adb scripts yourself; tell the developer exactly what to tap on the phone and wait for "done". Do not ask the developer to run bash scripts.
4. No system-wide installs, no SDK installs beyond what is needed, without asking. Do not upgrade AGP or Gradle. Use `D:\tools\jdk-21`.
5. No changes to phone settings. Phone installs need the developer's tap. No vault erase on the phone; delete only test recordings.
6. No home-grown cryptography. No change to v1 objects, the item header, envelope providers 1 to 3, or index v1.
7. Test recordings use neutral scenes only. Throwaway PINs only. No PIN, key bytes, sample bytes, or hashes of real captures in logs, files, reports, or the repository.
8. Decrypted buffers never go into the repository, logs, or reports.
9. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
10. **Code:**
    - No redundant `#include` lines for standard headers already available through the project's own headers.
    - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
11. No 3E work (video capture feature, audio, own muxing with `tfdt`/`sidx`). Leave seams and document them.
12. If a device or dependency is unavailable, continue and report the exact blocker.
13. Before finishing, run `git status` and `git diff`; no secrets, build output, recordings, or large files in the tree.

## 10. Report (`docs/PHASE3D_TEST_REPORT.md` and final message)

Status; environment; architecture changes; dependency and manifest results with APK sizes; exact commands; T1, T2, T3 tables; playback, seek, and memory figures per device; plaintext locations; defects with decision IDs; known limits; acceptance checklist with evidence; files changed; suggested commit message.
