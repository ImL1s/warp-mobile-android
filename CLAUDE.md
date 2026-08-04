# Warp on Mobile — AI Agent Entry Point

> **You are an AI assistant (Claude Code, Cursor, GitHub Copilot, etc.) opening this project.** This file is the canonical entry point. Read it first.

## What is this project

Open-source-first port of [Warp Terminal](https://github.com/warpdotdev/Warp) to Android, with a bundled Termux runtime. AGPL-3.0-only. Solo-dev 12-18 month timeline. F-Droid + GitHub Releases primary distribution.

See [`README.md`](README.md) for product description and architecture overview.

## Current milestone state (2026-08-04)

- **M0** (foundation spike): CLOSED CONDITIONAL GO @ commit `24a2c1c`
- **M1** (Android PTY/Service prototype): CLOSED CONDITIONAL GO @ commit `f7feb3f`, **10/10 stories PASS**
- **M2** (warpui::platform::android backend): CLOSED CONDITIONAL GO @ commit `0506c35`, **12/14 stories PASS** (M2-S13 user-deferred per 「先跳過便宜手機」)
- **M3** (Layer 2b integration: facade + DCS + Block + dynamic_grid): CLOSED CONDITIONAL GO @ commit `8ec75c8`, **12/12 stories PASS** (27 codex rounds; Plan Amendment 5 cfg-gate→extraction pivot)
- **M4** (Termux runtime: zsh + GNU coreutils + APT): CLOSED CONDITIONAL GO @ commit `de26e3a`, **14/15 PASS** (M4-S14 closed in v1-prep follow-up @ `1e732c5`)
- **M5** (Mobile UX: selection / accessory row / blocks / paste / UX review): CLOSED CONDITIONAL GO PARTIAL @ commit `5665b2f`, **5/8 PASS** (M5-S03 BottomSheet UI scaffold landed in v1-prep @ `06c86d7`; M5-S05 user-deferred; M5-S06+S07 v1 backlog)
- **M-W1–W8** (Foundation through Wave 8): ALL COMPLETED — Issues #6, #19, #22-#25, #28-#30 implemented.
  - **805 tests** (642 Kotlin JVM + 163 Rust), 0 failures
  - 77 Kotlin source files across 10 modules (ai, clipboard, editor, mcp, panes, search, security, skills, ssh, ui)
  - Terminal parser upgraded: UTF-8 streaming, CJK wide chars, OSC 0/2/7/8, alt screen, 256-color + truecolor SGR

To verify currency: `git log --oneline -10` and check canonical `PROJECT.md` ledger. Current `versionName = "1.0.0"` / `versionCode = 100`.

## How to resume / pick up work

**Read in this order**:

1. **This file** (`CLAUDE.md`) — you are here
2. [`.omc/m6-artifacts/M6-go-no-go.md`](.omc/m6-artifacts/M6-go-no-go.md) — **most recent close-out** (M6 + same-day carry-over closures); §5 marks all 4 v1 carry-overs as resolved
3. [`.omc/m5-artifacts/M5-go-no-go.md`](.omc/m5-artifacts/M5-go-no-go.md) — M5 PARTIAL close (5/8 PASS; S03 closed in v1-prep)
4. [`.omc/m4-artifacts/M4-go-no-go.md`](.omc/m4-artifacts/M4-go-no-go.md) — M4 close (14/15 PASS; S14 closed in v1-prep)
5. [`.omc/m3-artifacts/M3-go-no-go.md`](.omc/m3-artifacts/M3-go-no-go.md) — M3 close (12/12 PASS); per-layer GO/CONDITIONAL/NO-GO map
6. [`.omc/m2-artifacts/M2-go-no-go.md`](.omc/m2-artifacts/M2-go-no-go.md) — M2 close (12/14 PASS)
7. [`.omc/plans/ralplan-warp-on-mobile.md`](.omc/plans/ralplan-warp-on-mobile.md) — canonical plan with **5 amendments** at top (Amendment 5 = M3 cfg-gate→extraction pivot, 2026-04-30)
8. [`.omc/prd.json`](.omc/prd.json) — current milestone story states (all M0–M6 `passes:true` except M5-S05 user-deferred + M5-S06/S07 v1 backlog)
9. [`progress.txt`](progress.txt) — iteration log with lessons learned

**Do NOT** attempt to derive state from full conversation history. The close-out docs + kickoff doc are designed to be read cold.

## v1-release prep state (current focus)

Active v1-release prep work (post-M6 close):

- **Signing config**: opt-in via `android/keystore.properties` (gitignored) per `android/app/build.gradle` signingConfigs block. Without it, builds produce unsigned APK matching the F-Droid path. Generation instructions inline in build.gradle.
- **Release script**: `tools/scripts/release.sh <version> [--upload]` composes APK + bootstrap zip + SHA256SUMS + RELEASE_NOTES.md into `dist/<version>/`. Modes: build-only / `--dry-run` / `--upload` (gh release create).
- **Release CI**: `.github/workflows/release.yml` triggers on `v*` tag push. Optional signing via `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` repo secrets.
- **Block.output capture**: M3 Block model now captures stdout/stderr bytes between Preexec/CommandFinished into `Block.output: Vec<u8>` (capped 64 KB). ANSI escapes excluded via the parser state machine. Closes M5-S03 BottomSheet "(no output captured)" caveat. JNI dump JSON includes `"output"` field.
- **Color emoji**: ROOT-CAUSE DIAGNOSED — swash 0.1.19 (cosmic-text fork pin) supports COLR v0 only; modern NotoColorEmoji.ttf ships COLR v1. 4 fix paths captured in `.omc/m4-artifacts/M4-S14-result.json` post_close_diagnosis. Decision: defer to v1+1 unless emoji color is a v1.0 release blocker.

Remaining v1 backlog (NOT urgent for code-quality release):

- **M5-S05** (external tester UX review) — USER-DEFERRED, real-world tester recruitment.
- **M5-S06** (pkg.rs Rust subprocess wrapper + Kotlin progress UI) — significant scope.
- **M5-S07** (cosmetic apt list-append) — apt compile-time defaults; only visible in `apt-config dump`. Test driver explicitly excludes via grep. Truly tolerable as-is.
- **GhostSuggest cursor-anchored overlay** — replaces the AccessoryRow strip with inline grey-text overlay; needs JNI accessor for cursor screen position from Vulkan.

## User governance preferences (from CLAUDE.md global instructions)

These were established by the user explicitly and apply project-wide:

- **「全自動」** — full-auto governance. Do NOT ping user for: task assignments, strategy decisions, device runs (workers have adb access), spike redos, plan amendments, Codex review responses, repo metadata, commit messages, branch names.
- **「你自己決定」** — lead has authority to commit, push, run gh repo create, plan amendments, worker dispatch, Codex re-reviews — without re-asking.
- **Only ping user for**: irreversible operations user must approve (going public, formal v1 release, switching to Companion retreat) OR truly unrecoverable blockers.
- **Language**: 繁體中文 user-facing prose; English for code/identifiers/file paths/commit messages.
- **Tone**: short, direct, no excessive confirmation gates.

## Verifier SOP

`.omc/prd.json` has `verifierConfig.critic = "codex"`. **Every worker deliverable goes through Codex review** before story is marked passes:true. Use `omc ask codex --prompt "$(< /tmp/codex-*.md)"` (write prompt to file first to avoid zsh `()` parse errors). Background dispatch via `run_in_background: true`. Read verdict from `.omc/artifacts/ask/codex-*.md`.

## Project conventions

- **Branching**: main only. All commits push direct to main. No feature branches.
- **warp-src is gitignored**: it's a separate git repo (Warp upstream fork at `ImL1s/warp:warp-mobile/m0-facade`). Clone manually if needed (see README "Fresh-clone setup").
- **`.omc/` partial gitignore**: `plans/`, `handoffs/`, `m0-artifacts/`, `m1-artifacts/` tracked; `state/`, `artifacts/`, `notepad.md`, `project-memory.json` gitignored.
- **OMC orchestration runtime state** in `.omc/state/`: clear via `/oh-my-claudecode:cancel` when milestone closes.
- **Codex review prompts**: write to `/tmp/codex-*.md` first, dispatch via `omc ask codex --prompt "$(< file)"`.
- **Driver scripts use `am force-stop` not `am kill`** (Plan Amendment 4): `am kill` is no-op against running FGS per AOSP semantics.
- **Recommended test device classes** (per Plan Amendment 3 minSdk 31, Adreno 6xx+ baseline):
  - **Primary flagship** — Snapdragon 8 Gen 1+ / Adreno 730+ / API 33+ (e.g., Galaxy S24 Ultra, Pixel 7+)
  - **Secondary flagship** — Snapdragon 888 / Adreno 660 / API 31+ (e.g., Galaxy S21+, Pixel 6)
  - **Low-end (M2 carry-over)** — Snapdragon 730G/778G / Adreno 618-642L / API 31+ (e.g., Pixel 4a, Galaxy A52s)
  - **Below-min** — anything below API 31 / Adreno 6xx (e.g., Mali-G71-era, Galaxy S8) is dropped per Amendment 3.
  - Pass `<serial>` as first arg to all driver scripts (`tools/scripts/test-*.sh <serial>`); never hardcode serials.

## Available OMC tools (oh-my-claudecode plugin)

If `oh-my-claudecode` plugin is installed:
- `/oh-my-claudecode:ralph` — until-done loop, no state cleanup
- `/oh-my-claudecode:autopilot` — full lifecycle (Phase 0 expansion → Phase 5 cleanup); skips Phase 0+1 if ralplan exists
- `/oh-my-claudecode:cancel` — clean state and exit
- Worker agents: `executor`, `deep-executor`, `planner`, `architect`, `critic`, `verifier`, `analyst`

If you don't have the OMC plugin, you can still:
- Read all the docs in `.omc/`
- Run device tests via `tools/scripts/test-*.sh`
- Build via `cargo ndk -t arm64-v8a build` + `cd android && ./gradlew :app:assembleDebug`
- Run `omc ask codex` directly if you have Codex CLI

## Quick verification commands

```bash
# Rust tests (163 total)
cargo test -p warp-mobile-android-host                                    # → 29 passed
cargo test -p warp_ai_mobile --lib                                        # → 45 passed
cargo test -p warp_terminal_mobile_facade --manifest-path warp-src/Cargo.toml  # → 89 passed

# Kotlin JVM tests (642 total)
cd android && JAVA_HOME="/path/to/jdk-21" ./gradlew :app:testDebugUnitTest # → 642 passed, 0 failures

# Full compilation check
cd android && ./gradlew :app:compileDebugKotlin                            # → BUILD SUCCESSFUL

# Release APK size verification
cd android && ./gradlew :app:assembleRelease
du -h app/build/outputs/apk/release/app-release-unsigned.apk              # → ~53.7M
```

## What you should NOT do

- Don't skip reading `.omc/handoffs/lead-context-snapshot.md` and start fresh — you'll re-derive months of decisions and risk getting them wrong.
- Don't switch to a Compose UI — `warpui::platform::android` derived from headless is the chosen path (Plan §6 M2; Decisions A4 + D1.5-hybrid).
- Don't fork `termux-app` — we bundle `termux-packages` only.
- Don't ping the user for routine decisions — see "User governance preferences" above.
- Don't commit `.cargo/config.toml` (machine-absolute paths). The template at `.cargo/config.toml.template` is the source of truth; run `tools/scripts/setup-cargo-config.sh` to render.
- Don't commit `warp-src/` (it's a separate git repo, gitignored).

