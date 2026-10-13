# PrivateVault roadmap

Condensed from `PROJECT_HANDOFF.md` Section 3. Phase 1 is the only active phase.

| Phase | Scope | Status |
|---|---|---|
| 1 | Encrypted storage core (C++20, libsodium), desktop CLI, Android proof of concept, tests, documentation | Active |
| 2 | In-app camera capture written straight into encrypted objects; residual plaintext audit | Not started |
| 3 | Protected in-app viewer, `FLAG_SECURE`, lock on background, video seeking approach | Not started |
| 4 | Temporal interference experiment against external cameras; measured, not promised | Not started |
| 5 | Permission-based sharing between devices, streaming from the source device, revocation, local access log | Not started |
| 6 | Security review, hardening, backup and migration design, iOS port, beta | Not started |

Seams left in Phase 1 for later phases:

- `suite_id` in the object header lets Phase 3 add a seekable video format without breaking version 1 objects.
- Per-object data keys wrapped by the vault master key allow Phase 5 per-object sharing and Phase 6 per-object erasure.
- The core takes the vault master key as bytes, so new key providers (iOS Keychain, recovery schemes) do not touch the object format.
- The C ABI is the only surface platforms use, so an iOS client can call it from Swift.
