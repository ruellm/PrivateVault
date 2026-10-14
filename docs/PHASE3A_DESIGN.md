# Phase 3A design: PIN lock and PIN-derived vault key

Status: proposed 2026-10-14 for the Milestone B checkpoint; approved by the developer the
same day with four changes, all applied below (attempt counting before the stretch in 4.1
and 8, the debug-only script entry point in 5 and 9, the wording of the same-boot rule in 8,
and the one-step vault creation in 2.3, 4.2, 11, 15). CP-2 stays provisional until the Xiaomi
run. Items marked **P3-x** are the decisions in `PHASE3A_BRIEF.md` Section 3; items marked
**CP-n** are the choices this document made beyond the brief (Section 14).
Nothing here changes the version 1 object format, the item header, or the behaviour of
envelope providers 1 and 2.

Facts from Phases 1 and 2 are not repeated; see `docs/PHASE1_DESIGN.md` (object format,
providers 1 and 2, error model) and `docs/PHASE2_DESIGN.md` (capture pipeline). Where this
document says "measured" it names the run; everything else is design intent to be confirmed
by the Milestone C and D tests.

All multi-byte integers are little-endian and unsigned. Sizes are libsodium constants; the
numbers in parentheses are their values in libsodium 1.0.22.

## 1. Summary

A numeric PIN of 6 to 12 digits (P3-1) protects the vault. The PIN is stretched with
Argon2id in the core, turned into a key encryption key by a non-exportable HMAC-SHA256 key in
Android Keystore, and that key encryption key unwraps the vault master key inside the core
(P3-2). The VMK never leaves the core; the stretched PIN and the KEK cross JNI as short-lived
byte arrays. The existing Keystore-only vault (provider 2) is rewrapped into the new envelope
(provider 3) when the user sets the first PIN (P3-6). The app locks on background, screen
off, idle, manual lock, and process death (P3-4); wrong attempts are delayed (P3-5); the lock
screen offers to erase the vault (P3-11).

```
PIN digits ──core──> k_pin = Argon2id(PIN, salt, opslimit, memlimit)        32 bytes
k_pin ──Kotlin──> kek = HMAC-SHA256(Keystore key, k_pin)                   32 bytes
kek ──core──> VMK = XChaCha20-Poly1305-IETF open(kek, nonce, wrapped_vmk, AD)
```

Every guess costs one Argon2id run plus one Keystore call on the device that holds the
Keystore key. A copy of `vault.key` taken off the device cannot be attacked without that key.

## 2. Envelope provider 3 (`vault.key`)

### 2.1 Layout

Header, 16 bytes, same first 12 bytes as providers 1 and 2:

