# Claude Code Start Prompt: PrivateVault Phase 1

Read `PROJECT_HANDOFF.md` in full before changing anything. It is the canonical brief: product context, roadmap, constraints, architecture, object format baseline, test tiers, milestones, acceptance criteria, Git and code rules, and report format. If this prompt and the handoff ever differ, the handoff wins.

Repository: expected at `D:\repository\PrivateVault`. Confirm the actual working directory first.

## What to do

Execute Phase 1 end-to-end per handoff Section 7:

- **A:** inspect repo and toolchains, scaffold, and prove libsodium links on Windows and Android (`arm64-v8a`, `x86_64`) before building anything on it.
- **B:** write the design docs, then **stop and report** for approval (handoff Section 8, item 5).
- **C:** after approval, the desktop core, CLI, and all T1 tests.
- **D:** the Android app, Keystore provider, JNI bridge, and T2 tests; T3 if a device is connected.
- **E:** cross-platform verification, docs, and `docs/PHASE1_TEST_REPORT.md`.

Outside the Milestone B checkpoint, keep going without asking for approval of ordinary engineering choices. Record non-obvious ones in `docs/DECISIONS.md`. Keep `docs/PHASE1_PROGRESS.md` updated after each milestone.

## Hard rules

- Git: read-only only (`status`, `diff`, `log`, `show`). No `init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`. Do not commit; recommend a commit message at the end.
- No system-wide or privileged installs (WSL, hypervisor features, global SDK changes) without asking.
- No destructive operations; never overwrite a file you have not read.
- No home-grown cryptography; libsodium and platform APIs only.
- Synthetic data only. No real personal or intimate media.
- No Phase 2 work, no TrueCrypt implementation, no root requirement.
- No claims of "secure", "leak-proof", or audited. No invented build or test results.
- No passwords on command lines, no secrets in logs or the repository.
- Code: no redundant `#include` of standard headers a file already gets through project headers; comments readable cold, plain words, no coined terms, no reassurance words.

## Blockers

If the Android SDK/NDK, an emulator, a dependency, or a device is unavailable, continue with everything else and report the exact blocker and the steps needed. Ask the developer only for real blockers or security trade-offs the handoff does not settle.

## Finish

Run `git status` and `git diff`, confirm no secrets, build output, or large test files are present, write `docs/PHASE1_TEST_REPORT.md`, and return the final report in the handoff Section 10 format, with exact commands and Pass / Fail / Not verified for every acceptance criterion.
