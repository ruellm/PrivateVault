# PrivateVault Stage 3A Brief: PIN Lock and PIN-Derived Vault Key

**Document role:** Implementation brief for Stage 3A, the first of the Phase 3 stages. `PROJECT_HANDOFF.md` holds product intent and project-wide rules; where this brief differs on Stage 3A matters, this brief wins. Phase 1 and 2 records (`docs/PHASE1_*`, `docs/PHASE2_*`, `docs/DECISIONS.md` D-001 to D-015) describe the working baseline.

**Repository:** `D:\repo\PrivateVault`. SDK `D:\Android\Sdk`, AVDs `D:\Android\avd`, Gradle home `D:\Android\gradle`, vcpkg `D:\tools\vcpkg` (cache `D:\tools\vcpkg-cache`). C: is almost full: everything stays on D:.

**Execution model:** Milestones A to E, one stop at the Milestone B design checkpoint. Record non-obvious choices in `docs/DECISIONS.md` from D-016.

---

## 1. Plan context

| Stage | Scope |
|---|---|
| **3A** | **PIN lock, PIN-derived key, PIN change, migration, lock rules, attempt delays, erase vault (this brief)** |
| 3B | Photo viewer, delete photo, dependency cleanup (`camera-video` removed, one media3 line), heap check |
| 3C | Video groundwork: key-frame investigation, index format, seeking, playback probe |
| 3D | Video playback in the viewer |
| 3E | Video capture as a feature |
| 4 | Temporal interference R&D |
| 5 | Permission-based sharing |
| 6 | Hardening and review. MVP complete. |

iOS is not on the plan. The core stays portable so iOS can be added later (Section 4.4).

## 2. Starting point (verified, do not re-litigate)

- Core `src/vault_core` (C++20, libsodium 1.0.22), C ABI `pv.h`, object format v1, error codes up to 23.
- Android envelope provider 2: Keystore AES-256-GCM key wraps the VMK; lock-bound or not per `isDeviceSecure()` (D-010). The VMK crosses JNI into Kotlin (Phase 1 known limit).
- Capture: CameraX 1.6.2, process-scoped encrypt thread, bounded queue (2), `FLAG_SECURE` on the capture screen.
- Toolchain: AGP 8.9.3, Gradle 8.11.1, compileSdk 36, targetSdk 35, minSdk 28, NDK r29. Build types: debug, benchmark.
- Tests: T1 14/14, T2 31 (1 skipped PIN test passes alone), T3 pass on Xiaomi 2201116SG (Android 13, MIUI, no screen lock, Keystore `TRUSTED_ENVIRONMENT`, no StrongBox).
- The Xiaomi holds one provider 2 vault with 52 test objects.
- MIUI kills the app in the background when another camera app opens.
- A Keystore key that requires user authentication can only be created with a secure lock screen set up, and is invalidated if the lock screen is removed. Biometric and device-credential unlock are unavailable on the Xiaomi.

## 3. Decisions (final)

| ID | Decision |
|---|---|
| P3-1 | Numeric PIN, 6 to 12 digits, user's choice. Passphrase later, as an optional setting. |
| P3-2 | PIN is part of the key derivation, device-bound (Section 4.1). |
| P3-3 | No biometric. The envelope reserves a slot type for it. |
| P3-4 | Lock on background, on screen off, after 5 min idle in the foreground, on manual lock; always locked after process death. |
| P3-5 | 5 wrong attempts free, then 30 s doubling per attempt, cap 1 h. No auto-erase (later stage). |
| P3-6 | Migrate the existing provider 2 vault in place on first PIN setup. |
| P3-11 | "Forgot PIN: erase vault" on the lock screen behind typed confirmation `ERASE`. |
| P3-13 | minSdk 28, object format v1, item header unchanged. |

## 4. Architecture

### 4.1 Envelope provider 3

```
k_pin = Argon2id(PIN, salt, opslimit, memlimit)       core, crypto_pwhash
kek   = HMAC-SHA256 with a non-exportable Keystore key (k_pin)       Kotlin
VMK   = XChaCha20-Poly1305-IETF open(kek, nonce, wrapped_vmk, AD = all envelope bytes before wrapped_vmk)   core
```

