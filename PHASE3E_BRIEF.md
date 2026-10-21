# PrivateVault Stage 3E Brief: Video Capture as a Feature

**Document role:** Implementation brief for Stage 3E. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3E matters, this brief wins. Baseline: Phase 1 and 2 records (`docs/VIDEO_CAPTURE_FEASIBILITY.md`), Stage 3A to 3D records, `docs/DECISIONS.md` D-001 to D-036, `docs/ROADMAP.md` (the 3E items collected in 3B to 3D).

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`), JDK `D:\tools\jdk-21` through `JAVA_HOME` in scripts. Everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Decisions continue from D-037.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| 3A to 3D | PIN lock, photo viewer, video index, video playback (done) |
| **3E** | **Video capture in the release app, own muxing step, heap investigation (this brief)** |
| 3F | Audio: `RECORD_AUDIO`, AAC track, A/V sync, playback volume |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

No audio in 3E. `RECORD_AUDIO` is not requested. Leave seams for 3F (an audio track slot in the muxing step and the index, which already has audio fields).

## 2. Starting point (verified, do not re-litigate)

- Debug prototype V3b: CameraX `Preview` feeds a MediaCodec H.264 encoder surface; `FragmentedMp4Muxer` (media3 1.11.1) per 3 s segment; 1 s GOPs by time-based sync request with a 10 s backstop; segments cut on the encoder's actual key-frame output; fragment duration 1000 ms; each segment buffered in a Java array (8 MiB cap), then encrypted as one object (`media_type = 2`); index v1 (`media_type = 3`) written after the last segment. No viewfinder while recording.
- The item header carries `payload_len` at the start, so a segment must be complete before its encryption starts (D-014).
- The media3 muxer writes no `tfdt`, no `sidx`, and no rotation in the container. Playback works through the 3D extractor wrapper (fragment-table seek map, sample-time re-basing, index rotation on the track format).
- 3D: gallery video tiles, `VideoActivity`, index-first delete, unlock sweep over a list taken at its start, behind `VideoItems.recordingGate` (the recorder holds it from its first segment to the index write).
- Measured: the Xiaomi delivered 14.2 fps at the first prototype size, 29 fps at the 3C settings. ExoPlayer read about 20 s ahead of a 30 s clip on the emulator, more than its 4 s load control.
- Two regression clips on the phone (`3c55a432...`, `39b46d41...`) recorded by the 3C/3D muxer. They must still play after 3E.
- Heap investigation moved from 3B: about one JPEG-sized Java array per photo capture on the Xiaomi, cause unverified. Android Studio is now Rabbit (bundled JDK 25; Gradle uses `D:\tools\jdk-21`; the IDE's Gradle JDK must point there too).

## 3. Decisions (defaults; confirm or change at the checkpoint)

| ID | Decision | Default |
|---|---|---|
| P3E-1 | Viewfinder while recording | One camera stream drawn to both the on-screen preview and the encoder surface through public CameraX API (`CameraEffect` with a `SurfaceProcessor`, OpenGL copy to two outputs). Fallback: bind the encoder surface as the `Preview` output and draw the viewfinder from it. Milestone A trials both on both devices. |
| P3E-2 | Segment buffering | Move the segment buffer from the Java heap into guarded native memory (`pv_secure_alloc`), filled by the muxer through a native-backed channel. No item header change. |
| P3E-3 | Container fixes | Rotation through the muxer's public orientation metadata (`Mp4OrientationData`, written into `tkhd`). `tfdt` per fragment through the cheapest route Milestone A proves: a post-pass over each finished segment buffer that inserts `tfdt` and fixes `trun` data offsets, or an own fragmented MP4 writer for H.264 (and AAC in 3F). Decided at the checkpoint. |
| P3E-4 | Playback of old and new clips | The viewer detects per segment whether `tfdt` is present. New clips use ExoPlayer's standard fragmented MP4 path; old clips keep the 3D wrapper. Both regression clips must play. |
| P3E-5 | Capture settings | 1920x1080 if both devices deliver at least 25 fps there, else 1280x720; 30 fps target; H.264 hardware encoder; bitrate chosen from measurements (starting point 8 Mbit/s at 1080p, 5 at 720p). Settings recorded in the index. |
| P3E-6 | Stop conditions | Stop button; lock (background, screen off, idle, manual) stops the recording gracefully: finish the current segment, encrypt it, write the index, then lock. Free space below a threshold stops it the same way. No duration limit beyond the index bounds. |
| P3E-7 | Process killed mid-recording | Segments without an index stay orphans and the unlock sweep removes them (3D rule). No recovery in 3E; recorded as a known limit and a Phase 6 item. |
| P3E-8 | Read-ahead | Bound ExoPlayer's read-ahead with a byte target on the load control (proposed: about two segments) and measure on a 60 s clip. |
| P3E-9 | Heap investigation | Run with the Android Studio profiler on the Xiaomi (debug build); fix only through a public CameraX setting with all capture tests green. |

## 4. Architecture

### 4.1 Capture screen

- `CaptureActivity` gains a photo / video mode switch, a record button, an elapsed-time label, and a recording indicator. `FLAG_SECURE` as today.
- Video mode requires `UNLOCKED` and takes `VideoItems.recordingGate` for the whole recording (first segment to index write), so the sweep never runs beside it.
- The viewfinder stays live while recording (P3E-1). Switching camera during a recording is not offered.
- On stop: finish the current segment at the next key frame or after a bounded wait, encrypt it, write the index, release the gate, show the clip's duration. The gallery shows the new clip on return.

### 4.2 Recording pipeline

```
CameraX Preview -> (P3E-1 split) -> on-screen viewfinder
                                  -> MediaCodec H.264 encoder surface