| Offset | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVKEYENV`. Otherwise `ENVELOPE_MALFORMED`. |
| 8 | 2 | `version` | 1. Otherwise `ENVELOPE_UNSUPPORTED`. |
| 10 | 2 | `provider_id` | 3 = PIN slots. A provider 1 or 2 reader stops here with `ENVELOPE_UNSUPPORTED`; the provider 3 reader does the same for 1 and 2 (the app routes a provider 2 envelope to migration, Section 4). |
| 12 | 2 | `slot_count` | 1 to 8. 0 or more than 8: `ENVELOPE_MALFORMED`. |
| 14 | 2 | `reserved` | 0. Any other value: `ENVELOPE_MALFORMED`. |

Then `slot_count` slots back to back. Each slot starts with a 4-byte slot header:

| Offset | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 2 | `slot_type` | 1 = PIN. 2 = reserved for biometric (P3-3). Other values are unknown slots: skipped by length, never an error on their own. |
| 2 | 2 | `slot_len` | Byte length of the slot body that follows. Must fit inside the envelope; otherwise `ENVELOPE_MALFORMED`. |

PIN slot body (`slot_type` 1):

| Offset in body | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 2 | `mechanism` | Which device-bound step turns `k_pin` into the KEK (Section 7). 1 = Android Keystore HMAC-SHA256 key. The core reports it; the platform decides whether it can serve it. |
| 2 | 2 | `alias_len` | 1 to 64. Otherwise `ENVELOPE_MALFORMED`. |
| 4 | `alias_len` | `alias` | Identifier of the device-bound key on this platform, UTF-8 (the Keystore alias on Android). |
| | 4 | `opslimit` | Argon2id passes. Accepted range Section 3.2. |
| | 8 | `memlimit` | Argon2id memory in bytes. Accepted range Section 3.2. |
| | 16 | `salt` | `crypto_pwhash_SALTBYTES` random bytes. |
| | 24 | `nonce` | `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` random bytes. |
| | 48 | `wrapped_vmk` | VMK (32) plus tag (16). Key = KEK (32 bytes), nonce = `nonce`, additional data = Section 2.2. |

`slot_len` for a PIN slot is therefore `104 + alias_len`. A PIN slot whose `slot_len` is not
exactly that: `ENVELOPE_MALFORMED`. The whole envelope is at most 4096 bytes
(`PV_ENVELOPE3_MAX_BYTES`); the reader refuses anything longer before parsing. Bytes after
the last slot: `ENVELOPE_MALFORMED`.

Rules after the structural parse:

- No PIN slot at all (only unknown slots): `PV_ERR_ENVELOPE_NO_SLOT` (new code 24). An
  unknown slot beside a PIN slot is accepted and ignored.
- More than one PIN slot: the first one is used in Stage 3A; the parse reports the count.
- `opslimit` or `memlimit` outside the range: `ENVELOPE_KDF_PARAMS`, reported by the parse
  before any KDF runs and before any memory is requested.
- Unwrap failure (wrong KEK, which is wrong PIN or wrong Keystore key, or any modified
  byte covered by the additional data or the tag): `ENVELOPE_UNWRAP_FAILED`. The app shows
  one generic message for all of these (Section 6).

A first envelope with alias `privatevault.pin.<16 hex>` is 16 + 4 + 104 + 33 = 157 bytes.

### 2.2 Additional data (CP-1)

The additional data for every slot is the whole envelope image with every PIN slot's
`wrapped_vmk` field replaced by 48 zero bytes. The writer builds that image first and then
fills in each wrapped field; the reader rebuilds it from the file by zeroing the same fields.

Consequence: a change to the header, to the slot count, to any slot's type, length,
mechanism, alias, KDF parameters, salt, or nonce, or the removal or addition of a slot,
makes every PIN slot fail to unwrap. A change inside a `wrapped_vmk` field fails that slot's
tag. This is what "all slots are bound into the additional data" (brief, Section 4.1) means
here. The brief's other phrasing, "all envelope bytes before `wrapped_vmk`", gives the same
bytes for a single-slot envelope apart from the 48 trailing zeros, but would leave slots
after the one being opened unbound. D-016 records the choice.

### 2.3 Why the VMK stays in the core

`pv_vault_open_envelope3` reads `vault.key`, parses it, unwraps the first PIN slot with the
KEK it is given, and keeps the VMK in `sodium_malloc` memory inside the vault handle, as
`pv_vault_open` does with a VMK given from outside. The Kotlin side never receives the VMK.
`pv_vault_write_envelope3` takes the open handle and a KEK, so a rewrap also happens without
the VMK leaving the core. `pv_vault_create_envelope3` creates the directory, generates the
VMK, writes the envelope, and returns the open handle in one call, so setup never shows the
VMK to Kotlin either. The only remaining crossing is the provider 2 path
(`KeystoreKeyProvider.open` followed by `pv_vault_open`) during migration, once, for the
last use of that envelope. Phase 1 known limit, closed for provider 3 vaults.

## 3. PIN stretching

### 3.1 Function

`k_pin = crypto_pwhash(out = 32 bytes, PIN bytes, salt, opslimit, memlimit, ALG_ARGON2ID13)`.
The PIN is the ASCII digits as typed (6 to 12 bytes); the core treats it as opaque bytes so a
passphrase can use the same function later (P3-1). The algorithm is named explicitly, as in
provider 1.

### 3.2 Parameter range (P3-2, brief 4.1)

Accepted by the reader and the writer, checked before the KDF runs:

| Parameter | Minimum | Maximum |
|---|---|---|
| `opslimit` | `crypto_pwhash_OPSLIMIT_INTERACTIVE` (2) | `crypto_pwhash_OPSLIMIT_MODERATE` (3) |
| `memlimit` | `crypto_pwhash_MEMLIMIT_INTERACTIVE` (64 MiB) | `crypto_pwhash_MEMLIMIT_MODERATE` (256 MiB) |

Both bounds hold at the same time, so the accepted set is the box between the INTERACTIVE
and MODERATE presets. The upper bound keeps a modified envelope from forcing a large
allocation on a phone; the lower bound keeps it from weakening the derivation below the
libsodium interactive preset. The constants are `PV_PIN_OPSLIMIT_MIN/MAX` and
`PV_PIN_MEMLIMIT_MIN/MAX` in `pv.h`, checked against the libsodium values at compile time.

### 3.3 Measured times (Milestone A)

`kdf_bench` action: three runs per pair, fixed throwaway PIN, zero salt, one thread
(libsodium Argon2id is single-threaded). Times include the allocation of `memlimit` bytes.

| Device, build | ops 2, 64 MiB | ops 3, 64 MiB | ops 2, 128 MiB | ops 3, 128 MiB | ops 2, 256 MiB | ops 3, 256 MiB |
|---|---|---|---|---|---|---|
| Emulator Pixel_8_API_35, debug | 2238, 987, 991 | 1590, 1191, 1202 | 2246, 1692, 1601 | 3156, 2565, 2699 | 3555, 3223, 3446 | 5050, 5395, 4821 |
| Emulator Pixel_8_API_35, benchmark | 349, 253, 156 | 285, 164, 111 | 238, 957, 224 | 486, 630, 279 | 618, 549, 367 | 908, 539, 452 |
| Xiaomi 2201116SG, debug | not measured: phone not attached on 2026-10-14 | | | | | |
| Xiaomi 2201116SG, benchmark | not measured: phone not attached on 2026-10-14 | | | | | |

Times in milliseconds, three runs each. The debug variant builds the native code without
optimisation; the benchmark variant is the figure that matters (brief, Section 4.1). The
emulator runs on the host CPU (Core i7-10750H under WHPX) and its times vary by a factor of
two to four between runs of the same pair, so they bound nothing on the phone; they show
only that the whole range runs and that the bounds check refuses ops 1 and 512 MiB before
the KDF (`PV_ERR_ENVELOPE_KDF_PARAMS` in both builds). The phone rows are filled in when the
developer attaches the Xiaomi (debug APK, then the benchmark APK, each with a tap):

```
adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\debug\app-debug.apk
adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions kdf_bench,hmac_probe
adb -s 9c4424ba81c6 logcat -d -s PrivateVault:I
adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\benchmark\app-benchmark.apk
```

Keystore HMAC-SHA256 key (`hmac_probe`, alias created and deleted within the action):

| Device, build | Create | Mode | StrongBox | Level | MAC of 32 bytes |
|---|---|---|---|---|---|
| Emulator, debug | 15 ms | not lock-bound (no screen lock) | not offered | SOFTWARE | 5 to 12 ms |
| Emulator, benchmark | 7 ms | not lock-bound | not offered | SOFTWARE | 1 ms |
| Xiaomi, debug and benchmark | not measured: phone not attached | | | | |

The emulator Keystore is software-backed, as in Phases 1 and 2. On the Xiaomi the provider 2
AES key reported `TRUSTED_ENVIRONMENT` and no StrongBox; the HMAC key is expected to report
the same and that is to be confirmed by the run above.

### 3.4 Default parameters (CP-2)

Rule: among the pairs in the Section 3.2 box, take the one with the largest `memlimit`
whose benchmark-build time on the Xiaomi lies within 0.5 to 1.0 s, and among equal memory
the larger `opslimit`; memory is preferred because Argon2id's cost against parallel
hardware comes from memory. The phone figures are not in yet, so the proposal is
provisional:

| Parameter | Provisional default | Reason |
|---|---|---|
| `opslimit` | 3 | the MODERATE pass count |
| `memlimit` | 64 MiB | one Argon2id allocation of this size is safe beside the capture pipeline's measured native peak of about 50 MB on the phone; 128 MiB or 256 MiB only if the phone shows them under 1.0 s and the app survives the allocation next to a bound camera |

Named `PV_PIN_OPSLIMIT_DEFAULT` and `PV_PIN_MEMLIMIT_DEFAULT` in `pv.h` and mirrored in
Kotlin; the developer confirms the values at the checkpoint or after the phone run.

The reader accepts the whole range of Section 3.2, so the default can change in a later
stage without a format change; an envelope records the parameters it was written with.

### 3.5 Cost of guessing (for the threat model)

With 6 digits and the target of 0.5 to 1.0 s per guess on this phone, exhausting the PIN
space takes 6 to 12 days of single-threaded on-device work (about a week at 0.7 s), each
extra digit ten times more. The exact figure follows the phone measurement. The
app-enforced delays (Section 8) stand in front of that for anyone without root; an attacker
with root can reset them and is limited by the KDF cost alone. No hardware attempt counter
exists for app Keystore keys.

## 4. Unlock, setup, PIN change, migration

### 4.1 Unlock

1. Kotlin reads `vault.key` (`nativeFileReadAll`, cap 4096) and calls `nativeEnvelope3Parse`.
   Provider 1 or 2: route to migration (4.4) or report unopenable. Parse errors: unopenable.
2. Kotlin checks `mechanism == 1` and that the Keystore alias holds a key. No key: the vault
   is reported unopenable with reason `KEY_MISSING` and erase is offered.
3. `k_pin = nativePinStretch(pinDigits, salt, opslimit, memlimit)` (core).
4. `kek = KeystoreHmacProvider.mac(alias, k_pin)` (Kotlin, Keystore). `k_pin` is zeroed.
   A Keystore exception (device locked for a lock-bound key, key invalidated) is reported
   as unopenable with the exception class; erase is offered for the invalidated case.
5. `nativeVaultOpenEnvelope3(dir, kek, handleOut)` (core): parse again, unwrap, open.
   `kek` is zeroed. `ENVELOPE_UNWRAP_FAILED` is the wrong-PIN result. Any other code is
   shown by name.
6. On success: the attempt counter is reset, the alias sweep runs (4.6), the state becomes
   `UNLOCKED`.

Attempt counting (Section 8): the attempt is written to preferences (`failures + 1`, the
clocks, the boot count) between steps 2 and 3, before the stretch runs, and the counter is
reset only after step 5 returned `PV_OK`. A process killed anywhere between those two
points has therefore already counted as a failed attempt, so killing the app during the
stretch does not give a free guess.

The PIN digits live in a `ByteArray` from the keypad to step 3 and are zeroed in a
`finally` block; see Section 9.3 for what Kotlin cannot promise.

### 4.2 Setup (no vault yet)

Enter, confirm, 6 to 12 digits. Then, as in rewrap steps 1 and 2 (4.3): a Keystore HMAC
key under a fresh alias, a salt, `k_pin`, `kek`. Then one core call,
`pv_vault_create_envelope3(dir, params, kek, &handle)`: it makes `dir` and `dir/objects`,
refuses an existing `vault.key`, generates the VMK into guarded memory, writes the envelope
through `vault.key.partial` and the rename, and returns the open handle. The VMK never
leaves the core. `kek` zeroed; the alias recorded as current; `UNLOCKED`.

A setup interrupted before the envelope exists leaves `vault/objects/` with no `vault.key`
and possibly an unused Keystore alias; the next start treats that as no vault, removes the
directory (Section 10), and the next successful unlock's sweep removes the alias.

### 4.3 Rewrap (PIN change and migration share this)

Given an open vault handle (which holds the VMK) and the new PIN:

1. Generate a new Keystore HMAC key under a fresh alias `privatevault.pin.<16 hex from 8
   random bytes>` (CP-3). Mode and StrongBox handling as in D-010.
2. New salt from `pv_pin_salt_generate`. `k_pin` from the new PIN (core). `kek` from the new
   alias (Keystore). `k_pin` zeroed.
3. `pv_vault_write_envelope3(handle, params{mechanism 1, alias, opslimit, memlimit, salt},
   kek)`: the core builds the envelope, wraps the VMK with a fresh nonce, writes
   `vault.key.partial` (removing a stale one first), flushes, renames over `vault.key`, and
   fsyncs the directory. `kek` zeroed.
4. Delete the previous device key: the previous `privatevault.pin.*` alias (PIN change) or
   the provider 2 alias `privatevault.vmk.v1` (migration).
5. Record the new alias as current in app preferences (for the sweep, 4.6).

Objects are untouched. The rename is the commit point. Interruptions:

| Killed | State on disk and in Keystore | Next start |
|---|---|---|
| During or after 1, before 3 | Old envelope, old key, plus an unused new alias | Old PIN opens the vault; the sweep deletes the unused alias |
| During 3, before the rename | Old envelope, a stale `vault.key.partial`, both keys | Old PIN opens; the opener deletes the stale partial |
| After the rename, before 4 | New envelope, both keys | New PIN opens; the sweep deletes the old alias (and the provider 2 alias after migration) |
| After 4 | New envelope, new key | New PIN opens |

Never neither: the old envelope is replaced only by the rename, and the old key is deleted
only after the rename returned.

Why a fresh Keystore key per rewrap (CP-3): with the key kept, a copy of the old `vault.key`
taken before a PIN change would still open with the old PIN on this device. With a fresh key
and the old alias deleted after the commit, the old copy has no key to run its HMAC with.
Migration gets the same property against a copy of the provider 2 envelope. D-017.

### 4.4 Migration of the provider 2 vault (P3-6)

Trigger: the app finds `vault.key` with `provider_id` 2 and no PIN set. The lock screen shows
the setup flow ("Set a PIN for this vault"). After enter and confirm:

1. `KeystoreKeyProvider.open(envelope)` unwraps the VMK with the provider 2 key (this still
   carries the VMK through Kotlin, once, for this last use). `pv_vault_open(dir, vmk)`;
   `vmk` zeroed.
2. Rewrap (4.3) with the new PIN; step 4 deletes `privatevault.vmk.v1`.
3. `UNLOCKED`.

On the Xiaomi the provider 2 key is not lock-bound, so step 1 needs nothing from the device.
On a lock-bound device the user is interacting with the app, so the device is unlocked.

### 4.5 PIN change

From the unlocked vault screen: current PIN (verified by a full unlock against the current
envelope, which also applies the attempt delay), new PIN, confirm. Then rewrap (4.3).

### 4.6 Alias sweep

After every successful unlock: delete every Keystore alias that starts with
`privatevault.pin.` and is not the alias named in the envelope that just opened, and delete
`privatevault.vmk.v1` if the envelope that opened is provider 3. The sweep runs only after a
successful unwrap, so a broken envelope can never cause the key it needs to be deleted.

## 5. Lock state machine

Process-scoped `LockController` in `PvApp`. States `NO_VAULT`, `LOCKED`, `UNLOCKED`,
`LOCKING`. The process starts in `NO_VAULT` or `LOCKED`, never `UNLOCKED`.

```
             setup or unlock
 NO_VAULT ───────┐              ┌───────────────── trigger ───────────────┐
    ▲            ▼              ▼                                          │
    │  erase   LOCKED ──unlock──> UNLOCKED ──trigger──> LOCKING ──closed──> LOCKED
    └────────────┘