- Every PIN guess needs one Argon2id run plus one Keystore call on this device. Copied files are useless off the device.
- The VMK is unwrapped in the core and never crosses JNI. `k_pin` and `kek` cross JNI as short-lived byte arrays; zero them after use and document possible GC copies.
- Keystore HMAC key: follows D-010 (unlocked-device-required only when `isDeviceSecure()` at creation). StrongBox attempted with fallback. Report the security level.
- Argon2id parameters: measured on the Xiaomi benchmark build, target 0.5 to 1.0 s, within libsodium `INTERACTIVE` to `MODERATE`. The reader enforces those bounds before running the KDF.
- Layout (finalized in the design doc): same magic and version field as providers 1 and 2; `provider_id = 3`; slot count; slots. Slot type 1 = PIN slot (Keystore alias, `opslimit`, `memlimit`, salt, nonce, wrapped VMK). Slot type 2 reserved for biometric. Reject the envelope if it has no known slot. All slots are bound into the AEAD additional data.
- This composes standard primitives; it is not reviewed. State that in `docs/THREAT_MODEL.md`, along with:
  - No hardware attempt counter exists for app Keystore keys; app-enforced delays can be reset by an attacker with root.
  - A 6-digit PIN at about 0.7 s per guess is about a week of single-threaded on-device work; each extra digit multiplies that by 10.

### 4.2 PIN change and migration (same rewrap path)

1. Obtain the VMK: from the old PIN (PIN change) or the provider 2 key (migration).
2. New salt; `k_pin` from the new PIN; new `kek`; wrap the same VMK.
3. Write `vault.key.partial`, flush, rename over `vault.key`, fsync the directory.
4. Migration only: delete the provider 2 Keystore alias after the rename.

Objects are untouched. A crash at any step leaves either the old envelope with its key or the new envelope with its key. Test every interruption point.

### 4.3 Lock state machine

States: `NO_VAULT`, `LOCKED`, `UNLOCKED`, `LOCKING`.

- `UNLOCKED` to `LOCKING` on any of:
  - process `ON_STOP` from `ProcessLifecycleOwner` (Android delays it so rotation and permission dialogs do not trigger it);
  - `ACTION_SCREEN_OFF`;
  - 5 min without user input in the foreground;
  - manual lock.
- `LOCKING`:
  1. Hide sensitive UI at once.
  2. Wait for the capture encrypt queue to drain, bounded at 5 s; abort anything still pending and remove its `.partial`.
  3. Close the vault handle (the core zeroes the VMK).
  4. Then `LOCKED`.
- Process death: every activity checks the process-scoped state in `onCreate` and `onStart` and routes to the lock screen when not `UNLOCKED`. Saved instance state holds nothing plaintext-derived.
- Capture and scripted actions (D-009 mechanism) require `UNLOCKED`. A debug-only `unlock_pin=<pin>` action may exist for tests; absent from benchmark and release. Test PINs are throwaway values.
- `FLAG_SECURE` on the lock screen and every dialog the app shows.

### 4.4 Portability for iOS later

- Argon2id, envelope parse/write, AEAD wrap, attempt-policy arithmetic: all in the core, no Android types.
- The device-bound step is a platform interface: "derive a 32-byte KEK from `k_pin` using a key that cannot leave this device." Android implements it with Keystore HMAC. iOS will need its own mechanism (its secure hardware has no HMAC key type); the slot layout must record which mechanism was used so another platform's slot can sit beside it.
- No iOS code now.

### 4.5 Lock screen UI

- In-app numeric keypad; no system keyboard. PIN digits held in a `ByteArray`, zeroed after use, never a `String`.
- Setup: enter, confirm, length 6 to 12.
- Wrong PIN: generic message, remaining delay shown as a countdown. The attempt counter and next-allowed time live in app-private storage and survive restarts. Use elapsed realtime plus stored wall time so changing the clock does not skip the delay.
- "Forgot PIN": typed `ERASE`, then erase.

### 4.6 Erase vault

Order: delete Keystore aliases (provider 2 if present, provider 3), delete `vault.key`, delete objects and partials, delete the directory. A crash mid-way leaves undecryptable files; the next start removes them as orphans. Document that key deletion is only as strong as the Keystore implementation.

### 4.7 C ABI additions (additive only)

```c
int pv_pin_stretch(const uint8_t* pin, size_t pin_len, const uint8_t* salt, size_t salt_len,
                   uint64_t opslimit, size_t memlimit, uint8_t* out32);
int pv_envelope3_parse(const uint8_t* buf, size_t len, pv_envelope3_info* out);
int pv_vault_open_envelope3(const char* vault_dir, const uint8_t* kek32, pv_vault** out);
int pv_vault_write_envelope3(pv_vault* v, const pv_envelope3_params* p, const uint8_t* kek32);
int pv_vault_destroy_files(const char* vault_dir);
```

Final names and structs settled at the checkpoint. New error codes after 23, mirrored in Kotlin. D-011 status/handle split for every new handle.

## 5. Failure handling

| Situation | Behaviour | Test |
|---|---|---|
| Wrong PIN | Generic error, counter up, delay per P3-5 | T1, T2 |
| Envelope tampered, KDF params out of bounds | Fail before running the KDF | T1 |
| Keystore key missing or invalidated | Vault reported unopenable; erase offered | T2 (delete alias in test) |
| Kill during migration or PIN change | Old or new envelope opens, never neither | T2, hooks at each step |
| Kill while unlocked | Lock screen on next launch | T2 (`am kill`), T3 |
| Lock during in-flight capture | Drain or abort; no `.partial` after next open | T2 |
| Kill during erase | Orphans removed on next start | T2 |
| Clock changed during delay | Delay still enforced | T2 |

