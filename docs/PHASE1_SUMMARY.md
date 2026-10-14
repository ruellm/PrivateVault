# PrivateVault Phase 1 Summary

**Status:** Complete. All acceptance criteria pass on Windows, the Android emulator, and a physical Xiaomi phone.
**Dates:** 2026-10-12 to 2026-10-13.
**Detailed record:** `docs/PHASE1_TEST_REPORT.md`, `docs/PHASE1_DESIGN.md`, `docs/DECISIONS.md`.

## What was built

1. **Portable vault engine** (`src/vault_core`, C++20 over libsodium 1.0.22)
   - Encrypts files in 64 KB chunks with bounded memory.
   - Each file becomes one `.pvo` object with its own key.
   - Fails closed on tampering, truncation, trailing data, wrong key, and malformed headers.
2. **C ABI** (`pv.h`): the single interface used by the Windows tool and Android, and later iOS.
3. **Windows CLI** (`pv.exe`): developer test harness. Password-protected vault key (Argon2id). Commands: `init`, `encrypt`, `decrypt`, `verify`, `inspect`, `inspect-file`.
4. **Android debug app** (Kotlin + JNI)
   - Vault key protected by Android Keystore (AES-256-GCM), hardware-backed on the phone.
   - App-private storage, backup disabled, no permissions.
   - Never writes plaintext to disk.
5. **Documentation:** design, threat model, dependencies, container feasibility note, decisions D-001 to D-011, progress log, test report.

## Key design decisions

- Key hierarchy: platform key -> vault master key -> per-file key.
- Object format v1: 128-byte header bound into authentication, secretstream chunks, end marker proving completeness.
- A vault is a folder (`vault.key` + `objects/*.pvo` with random names), not a container file.
- TrueCrypt/VeraCrypt rejected: no mount on unrooted Android, XTS has no integrity, works against per-item sharing, restrictive license.
- Video seeking direction for Phase 3: segment objects, no new cryptography.
- libsodium built through vcpkg for all targets, no WSL needed. NDK r29, AGP 8.8.2, minSdk 28, targetSdk 35.

## Test results

| Tier | Where | Result |
|---|---|---|
| T1 automated | Windows, Debug and Release | 11/11 pass (rerun independently by the developer) |
| CLI hand test | Windows | Exact round trip; wrong password, 1-bit tamper, and truncation all rejected with no output left |
| T2 automated | Emulator, API 35 x86_64 | 17/17 pass |
| Cross-platform | Android-made objects decrypted on Windows | Pass |
| T3 device | Xiaomi 2201116SG, Android 13, MIUI | Create, encrypt 1 MB and 100 MB, verify, force-stop, reboot, tamper, truncation, wrong key: all pass |

Bounded memory: about 4.5 MB peak working set for both 1 MB and 256 MB inputs.

## Defects found and fixed

**Design review (before implementation):**
- Short reads from the input stream were not handled.
- Test-data generator output depended on read buffer size.
- `link()` is blocked in Android app storage; replaced by checked `rename()` fallback.
- Non-ASCII paths on Windows needed wide-character APIs.
- Library could abort on init failure instead of returning an error.

**Physical device (not reproducible on the emulator):**
- **D-010:** Keystore refused lock-bound keys on a phone without a screen lock. Fixed by supporting both modes and recording which one was used.
- **D-011:** JNI vault handle misread as an error because arm64 tags heap pointers (making them negative). Fixed by returning status and handle separately.

## Known limits

- Without a screen lock, the vault protects against file copying and other apps, not against someone holding the phone. An in-app lock is planned.
- Metadata is visible: file count, sizes, timestamps.
- The Kotlin copy of the vault key cannot be reliably wiped from memory.
- A cut inside a chunk reports `AUTH_FAILED` rather than `TRUNCATED` (still fails closed).
- 100 MB timings on the phone come from a debug build and are not performance figures.
- No independent security review yet.

## Carry forward to Phase 2 planning

- Test lock-bound mode on a phone with a PIN.
- Measure performance with a release build.
- Decide the in-app lock (app PIN or biometric) and when it lands.
- Consider a length trailer for format v2.
- MIUI quirks: per-install tap; `adb uninstall` and `pm clear` do not work.
