# PrivateVault roadmap

Condensed from `PROJECT_HANDOFF.md` Section 3. Phase 3 is complete with Stage 3F (audio); Phase 4 is next.

| Phase | Scope | Status |
|---|---|---|
| 1 | Encrypted storage core (C++20, libsodium), desktop CLI, Android proof of concept, tests, documentation | Complete |
| 2 | In-app photo capture written straight into encrypted objects; plaintext exposure audit; video feasibility prototype | Active |
| 3A | PIN lock, PIN-derived vault key (envelope provider 3), migration, lock rules, attempt delays, erase (`docs/PHASE3A_DESIGN.md`) | Complete |
| 3B | Photo gallery and viewer, delete one photo, camera-video removed from every variant (`docs/PHASE3B_DESIGN.md`) | Complete; the heap investigation moved to 3E |
| 3C | Video groundwork: key-frame investigation, index format v1, seeking, playback probe (`docs/PHASE3C_DESIGN.md`, `docs/VIDEO_PLAYBACK_PROBE.md`) | Complete |
| 3D | Video playback in the gallery and viewer, `media3-exoplayer` in the release build, first-frame thumbnails, delete with an orphan sweep (`docs/PHASE3D_DESIGN.md`) | Complete |
| 3E | Video capture as a feature, including the two Stage 3D T3 clips kept on the Xiaomi as the regression clips for the muxing change (the 3D viewer must still play them after `tfdt` or `sidx` and the container rotation are written; checkpoint decision 2026-10-20); the capture flow takes the `VideoItems.recordingGate` the Stage 3D sweep holds (`docs/PHASE3D_DESIGN.md` Section 4.6); the choice of capture resolution and frame rate (the Stage 3C probe records through a `Preview`-class surface at 1600x1200 on the Xiaomi and the camera delivered 14.2 fps there; 3E picks the size and a target frame-rate range and measures the delivered rate); the segment container written with a `tfdt` per fragment or a `sidx` and true last-sample durations, so seeks inside a segment need no extractor wrapper and container times equal the index times (`docs/PHASE3C_DESIGN.md` Section 5.1); the capture-burst heap investigation from 3B (brief 4.7, P3-12): a CameraX-internal copy of an already-encrypted capture, freed by the garbage collector, measured here because 3E reworks the capture path (procedure in `docs/PHASE3B_DESIGN.md` Section 10, `build/p3b/phone-p3b.sh heap`) | Complete (`docs/PHASE3E_DESIGN.md`, `docs/PHASE3E_TEST_REPORT.md`); the heap investigation found no retained array (route b) and the allocating method stays a Studio step |
| 3F | Audio: `RECORD_AUDIO`, an AAC track in the segment writer and the index, A/V sync, playback volume (the remainder of the full-video plan of `docs/VIDEO_CAPTURE_FEASIBILITY.md` Section 6; the viewfinder, native buffering, and index rules landed in 3E; the recovery rule for orphan segments stays a Phase 6 item). Also: the compatibility gate on the phone, left open in Stage 3E (`docs/PHASE3E_TEST_REPORT.md` T3), is closed in Stage 3F: a debug readback action runs both readers (framework `MediaExtractor` and media3 `FragmentedMp4Extractor`) on the kept 3E clip (`9a9eba86...`) and on the 3F clips | Complete (`docs/PHASE3F_DESIGN.md`, `docs/PHASE3F_TEST_REPORT.md`); the 3E gate closed on the phone; Phase 3 complete |
| 4 | Temporal interference experiment against external cameras; measured, not promised | Not started |
| 5 | Permission-based sharing between devices, streaming from the source device, revocation, local access log; strip EXIF (or rewrite a minimal header) before sharing, since captures carry make, model, and an embedded thumbnail inside the payload (Stage 3B finding) | Not started |
| 6 | Security review, hardening, backup and migration design, iOS port, beta | Not started |

Seams left in Phase 1 for later phases:

- `suite_id` in the object header lets Phase 3 add a seekable video format without breaking version 1 objects.
- Per-object data keys wrapped by the vault master key allow Phase 5 per-object sharing and Phase 6 per-object erasure.
- The core takes the vault master key as bytes, so new key providers (iOS Keychain, recovery schemes) do not touch the object format.
- The C ABI is the only surface platforms use, so an iOS client can call it from Swift.

Hardening items recorded for Phase 6:

- R8 (minify and shrink) for the release and benchmark variants, with keep rules for the JNI
  entry points of `NativeBridge`, the reflection in `PvApp`, and the CameraX consumer rules;
  measure APK size and startup before and after (Stage 3B checkpoint, CP-8).

Seams left in Phase 2:

- The media item header (`docs/PHASE2_DESIGN.md` Section 3) reserves media types 2 and 3 for video segments and indexes and has 8 reserved bytes; readers skip unknown trailing header bytes, so a later header version can grow.
- `VideoProbes` is the hook where the debug-only video prototype plugs in; a production video path replaces it.
- The in-app lock is decided at the start of Phase 3, before any viewer (P2-5).
