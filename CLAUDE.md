# PrivateVault: notes for Claude Code

Read `PROJECT_HANDOFF.md` first. It is the canonical brief for this repository; the
developer supplies a per-phase brief (`PHASE3A_BRIEF.md` for Stage 3A). `docs/PHASE3A_PROGRESS.md`
records the current state and the commands that were run (`docs/PHASE1_PROGRESS.md` and
`docs/PHASE2_PROGRESS.md` for the earlier phases).

## Layout

- `src/vault_core/` portable C++20 core with a C ABI (`include/pv/pv.h`).
- `src/desktop_cli/` Windows command line tool and password key provider.
- `tests/core/` host tests, plain CTest executables.
- `tests/fixtures/v1/` committed synthetic golden vectors and the test-only key.
- `android/` Gradle project, Kotlin app (lock screen, vault, capture screen, leak scan),
  Keystore key providers, JNI bridge; `app/src/debug/` holds the video prototype and the
  scripted-actions entry point (`ScriptActivity`, exported by the debug manifest only).
- `docs/` design, threat model, dependencies, decisions, progress, test report.

## Build

- Dependencies come from vcpkg in manifest mode (`vcpkg.json`). Set `VCPKG_ROOT`
  to a vcpkg checkout at the baseline commit named in `vcpkg.json`.
- Windows: `cmake --preset windows-x64-tests`, `cmake --build --preset windows-x64-tests-debug`,
  `ctest --preset windows-x64-tests-debug`.
- Android core only: `cmake --preset android-arm64` or `android-x64` (needs
  `ANDROID_HOME` and `ANDROID_NDK_HOME`).
- Android app: `android/gradlew.bat assembleDebug` from `android/`; `lintDebug` must stay
  clean (`RestrictedApi` is an error); `assembleBenchmark` builds the non-debuggable variant.
- Android Studio must be Meerkat (2024.3.1) or later for AGP 8.9.
- Keep build output, caches, and downloads on drive D.

## Rules

- Git is read-only for Claude Code. No commits, branches, or history changes.
- No cryptography beyond libsodium and platform APIs.
- No passwords on the command line, no secrets in logs, docs, or tests.
- Synthetic data only.
- Comments: short, plain words, readable cold. No reassurance words.
- Do not add `#include` lines for standard headers already provided by project headers.