## How to start M4

When ready to begin M4 (Termux runtime: zsh + GNU coreutils + APT):

```
/oh-my-claudecode:ralph M4 milestone — Termux runtime integration per ralplan §6 M4. Bundle termux-packages (zsh + GNU coreutils + APT package manager) as APK assets; first-launch extraction; PTY spawn uses Termux shell instead of /system/bin/sh. Closes M3 carry-overs (M3-S06 hook execution, M3-S08 toybox color, M3-S08 Linux pixel similarity, M3-S11 Option D shared-rlib API split). Verifier gate: codex per existing SOP.
```

Or with autopilot (auto-detects ralplan plan, skips Phase 0+1):

```
/oh-my-claudecode:autopilot
```

**M4 entry criteria** (per M3-go-no-go.md §5 + §6):
- M3 CLOSED CONDITIONAL GO @ commit `8ec75c8` (12/12 stories PASS)
- Release APK 7.4MB (M3-S10 baseline; ~73MB headroom for Termux bundle under 80MB gate, or ~113MB under 120MB combined)
- Cherry-pick budget intact (Pre-mortem C #4 NOT TRIPPED at M3-S11; ~3-5 min/commit estimated)
- 6 cross-workspace dups (~5800 LOC) documented as Option C divergence; Option D shared-rlib API split scheduled for M4 refactor

**M4 deliverables (per ralplan §6 M4)**:
1. Termux bootstrap zip bundling (zsh + coreutils-gnu + bash + grep + find + sed + awk + ...)
2. APK asset packaging similar to M3-S06 zsh_body.sh pattern
3. First-launch extraction to `/data/data/dev.warp.mobile/files/termux/`
4. PTY spawn uses `$PREFIX/bin/zsh` instead of `/system/bin/sh`
5. F-Droid metadata for v1 release prep
6. Bootstrap zip reproducibility (deterministic build)
7. Option D shared-rlib API split (resolves the 6 cross-workspace dups from M3-S11)
8. Re-run M3-S05 colored ls -la /system AC against real GNU coreutils ls --color=auto (closes M3-S08 AC#5 deferral)
9. Live emoji raster smoke (closes M3-S11 carry-forward)

---

*Last updated: 2026-05-01 by team-lead@warp-mobile-m3 (Claude Opus 4.7, 1M context) — M2+M3 close-out + M4 ready*
