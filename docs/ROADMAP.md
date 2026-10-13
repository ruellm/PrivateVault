# PrivateVault roadmap

Condensed from `PROJECT_HANDOFF.md` Section 3. Phase 2 is the active phase.

| Phase | Scope | Status |
|---|---|---|
| 1 | Encrypted storage core (C++20, libsodium), desktop CLI, Android proof of concept, tests, documentation | Complete |
| 2 | In-app photo capture written straight into encrypted objects; plaintext exposure audit; video feasibility prototype | Active |
| 3 | Protected in-app viewer, `FLAG_SECURE`, lock on background, video seeking approach; in-app lock decision at the start (P2-5) | Not started |
| Video | Full video capture: viewfinder mirror, native segment buffering, index and recovery rules, audio; recommended as its own phase or the second half of Phase 3 (`docs/VIDEO_CAPTURE_FEASIBILITY.md` Section 6) | Not started |
| 4 | Temporal interference experiment against external cameras; measured, not promised | Not started |
| 5 | Permission-based sharing between devices, streaming from the source device, revocation, local access log | Not started |
| 6 | Security review, hardening, backup and migration design, iOS port, beta | Not started |

Seams left in Phase 1 for later phases:

- `suite_id` in the object header lets Phase 3 add a seekable video format without breaking version 1 objects.
- Per-object data keys wrapped by the vault master key allow Phase 5 per-object sharing and Phase 6 per-object erasure.
- The core takes the vault master key as bytes, so new key providers (iOS Keychain, recovery schemes) do not touch the object format.
- The C ABI is the only surface platforms use, so an iOS client can call it from Swift.

Seams left in Phase 2:

- The media item header (`docs/PHASE2_DESIGN.md` Section 3) reserves media types 2 and 3 for video segments and indexes and has 8 reserved bytes; readers skip unknown trailing header bytes, so a later header version can grow.
- `VideoProbes` is the hook where the debug-only video prototype plugs in; a production video path replaces it.
- The in-app lock is decided at the start of Phase 3, before any viewer (P2-5).