## 6. Milestones

| Milestone | Work | Gate |
|---|---|---|
| A | `git status` clean check; T1 and T2 rerun green; Argon2id timing on emulator and Xiaomi (benchmark build) at `INTERACTIVE` and `MODERATE`; Keystore HMAC key creation and security level on both | Report, continue |
| B | `docs/PHASE3A_DESIGN.md`: envelope 3 byte layout, unlock flow, rewrap flow, lock state machine, attempt policy, erase order, ABI, platform interface for iOS, test plan; threat model deltas | **Stop for approval** |
| C | Core additions, T1 tests, provider 3 golden vectors (test PIN, fixed salt, test KEK stand-in, labelled TEST ONLY) | T1 green, Debug and Release |
| D | Android: Keystore HMAC provider, lock screen, setup, unlock, change, migration, lock rules, delays, erase; T2 | T2 green on emulator |
| E | T3 on Xiaomi; docs; `docs/PHASE3A_TEST_REPORT.md` | Checklist complete |

Keep `docs/PHASE3A_PROGRESS.md` current after each milestone.

## 7. Tests

**T1 (Windows):** all 14 existing tests; `pv_pin_stretch` known answer and bounds; envelope 3 round trip, wrong KEK, tamper per field and per slot, unknown slot alone (reject) and beside a PIN slot (accept), truncation, rewrap keeps the VMK; destroy files; all golden vectors.

**T2 (emulator):** all 31 existing tests; PIN setup, unlock, wrong PIN, delays with a test clock and with a clock change; PIN change; migration with interruption at each step; kill while unlocked; screen off; idle timeout (test-shortened); background lock; lock during capture; capture blocked while locked; Keystore alias deleted; lock-bound mode with an emulator PIN (`locksettings`, as in Phase 2); erase from locked and unlocked; `FLAG_SECURE` on lock screen and dialogs; `leak_scan` clean; logcat contains no PIN, `k_pin`, `kek`, or PIN length.

**T3 (Xiaomi, developer taps installs):**
- Migrate the 52-object vault with a throwaway PIN; all objects verify.
- Unlock time in debug and benchmark builds.
- Background, screen off, and the MIUI camera kill case: locked on return.
- Wrong PIN delays.
- Recents card blank on the lock screen.
- Phase 2 capture tests still pass while unlocked.
- Erase the vault only if the developer agrees.

## 8. Acceptance criteria

1. Phase 1 and 2 T1 and T2 suites pass.
2. Provider 3 vault opens only with the correct PIN; VMK never crosses JNI; tampered envelope and missing Keystore key fail closed.
3. Migration and PIN change survive interruption at every step.
4. Lock triggers per P3-4 work on emulator and Xiaomi; capture blocked while locked.
5. Attempt delays per P3-5, persistent across restarts and clock changes.
6. Erase works from the lock screen; interrupted erase leaves nothing readable.
7. Core changes additive and Android-free; golden vectors decrypt on both platforms.
8. Threat model updated (Section 4.1 limits, Kotlin copies of `k_pin`/`kek`, unreviewed construction).
9. Report separates demonstrated facts from assumptions.

## 9. Rules

1. **Git:** read-only only (`status`, `diff`, `log`, `show`). Never `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite. Recommend a commit message at the end; do not commit.
2. Start with `git status`; if not clean, stop and report.
3. No system-wide installs, no Android Studio upgrade, no SDK installs beyond what is needed, without asking.
4. No changes to phone settings. Phone installs need the developer's tap; ask when needed. No erase on the phone without agreement.
5. No home-grown cryptography: libsodium and platform APIs only. No change to v1 objects or provider 1 and 2 behaviour.
6. Test PINs are throwaway. Never ask for a real PIN. No PIN, derived key, or key bytes in logs, files, reports, or the repository.
7. No claims of "secure", "leak-proof", "private", "erased", or audited. No invented results.
8. **Code:**
   - No redundant `#include` lines for standard headers already available through the project's own headers.
   - Comments make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure", "intended". Longer rationale goes in `docs/`.
9. No 3B or later work (viewer, delete photo, dependency cleanup, video). Leave seams and document them.
10. If a device or dependency is unavailable, continue and report the exact blocker.
11. Before finishing, run `git status` and `git diff`; no secrets, build output, or large files in the tree.

## 10. Report (`docs/PHASE3A_TEST_REPORT.md` and final message)

Status; environment; architecture changes; exact commands; T1, T2, T3 tables; Argon2id parameters and unlock times per device and build; Keystore HMAC security level per device; defects with decision IDs; known limits; acceptance checklist with evidence; files changed; suggested commit message.