encoder output -> muxer per segment -> guarded native buffer (P3E-2)
segment closed on a key frame -> container fix (P3E-3) -> encrypt as one object
after the last segment -> index v1 -> encrypt
```

- At most two finished segments wait for encryption; a third stops the recording with a clear status (as V3b's `QUEUE_FULL`), keeping what is already encrypted.
- Encryption runs on the process-scoped encrypt thread, as photo capture does.
- The segment buffer is wiped and freed after its encrypt call returns, on every path.

### 4.3 Container fixes and playback (P3E-3, P3E-4)

- New segments carry the rotation in `tkhd` and a `tfdt` in every `traf`. `sidx` only if the trial shows ExoPlayer needs it for in-segment seeking.
- The viewer chooses per segment: standard path when `tfdt` is present, the 3D wrapper otherwise. The index rotation still sets the displayed orientation when the container has none.
- The thumbnail decoder (`VideoFirstFrame`) works on both shapes.

### 4.4 Lock and failure during recording

| Situation | Behaviour |
|---|---|
| Lock during recording | Graceful stop (P3E-6): current segment finished and encrypted, index written, then lock. Bounded: if the finish exceeds a few seconds, the partial segment is dropped and the index covers the encrypted segments. |
| Free space below threshold | Graceful stop with a message. |
| Encoder or camera error | Stop; keep encrypted segments and write the index for them; message. |
| Encrypt queue full | Stop with `QUEUE_FULL`; keep and index what is encrypted. |
| Process killed | Orphan segments; removed by the next unlock sweep (P3E-7). |
| MIUI camera case | Behaves as lock. |

### 4.5 Read-ahead (P3E-8)

Byte target on the load control so the sample queue holds about two segments. Measure on a 60 s clip on both devices; report the bound reached.

### 4.6 Heap investigation (P3E-9)

As in `docs/PHASE3B_DESIGN.md` Section 10 and the 3B phone script: debug build on the Xiaomi, 10 photo captures of a neutral scene, Java allocation recording with stacks for arrays above 1 MB, heap dump before and after GC. Dumps under `D:\pv-heap`, deleted after analysis, never committed. Record the allocating class and method and whether a public setting removes it.

### 4.7 Core additions (if needed)

Only if the muxing route needs them (for example a native buffer channel or a `tfdt` post-pass in C++). Additive, portable, T1-tested. New error codes after 29.

## 5. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 green; viewfinder-plus-encoder trial (both P3E-1 routes) on emulator and Xiaomi; achievable fps at 1080p and 720p on the Xiaomi; `tfdt` trial (post-pass and own-writer sketch) with ExoPlayer seeking on the result; `Mp4OrientationData` in `tkhd` verified; native buffer channel trial | Report, continue |
| B | `docs/PHASE3E_DESIGN.md`: capture UI, pipeline, chosen split route, buffering, container route, playback detection, settings with measurements, stop and failure rules, read-ahead bound, heap plan, test plan; threat model deltas | **Stop for approval** |
| C | Core additions (if any), T1 | T1 green, Debug and Release |
| D | Capture feature in main, container fixes, viewer detection, read-ahead bound; V3b retired or kept as debug probe; T2 | T2 green on emulator |
| E | T3 on Xiaomi, heap investigation, docs, `docs/PHASE3E_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3E_PROGRESS.md` current after each milestone.

## 6. Tests

**T1 (Windows):** all 18 existing tests, plus tests for any core additions (including a box-level check of the `tfdt` post-pass if it lands in the core).

**T2 (emulator):** all 69 existing tests. Record from the capture screen with the viewfinder live; segments carry `tfdt` and the `tkhd` rotation; the clip plays through the standard path, seeks in-segment land within one GOP; a clip from the 3D muxer (fixture recorded in the test) still plays through the wrapper; lock during recording saves the clip and locks; free-space stop; encoder error hook; queue-full hook; process kill mid-recording leaves orphans that the next sweep removes; read-ahead bound on a 60 s clip; `FLAG_SECURE` on the capture screen while recording; `leak_scan` clean; release and benchmark manifests `CAMERA` only.

**T3 (Xiaomi; Claude Code runs the scripts and tells the developer what to tap):**
- Install the debug build; heap investigation (photo captures, profiler).
- Install the benchmark build over it (vault kept).
- Record by hand from the capture screen: the developer confirms the viewfinder stays live while recording and the clip plays upright and smooth.
- Record a 60 s clip; read-ahead and memory measured during playback.
- Lock during recording (Home, screen off, MIUI camera): clip saved up to the stop, vault locked.
- Both regression clips still play.
- Recents card blank while recording and while playing.
- `leak_scan`: zero findings.
- Test clips deleted at the end except the two regression clips and one 3E clip, kept for 3F.

## 7. Acceptance criteria

1. Phase 1, 2, and 3A to 3D T1 and T2 suites pass.
2. Video recording from the release-shaped capture screen with a live viewfinder on emulator and Xiaomi; developer confirms by eye on the phone.
3. Segments are never held in the Java heap; guarded buffers wiped after encryption.
4. New segments carry rotation and `tfdt`; new clips seek through the standard path; old clips still play.
5. Settings chosen from measurements on both devices and recorded in the index.
6. Lock, low space, encoder error, and queue-full stops keep and index what was encrypted; a process kill leaves orphans the sweep removes.
7. Read-ahead bounded and measured on a 60 s clip.
8. Heap investigation result recorded with evidence.
9. `FLAG_SECURE` while recording and playing; recents blank; `leak_scan` clean on both devices.
10. Release and benchmark manifests unchanged (`CAMERA` only, only `LockActivity` exported); APK size recorded.
11. Core changes additive; all golden vectors pass.
12. Report separates demonstrated facts from assumptions.

## 8. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. The developer works on Windows. Run phone and adb scripts yourself; tell the developer exactly what to tap on the phone and wait for "done". Do not ask the developer to run bash scripts.
4. No system-wide installs, no SDK installs beyond what is needed, without asking. Do not upgrade AGP or Gradle. Use `D:\tools\jdk-21`.
5. No changes to phone settings. Phone installs need the developer's tap. No vault erase on the phone. Never delete the two regression clips.
6. No home-grown cryptography. No change to v1 objects, the item header, envelope providers 1 to 3, or index v1 (an index field already defined may be filled; a layout change needs the checkpoint).
7. No audio, no `RECORD_AUDIO` (3F).
8. Test recordings and captures use neutral scenes only. Throwaway PINs only. No PIN, key bytes, sample bytes, image bytes, or hashes of real captures in logs, files, reports, or the repository.
9. Heap dumps, traces, and decrypted buffers never go into the repository, logs, or reports; store under `D:\pv-heap` and delete after analysis.
10. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
11. **Code:**
    - No redundant `#include` lines for standard headers already available through the project's own headers.
    - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
12. No 3F or later work. Leave seams and document them.
13. If a device or dependency is unavailable, continue and report the exact blocker.
14. Before finishing, run `git status` and `git diff`; no secrets, build output, recordings, heap dumps, or large files in the tree.

## 9. Report (`docs/PHASE3E_TEST_REPORT.md` and final message)

Status; environment; architecture changes; split route and container route chosen with the trial evidence; capture settings with measurements; exact commands; T1, T2, T3 tables; recording, playback, seek, read-ahead, and memory figures per device; heap investigation result; plaintext locations; defects with decision IDs; known limits; acceptance checklist with evidence; files changed; suggested commit message.