```

Triggers from `UNLOCKED` to `LOCKING` (P3-4):

| Trigger | Source | Notes |
|---|---|---|
| App to background | `PvApp` counts started activities; the lock is requested 700 ms after the count reaches zero | The same rule `ProcessLifecycleOwner` applies; the owner itself cannot be used because its `androidx.startup` initializer is removed from the manifest (D-013, D-020). CP-6 withdrawn. |
| Screen off | `ACTION_SCREEN_OFF` receiver registered by `PvApp`, not exported | Also covers the MIUI case where the process survives in the background with the screen off. |
| Idle | 5 minutes without `onUserInteraction` in any activity | A `Handler` timer reset by every activity's `onUserInteraction` and by capture completions; checked against `SystemClock.elapsedRealtime`. Test builds accept a shorter timeout through a debug action. |
| Manual | Lock button on the vault and capture screens | |
| Process death | none needed | The next process starts `LOCKED`. |

`LOCKING`, in order:

1. Post `LOCKED_UI` to the activities: the capture screen finishes to the lock screen at
   once; the vault screen does the same. Nothing sensitive stays on screen while the rest
   runs.
2. `CaptureSession.drain(5 s)`: refuse new captures; close queued `ImageProxy` objects that
   have not started encrypting (nothing was written for them); set the JNI cancel flag
   (CP-5) so the in-flight encrypt fails at its next source read, which makes the core
   delete its `.partial`; wait for the encrypt thread to go idle, at most 5 s. If the wait
   expires the handle is still in use and the close below waits for the thread anyway;
   the lock screen is already showing.
3. `VaultSession.close()`: `pv_vault_close`, which zeroes the VMK.
4. `LOCKED`.

Activities: `LockActivity` (launcher, exported), `MainActivity` (vault screen, not
exported), `CaptureActivity` (not exported). `MainActivity` and `CaptureActivity` check
`lock.state` in `onCreate` and `onStart` and route to `LockActivity` when it is not
`UNLOCKED`. Saved instance state holds nothing derived from plaintext; the log views are
rebuilt from `CaptureSession.history`, which carries object IDs and sizes only. All three
activities set `FLAG_SECURE`; every confirmation (erase, PIN change) is an in-activity view,
not a `Dialog`, so no second window exists that could miss the flag.

Capture and scripted actions require `UNLOCKED`. Scripted actions (the D-009 mechanism)
enter through `ScriptActivity`, which exists only in the debug source set and is exported
only by the debug manifest (`app/src/debug/AndroidManifest.xml`, CP-4 as amended). Its
`actions` list must start with `unlock_pin=<pin>` or `setup_pin=<pin>`; it performs that
step through `LockController`, then hands the rest of the list to the vault screen as
before. `LockActivity` reads no extras. The benchmark and release manifests export nothing
but `LockActivity`; Milestone D records the merged manifest of each variant. Test PINs are
throwaway values and are not written to any log.

## 6. Lock screen UI

- Numeric keypad drawn by the app (0 to 9, delete, OK). No `EditText`, so the system
  keyboard, autofill, and text buffers never see the digits.
- Digits go into a 12-byte `ByteArray` with a length counter; the display shows one dot per
  digit. The array is zeroed on delete-all, on submit (after the stretch call returns), on
  `onStop`, and on `onDestroy`. Length limits 6 to 12 are enforced by the keypad.
- Setup and PIN change: enter, then confirm; mismatch clears both.
- Wrong PIN: one generic line, "Wrong PIN". While a delay is active the keypad is disabled
  and a countdown shows the remaining time (whole seconds), driven by the arithmetic of
  Section 8; the text does not say how many attempts were made.
- Unopenable vault (key missing, envelope malformed, Keystore refuses): the reason name and
  the erase option.
- "Forgot PIN": shows a field (an in-app keypad for letters is not needed; this one input
  uses a plain `EditText` with `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, since the word is not a
  secret) where typing `ERASE` enables the erase button (P3-11). Section 10.
