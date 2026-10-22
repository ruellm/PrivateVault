# PrivateVault Stage 3F Brief: Audio in Video Recording and Playback

**Document role:** Implementation brief for Stage 3F, the last stage of Phase 3. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3F matters, this brief wins. Baseline: Phase 1 and 2 records, Stage 3A to 3E records (`docs/PHASE3E_DESIGN.md`, `docs/PHASE3E_TEST_REPORT.md`), `docs/DECISIONS.md` D-001 to D-043, `docs/ROADMAP.md` (the 3F items).

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`), JDK `D:\tools\jdk-21` through `JAVA_HOME` in scripts. Everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Decisions continue from D-044.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| 3A to 3E | PIN lock, photo viewer, video index, playback, video capture (done) |
| **3F** | **Audio: microphone, AAC track, A/V sync, playback audio; 3E carry-overs (this brief)** |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

## 2. Starting point (verified, do not re-litigate)

- 3E capture: live viewfinder through a public `CameraEffect`; H.264 at 1920x1080, 30 fps, 8 Mbit/s on the Xiaomi (closest-lower fallback elsewhere); one stop path for stop, lock (bounded at 4 s), low space, encoder error, and full queue.
- Own fragmented MP4 writer in the core: H.264 only, each segment assembled in guarded memory with `tfdt`, `sidx`, true durations, `tkhd` rotation, microsecond timescale; encrypted straight from guarded memory. T1 box-level golden vectors.
- Index v1 has audio fields in its header, unused so far.
- Viewer: per-segment `tfdt` detection; new clips through the standard extractor; the two regression clips through the 3D wrapper. Read-ahead one to two segments.
- Manifests today: release and benchmark `CAMERA` only; only `LockActivity` exported.
- Phone vault: 49 photos, 4 Phase 1 samples, two regression clips, one 3E clip (`9a9eba86...`) without audio.
- Open from 3E: the compatibility gate on the phone (both readers on real clips) was not run; the front camera was not recorded.

## 3. Decisions

### 3.1 Settled by the developer

| ID | Decision |
|---|---|
| P3F-1 | Audio is on by default, as in an ordinary camera app. A mute toggle on the capture screen turns it off for the next recording. |

### 3.2 Defaults (confirm or change at the checkpoint)

| ID | Decision | Default |
|---|---|---|
| P3F-2 | Permission | `RECORD_AUDIO` requested at runtime the first time video mode is used. If denied, video records without audio and the screen shows "no audio" on the record button; ask again only from a settings tap, never in a loop. |
| P3F-3 | Audio format | AAC-LC through the platform `MediaCodec` encoder, 48 kHz, stereo where the device offers it, else mono, 128 kbit/s. Source `MediaRecorder.AudioSource.CAMCORDER`. Milestone A measures what both devices accept. |
| P3F-4 | Container | The own fMP4 writer gains one AAC track: `mp4a` sample entry with `esds`, a second `traf` per `moof`, samples interleaved by time. Segments are still cut on video key frames; audio samples go to the segment their time falls in. |
| P3F-5 | Index | Fill the audio fields already defined in index v1 (codec, sample rate, channels). No layout change. If a field the design needs is missing, stop at the checkpoint. |
| P3F-6 | A/V sync | One time base for both tracks: camera frame timestamps and `AudioRecord.getTimestamp` on the same clock (check the camera's `SENSOR_INFO_TIMESTAMP_SOURCE`). Target: audio within 45 ms ahead and 125 ms behind video (ITU-R BT.1359 detectability), measured on the phone. |
| P3F-7 | Interruptions | A phone call, loss of the microphone, or an audio encoder error stops the recording through the 3E stop path, keeping and indexing what was encrypted. |
| P3F-8 | Playback | ExoPlayer plays the audio track. A mute button in the viewer; the system volume keys control loudness. Audio focus requested on play; playback pauses on focus loss and when headphones are unplugged. Clips without audio play silent with no error. |

## 4. Architecture

### 4.1 Capture

- `CaptureActivity` video mode: mute toggle (on by default, P3F-1), a microphone indicator while recording, the "no audio" state when the permission is denied.
- `AudioRecord` on its own thread feeds the AAC encoder; encoder output goes to the same per-segment writer as the video, through the native channel into guarded memory. No Java copy of encoded audio beyond the encoder's own buffers (record what the platform holds).
- The recording gate, stop path, and lock bound of 3E cover the audio thread too: stop drains the audio encoder to the last sample before the final segment closes.
- Muted recording: no `AudioRecord` is opened, so the system microphone indicator does not light; the clip has no audio track.

### 4.2 Container and core

- Writer additions in the core: AAC track in `moov` (`trak` with `mp4a`/`esds`, `mvex/trex`), per-fragment audio `traf` with `tfdt` and `trun`, `sidx` covering the fragment. Microsecond timescale for both tracks, or the audio sample rate for the audio track if a reader requires it (decide from the Milestone A trial).
- T1 golden vectors for an init segment and one fragment with both tracks.
- Index v1 audio fields filled from the encoder output format.

### 4.3 Playback

- New clips with audio play through the standard extractor with both renderers. Old clips (the two regression clips and the 3E clip) play as before, silent.
- Mute button, focus handling, and headphone unplug per P3F-8. Lock and `onStop` release the player as in 3D.

### 4.4 Plaintext locations (additions)

`AudioRecord` buffers, the AAC encoder input and output buffers (platform), the guarded segment buffer (app, wiped), ExoPlayer's audio sample queue and the audio track (platform). Threat model records which the app wipes.

## 5. 3E carry-overs

1. **Compatibility gate on the phone.** A debug-only scripted action decrypts the segments of a named clip and runs the framework `MediaExtractor` and media3 `FragmentedMp4Extractor` over each, reporting per track the sample count, sync count, and sync times against the index. Run on the kept 3E clip (`9a9eba86...`) and on every 3F clip.
2. **Front camera.** Record one clip with the front camera on the Xiaomi; check orientation (and mirroring as the platform presents it) by eye.

## 6. Failure handling

| Situation | Behaviour | Test |
|---|---|---|
| Permission denied | Video without audio, "no audio" shown, no repeated prompt | T2 |
| Permission revoked while the app runs | Next recording records without audio | T2 (`pm revoke`) |
| Phone call or microphone taken during recording | Stop through the 3E path, clip kept | T2 (audio error hook), T3 by hand if practical |
| Audio encoder error | Same | T2 (hook) |
| Lock during recording | 3E graceful stop including the audio drain, within the 4 s bound | T2, T3 |
| Audio focus lost during playback | Pause | T2 |
| Headphones unplugged during playback | Pause | T3 if headphones are at hand, else not verified |
| Clip without audio | Plays silent, no error | T2, T3 |

## 7. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 green; audio formats accepted on both devices; timestamp sources of camera and `AudioRecord`; a trial clip with audio from the own writer through both readers and ExoPlayer; first A/V offset measurement | Report, continue |
| B | `docs/PHASE3F_DESIGN.md`: capture flow with permission and mute, audio thread and drain, writer additions, index fields, sync method and target, interruption rules, playback controls and focus, test plan, threat model deltas | **Stop for approval** |
| C | Core writer additions, T1 golden vectors | T1 green, Debug and Release |
| D | Capture and playback features, readback action, T2 | T2 green on emulator |
| E | T3 on Xiaomi, docs, `docs/PHASE3F_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3F_PROGRESS.md` current after each milestone.

## 8. Tests

**T1 (Windows):** all 19 existing tests; writer with an AAC track: init segment and fragment golden vectors, interleaving by time, segment split of audio samples at a video key frame, `sidx` covering both tracks.

**T2 (emulator):** all 72 existing tests. Record with audio (emulator microphone), mute toggle on and off, permission denied and revoked; both readers see two tracks per segment with counts equal to the index; playback with audio, mute in the viewer, focus loss pauses; old clips play silent; lock during recording drains audio and saves the clip; interruption hooks; `FLAG_SECURE`; `leak_scan` clean; release and benchmark manifests `CAMERA` and `RECORD_AUDIO` only, `LockActivity` the only export. No frame-rate or sync assertion on the emulator.

**T3 (Xiaomi; Claude Code runs the scripts and tells the developer what to tap):**
- Install the debug build; grant the microphone when asked (developer's tap).
- Readback action on the kept 3E clip: both readers, counts equal to the index.
- Record with audio, back camera; readback on it.
- A/V sync check: the developer claps once in view of the camera; Claude Code measures the offset between the clap frame and the audio peak; the developer also confirms by eye and ear on playback.
- Record muted; check the clip is silent and the system microphone indicator did not light.
- Record with the front camera; developer checks orientation by eye.
- Install the benchmark build (vault kept); play with audio, mute, volume keys; old clips silent.
- Lock during recording (Home, screen off, MIUI camera): clip saved with audio up to the stop.
- Recents card blank while recording and playing.
- `leak_scan`: zero findings.
- Cleanup through the app: delete test clips except the two regression clips, the 3E clip, and one 3F clip with audio.

## 9. Acceptance criteria

1. Phase 1, 2, and 3A to 3E T1 and T2 suites pass.
2. Audio records by default; mute and denied permission give clips without audio and no crash.
3. Segments carry an AAC track written by the own writer; both readers parse every segment of every new clip with counts equal to the index, on emulator and Xiaomi.
4. The 3E clip passes the same readback on the phone (3E gap closed).
5. A/V offset on the Xiaomi within the P3F-6 target, measured and confirmed by the developer.
6. Playback with audio, mute, focus loss, and old silent clips work; lock releases the player.
7. Interruptions and lock stop the recording through the 3E path and keep the clip.
8. Front camera clip recorded and checked by eye.
9. `FLAG_SECURE` while recording and playing; recents blank; `leak_scan` clean on both devices.
10. Release and benchmark manifests carry `CAMERA` and `RECORD_AUDIO` only, `LockActivity` the only export; APK size recorded.
11. Core changes additive; index v1 layout unchanged; all golden vectors pass.
12. Threat model updated: microphone, audio buffers, focus behaviour.
13. Report separates demonstrated facts from assumptions.

## 10. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. The developer works on Windows. Run phone and adb scripts yourself; tell the developer exactly what to tap on the phone and wait for "done". Do not ask the developer to run bash scripts.
4. No system-wide installs, no SDK installs beyond what is needed, without asking. Do not upgrade AGP or Gradle. Use `D:\tools\jdk-21`.
5. No changes to phone settings beyond granting the app's own microphone permission when the developer taps it. Phone installs need the developer's tap. No vault erase. Never delete the two regression clips or the 3E clip.
6. No home-grown cryptography. No change to v1 objects, the item header, envelope providers 1 to 3, or the index v1 layout.
7. Test recordings use neutral scenes. Sound in test recordings: a clap, room noise, or a spoken count; no conversations or other people's voices. Throwaway PINs only. No PIN, key bytes, sample bytes, audio bytes, or hashes of real captures in logs, files, reports, or the repository.
8. Decrypted buffers never go into the repository, logs, or reports.
9. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
10. **Code:**
    - No redundant `#include` lines for standard headers already available through the project's own headers.
    - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
11. No Phase 4 or later work. Leave seams and document them.
12. If a device or dependency is unavailable, continue and report the exact blocker.
13. Before finishing, run `git status` and `git diff`; no secrets, build output, recordings, or large files in the tree.

## 11. Report (`docs/PHASE3F_TEST_REPORT.md` and final message)

Status; environment; architecture changes; audio format and sync method with the measurements; exact commands; T1, T2, T3 tables; readback results per clip; A/V offset per device; playback figures; plaintext locations; defects with decision IDs; known limits; acceptance checklist with evidence; Phase 3 close-out summary (what Phase 3 delivered, what moves to Phase 4 to 6); files changed; suggested commit message.
