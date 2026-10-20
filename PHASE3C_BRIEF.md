# PrivateVault Stage 3C Brief: Video Groundwork (Key Frames, Index, Seeking, Playback Probe)

**Document role:** Implementation brief for Stage 3C. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3C matters, this brief wins. Baseline: Phase 1 and 2 records (including `docs/VIDEO_CAPTURE_FEASIBILITY.md`), Stage 3A and 3B records, `docs/DECISIONS.md` D-001 onward.

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`). Everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Decisions continue from the next free D-number.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| 3A | PIN lock (done) |
| 3B | Photo viewer, delete photo, `camera-video` removed (done; heap check moved to 3E) |
| **3C** | **Video groundwork: key-frame investigation, index format, seeking design, debug playback probe (this brief)** |
| 3D | Video playback in the viewer |
| 3E | Video capture as a feature, heap investigation |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

Nothing in 3C ships in release. Release and benchmark builds stay free of media3.

## 2. Starting point (verified, do not re-litigate)

- Video prototype V3b (debug only): CameraX `Preview` feeds a MediaCodec H.264 encoder surface; `FragmentedMp4Muxer` (media3 1.11.x) per segment of about 3 s; each segment buffered in memory (8 MiB cap), then encrypted as one v1 object with item `media_type = 2`; a prototype index object with `media_type = 3` (D-014). No viewfinder while recording, no audio.
- Phase 2 run: 1 s I-frame interval; encoder reported 2/4/4/3 key frames per segment, `MediaExtractor` reported 2/2/2/2.
- 3B: decrypt into guarded memory (`pv_vault_decrypt_to_buffer` with a cancel flag), cancellation inside `Vault.close()` on lock, `FLAG_SECURE` on all windows, in-activity confirmations only. On the Xiaomi the app process can lock up to 64 MiB; on the emulator the lock is refused.
- `camera-video` removed from every variant. Debug carries media3 on one 1.11.x version; release and benchmark carry none.
- Item header (48 bytes) carries `payload_len` at the start, so a segment must be complete before its encryption starts (D-014).

## 3. Research findings (from the Phase 3 planning research)

**Verified from library source and documentation:**

1. `FragmentedMp4Muxer` default fragment duration is 2000 ms. With a video track, a new fragment starts only when the next video sample carries `C.BUFFER_FLAG_KEY_FRAME` and the pending samples span at least the fragment duration.
2. The muxer defines separate sync and non-sync `trun` sample-flag values and builds per-sample metadata from each sample's `BufferInfo.flags`.
3. media3 `FragmentedMp4Extractor` has a flag that forces sync flags to false except for the first sample in each segment, a known workaround for streams that mark every frame as sync. Extractors treat fragment-first samples specially.

**Working hypotheses for the mismatch (rank, then prove or reject with logs):**

- **H1:** the framework `MediaExtractor` reports sync only for fragment-first samples, regardless of per-sample `trun` flags. With 2 s fragments in 3 s segments that gives 2 per segment, which matches the observation.
- **H2:** the muxer writes per-sample flags that mark only the fragment-first sample as sync.
- **H3:** the flags passed to the muxer differ from the encoder's (a conversion that drops or rewrites them). The extra encoder key frame (4 in a 3 s segment at a 1 s interval) is likely the sync request at each segment start.
- **H4:** a difference between media3 versions.

## 4. Objective

1. Explain the key-frame count mismatch from logs and box-level evidence.
2. Settle segment length, fragment duration, and I-frame interval for the video phase.
3. Define index format v1 and the seek design, and implement the index encode and decode in the core (Section 6).
4. Prove playback of encrypted segments with media3 ExoPlayer from decrypted memory, including segment boundaries, seeking, lock behaviour, and the plaintext locations.

## 5. Key-frame investigation (debug only)

1. Record fresh clips with V3b on the emulator and the Xiaomi, neutral scene, 10 to 15 s each.
2. Log every encoder output buffer: PTS, size, `KEY_FRAME`, `CODEC_CONFIG`.
3. Log every `writeSampleData` call: PTS and flags as passed to the muxer.
4. Decrypt each segment into guarded memory and walk its boxes with a debug-only parser:
   - `moof` count;
   - per `trun`: flags field, sample count, `first_sample_flags` if present, per-sample `sample_is_non_sync_sample`;
   - `tfhd` default sample flags, `trex` defaults.
5. Count sync samples with the framework `MediaExtractor` and with media3 `FragmentedMp4Extractor` on the same bytes.
6. Repeat with fragment duration 500, 1000, 3000 ms and I-frame interval 1 s and 2 s.
7. Record which hypothesis the evidence supports. Do not report a cause the logs do not show.

Logs carry flags, sizes, timestamps, and counts only; no sample bytes.

## 6. Index format v1 and seek design (Proposed, finalized at the checkpoint)

### 6.1 Proposed direction

- Seeking does not rely on the container's sync table. The index records key-frame times taken from encoder output.
- Each segment starts on a requested sync frame. Fragment duration is set no longer than the I-frame interval, so each GOP starts its own fragment (confirm with Section 5 evidence).
- The index is one encrypted object, item `media_type = 3`, written after the last segment. All index content is inside the encrypted payload.

### 6.2 Proposed content (layout settled at the checkpoint)

- Index header: magic, version, video codec, width, height, rotation, total duration, segment count, creation time.
- Per segment: object ID, start PTS, duration, byte length, key-frame count, then the key-frame PTS list (or offsets from the segment start).
- Room for an audio track description later (3E), without a version change if possible.

### 6.3 Seek rule (proposed)

Seek to time T: find the segment whose range holds T, decrypt it, seek inside it to the latest key frame at or before T, decode forward to T. Seek granularity is one GOP; worst-case work is one segment decrypt plus one GOP decode.

### 6.4 Core work

- `pv_video_index_encode` and `pv_video_index_decode` in the core, portable, same style as the item header: strict bounds, every field validated, unknown versions rejected.
- T1 tests and golden vectors for the index.
- The Phase 2 prototype index objects are not migrated; they show as unknown items, as today.

## 7. Playback probe (debug only)

- `media3-exoplayer` added to the debug variant only, same pinned media3 version. Verify the merged debug manifest and record anything it adds; release and benchmark unchanged.
- Debug activity `VideoProbeActivity`, `FLAG_SECURE`, output to a `SurfaceView` (also call `SurfaceView.setSecure(true)`).
- A custom `DataSource` serves one segment from a decrypted guarded buffer (3B pipeline). Segments play as a playlist built from the index. A segment buffer is wiped and freed once ExoPlayer has moved past it; at most the current and next segment are decrypted at once.
- Measure on emulator and Xiaomi:
  - time to first frame;
  - gap or stall at each segment boundary (frame timestamps around transitions);
  - seek latency for 10 random positions, both segment-start and within-segment;
  - peak native and Java memory, decrypted bytes held at once.
- Lock rules: background, screen off, idle, or manual lock stops playback, releases the player, wipes buffers, cancels any decrypt in flight.
- List every place plaintext lives during playback (DataSource buffer, extractor and sample buffers in the Java heap, codec input buffers, output surface) and which the app can wipe.
- `leak_scan` after the probe. Screen capture and recents card during playback must be black.

## 8. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 green; fresh clips on emulator and Xiaomi; Section 5 investigation complete | Report, continue |
| B | `docs/PHASE3C_DESIGN.md`: key-frame findings and cause, recommended segment length, fragment duration, I-frame interval; index format v1 byte layout; seek rule; playback probe plan; test plan; threat model deltas | **Stop for approval** |
| C | Core index encode and decode, T1 tests, golden vectors | T1 green, Debug and Release |
| D | V3b updated to the approved settings and to write index v1; playback probe; T2 | T2 green on emulator |
| E | T3 on Xiaomi, `docs/VIDEO_PLAYBACK_PROBE.md`, `docs/PHASE3C_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3C_PROGRESS.md` current after each milestone.

## 9. Tests

**T1 (Windows):** all 17 existing tests. Index encode and decode round trip; every field rejection (magic, version, counts, out-of-range times, key-frame list not increasing, lengths past the buffer); zero segments rejected; large segment and key-frame counts within the documented bounds; golden vectors.

**T2 (emulator):** all 63 existing tests. Record, index, and play back a short clip; boundary gaps measured; seek to segment start and within a segment lands within one GOP; lock during playback releases the player and wipes buffers; lock during a decrypt cancels it; `FLAG_SECURE` on the probe window; `leak_scan` clean; release and benchmark APKs still contain no media3 classes.

**T3 (Xiaomi, developer taps installs; Claude Code runs the scripts and says when to tap):**
- Record clips at the approved settings; key-frame counts from encoder, container, and index agree.
- Play back, seek, measure, as in Section 7, on the hardware decoder.
- Home, screen off, and MIUI camera case during playback: playback stops, lock screen on return.
- Recents card black during playback.
- `leak_scan`: zero findings.

## 10. Acceptance criteria

1. Phase 1, 2, 3A, 3B T1 and T2 suites pass.
2. The key-frame mismatch is explained with log and box evidence.
3. Segment length, fragment duration, and I-frame interval are chosen with measurements on both devices.
4. Index format v1 is documented, implemented in the core, and covered by T1 tests and golden vectors.
5. Playback from decrypted memory works on emulator and Xiaomi, with boundary gaps, seek latency, and memory measured.
6. Lock during playback stops it, releases the player, and wipes app-owned buffers.
7. Plaintext locations during playback listed, with which ones the app can wipe.
8. `FLAG_SECURE` holds during playback; `leak_scan` clean on both devices.
9. Release and benchmark builds unchanged: no media3, manifests unchanged.
10. Report separates demonstrated facts from assumptions.

## 11. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. The developer works on Windows. Run phone and adb scripts yourself; tell the developer exactly what to tap on the phone and wait for "done". Do not ask the developer to run bash scripts.
4. No system-wide installs, no SDK installs beyond what is needed, without asking. Do not upgrade AGP or Gradle.
5. No changes to phone settings. Phone installs need the developer's tap. No vault erase on the phone; delete only test recordings, with agreement.
6. No home-grown cryptography. No change to v1 objects, the item header, or envelope providers 1 to 3.
7. Test recordings use neutral scenes only. Throwaway PINs only. No PIN, key bytes, sample bytes, or hashes of real captures in logs, files, reports, or the repository.
8. Decrypted buffers never go into the repository, logs, or reports.
9. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
10. **Code:**
    - No redundant `#include` lines for standard headers already available through the project's own headers.
    - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
11. No 3D or later work (viewer video playback in release, video capture feature, audio). Leave seams and document them.
12. If a device or dependency is unavailable, continue and report the exact blocker.
13. Before finishing, run `git status` and `git diff`; no secrets, build output, recordings, or large files in the tree.

## 12. Report (`docs/PHASE3C_TEST_REPORT.md` and final message)

Status; environment; key-frame findings with the hypothesis supported; chosen video settings with the measurements behind them; index format v1 summary; exact commands; T1, T2, T3 tables; playback measurements per device; plaintext locations; defects with decision IDs; known limits; acceptance checklist with evidence; files changed; suggested commit message.