- Status line with the Keystore level and key mode, as the vault screen shows today.

## 7. Platform interface for the device-bound step (iOS later, brief 4.4)

```kotlin
interface DeviceBoundKey {             // Kotlin shape; Swift will mirror it
    val mechanism: Int                 // value written into the slot
    fun create(alias: String)          // generates a key that cannot leave the device
    fun exists(alias: String): Boolean
    fun derive(alias: String, kPin: ByteArray): ByteArray   // 32-byte KEK
    fun delete(alias: String)
}
```

- Mechanism 1 (Android): `KeystoreHmacProvider`. `derive` = HMAC-SHA256 over the 32-byte
  `k_pin` with a Keystore key created with `PURPOSE_SIGN`, `DIGEST_SHA256`, key size 256,
  `setUnlockedDeviceRequired(true)` only when `isDeviceSecure()` at creation (D-010),
  StrongBox attempted with fallback.
- iOS: the Secure Enclave holds only EC P-256 keys, so an HMAC key is not available there.
  The candidate is a Keychain item (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`) holding
  a random secret that the core would HMAC with, or an ECDH-based derivation with a Secure
  Enclave key. Choosing between them is an iOS design task; neither is built now. Whatever
  is chosen gets its own mechanism number, so an iOS slot can sit beside an Android slot in
  one envelope once a VMK transfer path exists (Phase 6).
- The core is mechanism-agnostic: Argon2id, envelope parse and write, AEAD wrap, delay
  arithmetic, and file destruction have no platform types. `mechanism` is data the core
  carries from the file to the platform and back.

## 8. Attempt policy (P3-5)

Arithmetic in the core, storage and clocks in Kotlin.

```c
// Delay that must pass after `failures` consecutive wrong PINs before the next attempt.
// 0 for failures < 5; 30 s * 2^(failures - 5) after that; capped at 3600 s.
uint64_t pv_pin_delay_ms(uint32_t failures);
```

| Consecutive failures | Delay before the next attempt |
|---|---|
| 1 to 4 | none |
| 5 | 30 s |
| 6 | 60 s |
| 7 | 2 min |
| 8 | 4 min |
| 9 | 8 min |
| 10 | 16 min |
| 11 | 32 min |
| 12 and more | 1 h |

```c
// Milliseconds still to wait. Two remaining-time estimates are computed: one from the
// elapsed clock, one from the wall clock (a wall clock that went backwards gives the
// whole delay). Same boot: the larger of the two. Different boot: the whole delay again.
uint64_t pv_pin_remaining_ms(uint64_t delay_ms, uint64_t fail_elapsed_ms, uint64_t now_elapsed_ms,
                             int64_t fail_wall_ms, int64_t now_wall_ms, int boot_changed);
```

Kotlin (`AttemptPolicy`) keeps in app-private `SharedPreferences`: `failures`, `delay_ms`,
`fail_elapsed_ms` (`SystemClock.elapsedRealtime`), `fail_wall_ms`
(`System.currentTimeMillis`), and `boot_count` (`Settings.Global.BOOT_COUNT`). Every unlock
attempt is recorded before the stretch runs (`failures + 1`, the clocks, the boot count,
written with `commit` so the write completes before the stretch starts); only a successful
unwrap resets everything. A process killed during an unlock has already counted. An unlock
attempt while `remaining > 0` is refused by the UI and, as a second line of defence, by
`LockController` itself.

Why a reboot restarts the delay (CP-7, D-018): after a reboot the elapsed clock is gone and
only the wall clock remains, which the device owner can set. Restarting the delay makes a
clock change useless and costs a legitimate user at most one more wait after a reboot.

What this does not do: no auto-erase (a later stage); nothing survives root (the
preferences file is app-private, not tamper-proof); nothing survives Clear Data, which
also destroys the Keystore keys and so the vault.

## 9. Memory handling and JNI

### 9.1 Entry points (D-011 shape: status returned, data through arrays)

```kotlin
external fun nativePinStretch(pin: ByteArray, salt: ByteArray, opslimit: Long, memlimit: Long, out: ByteArray): Int
external fun nativePinSaltGenerate(out: ByteArray): Int
// infoOut = [version, provider, slot_count, pin_slot_count, unknown_slot_count, pin_slot_index,
//            mechanism, opslimit, memlimit]; saltOut 16 bytes; aliasOut[0] the alias.
external fun nativeEnvelope3Parse(envelope: ByteArray, infoOut: LongArray, saltOut: ByteArray, aliasOut: Array<String?>): Int
external fun nativeVaultCreateEnvelope3(dir: String, mechanism: Int, alias: String, opslimit: Long, memlimit: Long, salt: ByteArray, kek: ByteArray, handleOut: LongArray): Int
external fun nativeVaultOpenEnvelope3(dir: String, kek: ByteArray, handleOut: LongArray): Int
external fun nativeVaultWriteEnvelope3(handle: Long, mechanism: Int, alias: String, opslimit: Long, memlimit: Long, salt: ByteArray, kek: ByteArray): Int
external fun nativeVaultDestroyFiles(dir: String): Int
external fun nativePinDelayMs(failures: Int): Long
external fun nativePinRemainingMs(delayMs: Long, failElapsedMs: Long, nowElapsedMs: Long, failWallMs: Long, nowWallMs: Long, bootChanged: Boolean): Long
external fun nativeSetEncryptCancel(cancel: Boolean)      // all variants
```

### 9.2 Native side

- `pin`, `k_pin`, and `kek` are copied out of the Java arrays into `KeyCopy` buffers that
  `sodium_memzero` on scope exit; the stack output buffers are zeroed before return.
- `nativeVaultOpenEnvelope3` never writes the VMK anywhere Kotlin can read.
- The cancel flag (CP-5) is an atomic checked by the source wrapper every encrypt already
  passes through (the Phase 2 hook wrapper, now compiled in every variant with the cancel
  check only; the slow-read and fail-after hooks stay debug-only). A cancelled read returns
  an error, the core reports `PV_ERR_IO`, deletes the `.partial`, and the Kotlin side names
  the result `CANCELLED` when the flag was set.

### 9.3 What Kotlin cannot promise (threat model)

`ByteArray` objects live in the managed heap. The app overwrites the PIN, `k_pin`, and
`kek` arrays after use, but the garbage collector may have moved or copied them before
that, and a `Mac.doFinal` result is allocated by the platform. These copies are short-lived
and small, and none of them is the VMK, but they exist. Recorded in `docs/THREAT_MODEL.md`.

## 10. Erase (P3-11, brief 4.6)

From the lock screen (typed `ERASE`) or from the vault screen (same confirmation).
`LockController.erase()`:

1. Write `erase_pending = true` to app preferences (CP-8).
2. If unlocked: lock first (Section 5), so no encrypt runs and the VMK is gone.
3. Delete Keystore aliases: `privatevault.vmk.v1` if present, every `privatevault.pin.*`.
4. `pv_vault_destroy_files(dir)`: delete `vault.key` and `vault.key.partial`, every entry in
   `objects/`, then `objects/`, then the vault directory. Missing entries are not errors.
5. Clear the attempt counters and the current-alias record; clear `erase_pending`.
6. `NO_VAULT`.

Interrupted erase: on every process start, if `erase_pending` is set, steps 3 to 6 run again
before any screen is shown. A crash between steps 3 and 4 leaves files that no key can open;
the pending flag makes the next start remove them. Deleting a Keystore key is as strong as
the Keystore implementation makes it; deleting a file removes a directory entry, and flash
storage may keep the blocks until reuse (Phase 1 position, unchanged). The app does not
claim erasure beyond that.

## 11. C ABI additions (brief 4.7)

Additive only. Declared in `pv.h` after the Phase 2 functions. Error codes after 23.

```c
#define PV_ERR_ENVELOPE_NO_SLOT 24     // a provider 3 envelope with no slot this reader can use

#define PV_PIN_SALT_BYTES 16
#define PV_KEK_BYTES 32
#define PV_PIN_OPSLIMIT_MIN 2u         // = crypto_pwhash_OPSLIMIT_INTERACTIVE
#define PV_PIN_OPSLIMIT_MAX 3u         // = crypto_pwhash_OPSLIMIT_MODERATE
#define PV_PIN_MEMLIMIT_MIN (64u * 1024u * 1024u)
#define PV_PIN_MEMLIMIT_MAX (256u * 1024u * 1024u)

#define PV_ENVELOPE_PROVIDER_PIN 3
#define PV_SLOT_PIN 1
#define PV_SLOT_BIOMETRIC 2            // reserved, never written in Stage 3A
#define PV_MECHANISM_ANDROID_KEYSTORE_HMAC 1
#define PV_ENVELOPE_ALIAS_MAX 64
#define PV_ENVELOPE3_MAX_BYTES 4096
#define PV_ENVELOPE3_MAX_SLOTS 8

typedef struct pv_envelope3_params {
    uint16_t mechanism;
    char alias[PV_ENVELOPE_ALIAS_MAX + 1];    // UTF-8, NUL terminated, 1 to 64 bytes
    uint32_t opslimit;
    uint64_t memlimit;
    uint8_t salt[PV_PIN_SALT_BYTES];
} pv_envelope3_params;

typedef struct pv_envelope3_info {
    uint16_t version;
    uint16_t provider_id;
    uint16_t slot_count;
    uint16_t pin_slot_count;
    uint16_t unknown_slot_count;
    uint16_t pin_slot_index;                  // index of the first PIN slot
    pv_envelope3_params pin;                  // parameters of that slot
} pv_envelope3_info;

int pv_pin_stretch(const uint8_t* pin, size_t pin_len, const uint8_t* salt, size_t salt_len,
                   uint64_t opslimit, uint64_t memlimit, uint8_t* out, size_t out_len);
int pv_pin_salt_generate(uint8_t* out, size_t out_len);

// In-memory forms, used by the vault functions, the host tests, and the vector generator.
int pv_envelope3_parse(const uint8_t* buf, size_t len, pv_envelope3_info* out);
int pv_envelope3_seal(const uint8_t* vmk, size_t vmk_len, const pv_envelope3_params* p,
                      const uint8_t* kek, size_t kek_len, uint8_t* out, size_t out_cap, size_t* out_len);
int pv_envelope3_open(const uint8_t* buf, size_t len, const uint8_t* kek, size_t kek_len,
                      uint8_t* vmk_out, size_t vmk_out_len);

// Vault forms. The VMK stays inside the handle. create makes the directories, refuses an
// existing vault.key, generates the VMK, writes the envelope, and returns the open handle.
int pv_vault_create_envelope3(const char* vault_dir, const pv_envelope3_params* p, const uint8_t* kek,
                              size_t kek_len, pv_vault** out);
int pv_vault_open_envelope3(const char* vault_dir, const uint8_t* kek, size_t kek_len, pv_vault** out);
int pv_vault_write_envelope3(pv_vault* v, const pv_envelope3_params* p, const uint8_t* kek, size_t kek_len);
int pv_vault_destroy_files(const char* vault_dir);

// Attempt policy arithmetic (Section 8).
uint64_t pv_pin_delay_ms(uint32_t failures);
uint64_t pv_pin_remaining_ms(uint64_t delay_ms, uint64_t fail_elapsed_ms, uint64_t now_elapsed_ms,
                             int64_t fail_wall_ms, int64_t now_wall_ms, int boot_changed);
```

- `pv_envelope3_open` returns VMK bytes to a C caller; it exists for the host tests and the
  vector generator and has no JNI counterpart. Android uses only the vault forms.
- `pv_vault_create_envelope3` fails with `PV_ERR_DEST_EXISTS` when `vault.key` exists and
  leaves the directories in place on any later failure (the next start removes a vault
  directory without `vault.key`, Section 10).
- `pv_vault_open_envelope3` deletes a stale `vault.key.partial` before reading `vault.key`.
- `pv_vault_write_envelope3` removes a stale `vault.key.partial`, writes the new one, flushes,
  renames over `vault.key` (replacing: `rename` on POSIX, `MoveFileExW` with
  `MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH` on Windows), and fsyncs the
  directory. This is the one place the core replaces an existing file; a new internal
  `fs::rename_replace` carries it. `pv_file_write_atomic` keeps its no-replace rule.
- `pv_error_name` learns code 24; `NativeBridge` mirrors it.
- The desktop CLI does not gain a PIN provider; `pv inspect` is unchanged.

The `pv_pin_stretch` function, its constants, and `nativePinStretch` were added in
Milestone A for the measurements and are kept as proposed here.

## 12. Build and dependencies

- No new runtime dependency (CP-6 withdrawn, D-020). `androidx.test:core` 1.6.1 is pinned
  for the lock screen window test. The keypad, countdown, and confirmation views are plain
  widgets.
- `versionCode` 3, `versionName` 0.3.0.
- Build variants unchanged: debug (test hooks, `unlock_pin`, `setup_pin`), benchmark,
  release.

## 13. Failure handling (brief Section 5)

| Situation | Detection | Behaviour | Test |
|---|---|---|---|
| Wrong PIN | `ENVELOPE_UNWRAP_FAILED` from `pv_vault_open_envelope3` | Generic message, counter up, delay per Section 8 | T1 (`test_envelope3` wrong KEK), T2 (`LockTest.wrongPinDelays`) |
| Envelope tampered, KDF parameters out of bounds | parse: `ENVELOPE_MALFORMED`, `ENVELOPE_KDF_PARAMS`, `ENVELOPE_NO_SLOT`; unwrap: `ENVELOPE_UNWRAP_FAILED` | Fails before the KDF for structural and range errors; unopenable shown with the code name; erase offered | T1 per field and per slot |
| Keystore key missing or invalidated | `KeystoreHmacProvider.mac` returns null or throws | Unopenable, `KEY_MISSING` or the exception class; erase offered | T2 (delete the alias, then unlock) |
| Kill during migration or PIN change | none needed | Table in 4.3; the next start opens with the old or the new PIN | T1 (stale partial, replace), T2 (hooks `after_key_created`, `after_partial`, `before_old_key_delete`) |
| Kill while unlocked | none needed | Process starts `LOCKED`; lock screen first | T2 (`am kill`), T3 |
| Lock during in-flight capture | `LOCKING` step 2 | Drain or cancel; `.partial` removed by the core; next open removes any leftover | T2 (`test_hook_slow` plus lock) |
| Kill during erase | `erase_pending` flag | Next start finishes the erase | T2 (hook after alias deletion) |
| Clock changed during delay | `pv_pin_remaining_ms` | Delay unchanged (same boot) or restarted (reboot) | T1 arithmetic, T2 with `date` over adb |
| Device locked, lock-bound key | Keystore exception from `mac` | Unopenable with the exception name; the attempt stays counted, because the record is written before the stretch and only an unwrap resets it | T2 with an emulator PIN (as Phase 2) |
| Storage full during rewrap | `PV_ERR_IO` from the write | Old envelope stays; message shown | T1 fault injection on the sink |

## 14. Checkpoint items (CP-1 to CP-9)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Additional data rule | Whole envelope image with every `wrapped_vmk` zeroed (2.2) |
| CP-2 | Default Argon2id parameters | Section 3.4, from the phone benchmark figures |
| CP-3 | Fresh Keystore HMAC key per rewrap, old alias deleted after the rename | Section 4.3 |
| CP-4 | `LockActivity` becomes the exported launcher; `MainActivity` is no longer exported; scripted actions enter through a `ScriptActivity` exported by the debug manifest only, starting with `unlock_pin=` or `setup_pin=` (amended by the developer) | Section 5 |
| CP-5 | JNI cancel flag for in-flight encrypts during `LOCKING`, compiled in every variant | Section 9.2 |
| CP-6 | New dependency `androidx.lifecycle:lifecycle-process:2.6.2` (approved, then withdrawn in Milestone D: D-020) | Section 12 |
| CP-7 | Same boot: the larger of the two remaining-time estimates; a reboot restarts the current delay | Section 8 |
| CP-8 | `erase_pending` preference so an interrupted erase finishes on the next start | Section 10 |
| CP-9 | ABI names and structs as in Section 11, one new error code (24) | Section 11 |

## 15. Seams left for later stages

- Slot type 2 (biometric) and further mechanism numbers (iOS) are reserved; the parse
  already skips unknown slots and reports mechanism numbers it does not know.
- The provider 2 migration is the last place the VMK crosses JNI; it goes away with
  provider 2 itself once no provider 2 vault remains to migrate.
- The attempt policy has no auto-erase; the counter and `pv_pin_delay_ms` are where a
  later stage would add it.
- Passphrase option (P3-1): `pv_pin_stretch` takes bytes; the keypad is the only part that
  limits input to digits.
- Viewer (3B): `LockController` posts `LOCKED_UI` to any registered activity, so a viewer
  activity joins the same rule.

## 16. Test plan

**T1 (Windows, CTest).** All 14 existing tests. New:

- `test_pin_stretch`: known answer for a fixed PIN, salt, and parameters (computed once
  with libsodium and recorded in the test); bounds (ops 1, 4; mem 64 MiB minus 1, 256 MiB
  plus 1, huge) give `ENVELOPE_KDF_PARAMS` without running; argument checks; different
  salts give different output; delay arithmetic table and remaining-time cases (same boot,
  clock forward, clock back, reboot).
- `test_envelope3`: seal and open round trip; wrong KEK; tamper every header field, every
  slot header field, every PIN slot field, the tag and the body; unknown slot alone
  (`ENVELOPE_NO_SLOT`) and beside a PIN slot (accept); two PIN slots (first used);
  slot_count 0 and 9; slot_len past the end; alias_len 0 and 65; PIN slot with a wrong
  slot_len; trailing byte; truncation at every length; envelope longer than 4096; parse
  reports fields and counts; vault forms: open through a directory, write replaces the
  envelope and keeps the VMK (objects made before the rewrap verify after it), stale
  `vault.key.partial` removed by open and by write, sink fault during write leaves the old
  envelope; destroy files on a vault with objects and partials, on a missing directory, and
  twice.
- `test_golden`: the provider 3 vectors below.

**Golden vectors** (`tests/fixtures/v1/`, TEST ONLY): `envelope3_single.key` and
`envelope3_with_unknown_slot.key`, wrapping the test VMK with a fixed KEK stand-in
`PRIVATEVAULT TEST ONLY KEK  0001` (the Keystore step cannot be reproduced on Windows, so
the vectors fix the KEK instead), a fixed salt, test PIN `000000` recorded in the manifest
with the `k_pin` the stretch gives for it at the vector parameters. Both platforms must:
stretch the test PIN to the recorded `k_pin`, open the envelope with the stand-in KEK, and
decrypt a vector object with the VMK that comes out (on Android through the vault form:
open the fixture directory with the KEK and verify the object).

**T2 (emulator, instrumented).** All 31 existing tests. New classes: `PinTest` (stretch
through JNI equals the vector `k_pin`; bounds; salt generate), `Envelope3Test` (vault
create, write, open, wrong KEK, tamper, destroy, stale partial, alias sweep),
`KeystoreHmacTest` (key creation, level, MAC determinism, deleted alias gives null,
StrongBox attempt), `LockTest` (setup, unlock, wrong PIN counting, an attempt is recorded before the stretch
and a process kill during the unlock leaves it counted, delays with injected clocks, delay
survives a process restart of the preferences, PIN change, migration from a provider 2
vault with interruption hooks at each step, kill while unlocked starts locked,
lock during a slowed capture leaves no `.partial`, capture refused while locked, erase from
locked and unlocked, interrupted erase finishes), `LockScreenTest` (`FLAG_SECURE` on the
lock activity window, PIN array zeroed after submit). Host-driven: `am kill` while
unlocked and `am kill` during a slowed unlock (counter checked afterwards), the merged
manifest of every variant (`aapt2`: only `LockActivity` exported outside debug), `KEYCODE_SLEEP`, idle with a shortened timeout, background by `KEYCODE_HOME`,
clock change with `date` during a delay, the Phase 2 lock-bound PIN procedure, `leak_scan`,
and a logcat scan for the test PIN digits, `k_pin`, `kek` hex, and PIN length.

**T3 (Xiaomi, debug APK by developer tap, benchmark APK for timings).** Brief Section 7:
migrate the 52-object vault with a throwaway PIN and verify all; unlock time in debug and
benchmark; background, screen off, and the MIUI camera kill case; wrong PIN delays; recents
card blank on the lock screen; Phase 2 capture tests while unlocked; erase only if the
developer agrees.

## 17. Threat model deltas (for `docs/THREAT_MODEL.md` Section 7)

- The construction composes standard primitives (Argon2id, HMAC-SHA256 in Keystore,
  XChaCha20-Poly1305) and has not been reviewed.
- No hardware attempt counter exists for app Keystore keys; the delays are app-enforced and
  an attacker with root can reset them.
- Guessing cost per Section 3.5; each extra digit multiplies it by ten.
- Kotlin copies of the PIN digits, `k_pin`, and `kek` (9.3); the VMK no longer crosses JNI
  for provider 3 vaults; the provider 2 migration is the one remaining crossing.
- Key deletion at erase and at rewrap is as strong as the Keystore implementation; file
  deletion is a directory entry removal.
- A device without a secure lock screen (the Xiaomi) gets a Keystore HMAC key that is usable
  whenever the device is on; the PIN is then the only thing between a person holding the
  phone and the vault, and its strength is the guessing cost above.
- The lock screen and all app windows carry `FLAG_SECURE`.

## 18. What the implementation changed against this design (Milestones C and D)

- Section 6: the lock screen gained a Diagnostics button that runs the Milestone A
  measurements (`Diagnostics.kdfBench`, `hmacProbe`) without a vault, in every variant, so
  the benchmark build can be measured on a phone after CP-4 removed scripting from it.
  `ScriptActivity` accepts `kdf_bench` and `hmac_probe` before the unlock action for the
  same reason; they touch no vault and no vault key.
- Section 5, step 2: once the encrypt queue is empty the cancel flag is cleared again
  (`CaptureSession.awaitIdle`), while the session stays closed to captures until the next
  unlock. With the flag left set, encrypts made outside the capture session (the sample
  actions, the Phase 1 tests) failed after a lock.
- Section 4.1: the remaining delay reported with a wrong-PIN result is read after the
  stretch, so it is the recorded delay minus the stretch time.
- Section 11: `vault_internal.h` holds the handle struct and `vault_open_raw`, shared by
  `vault.cpp` and `envelope3.cpp`; `fs` gained `rename_replace` and `remove_dir`. The JNI
  parse entry maps an empty byte array to an empty envelope (`ENVELOPE_MALFORMED`) rather
  than a null pointer (`INVALID_ARGUMENT`).
- Section 4.3: setup and migration also run the alias sweep after success, so a key left
  by an interrupted earlier attempt is removed at once rather than at the next unlock.
- Section 5: the background trigger counts started activities in `PvApp` instead of using
  `ProcessLifecycleOwner`, whose initializer the manifest removes (D-020); the dependency
  of CP-6 is gone again.
- Section 13: a Keystore refusal (device locked, lock-bound key) leaves the attempt
  counted, since the record precedes the stretch.
- Section 16: the "kill during an unlock" check runs as an instrumented test through the
  `beforeStretch` hook (the attempt is on disk when the hook fires) and as a host step
  (`am kill` during a 4 s delayed unlock, counter checked by the following attempts).
