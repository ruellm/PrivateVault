# PrivateVault roadmap

Condensed from `PROJECT_HANDOFF.md` Section 3. Phase 2 is the active phase.

| Phase | Scope | Status |
|---|---|---|
| 1 | Encrypted storage core (C++20, libsodium), desktop CLI, Android proof of concept, tests, documentation | Complete |
| 2 | In-app photo capture written straight into encrypted objects; plaintext exposure audit; video feasibility prototype | Active |
| 3A | PIN lock, PIN-derived vault key (envelope provider 3), migration, lock rules, attempt delays, erase (`docs/PHASE3A_DESIGN.md`) | Complete |
| 3B | Photo gallery and viewer, delete one photo, camera-video removed from every variant (`docs/PHASE3B_DESIGN.md`) | Complete; the heap investigation moved to 3E |
| 3C | Video groundwork: key-frame investigation, index format, seeking, playback probe | Not started |
| 3D | Video playback in the viewer | Not started |
| 3E | Video capture as a feature; the capture-burst heap investigation from 3B (brief 4.7, P3-12): a CameraX-internal copy of an already-encrypted capture, freed by the garbage collector, measured here because 3E reworks the capture path (procedure in `docs/PHASE3B_DESIGN.md` Section 10, `build/p3b/phone-p3b.sh heap`) | Not started |
| Video | Full video capture: viewfinder mirror, native segment buffering, index and recovery rules, audio; recommended as its own phase or the second half of Phase 3 (`docs/VIDEO_CAPTURE_FEASIBILITY.md` Section 6) | Not started |
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
