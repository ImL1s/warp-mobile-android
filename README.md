# Warp on Mobile (Android)

An open-source Android port of [Warp Terminal](https://github.com/warpdotdev/Warp) with Block-style command grouping, DCS-hook-driven Block detection, per-cell Vulkan rendering, and a bundled Termux runtime (M4+).

[![Test (Rust + Android smoke)](https://github.com/ImL1s/warp-mobile-android/actions/workflows/test.yml/badge.svg)](https://github.com/ImL1s/warp-mobile-android/actions/workflows/test.yml)

**Status**: M0–M6 CLOSED, Milestones M-W1 through M-W8 COMPLETED — 805 tests (642 Kotlin + 163 Rust) passing. Version 1.0.0 (versionCode 100). Warp **engine layer** functional on Android 12+ (PTY + Vulkan grid + DCS-hook Block model + Termux runtime + BYOK AI client + SSH + MCP + Split Panes + Security Hardening). &nbsp;|&nbsp; License: AGPL-3.0-only

> ⚠️ **What you actually see when you tap the icon today**: a fullscreen black Vulkan surface with a single mksh shell prompt at the top, the system Gboard at the bottom, and a custom modifier/symbol AccessoryRow above it. **This is engine-preview shape, not Warp Desktop shape.** The Block model + AI BYOK + Termux runtime + AGPL framework are all wired up underneath, but the user-facing chrome that makes Warp feel like Warp (agent conversation, block cards, sidebar, prompt suggestions) is the next major milestone.

---

## What is this?

[Warp Terminal](https://github.com/warpdotdev/Warp) introduced a fundamentally different terminal UX: commands and their output are grouped into discrete, navigable Blocks; output is colored and structured rather than a raw scrollback stream; and an AI layer (Haiku for inline ghost-text, Sonnet for agent) is built into the shell workflow. This project is a community-driven Android port that is **building Warp bottom-up**: the engine layer first (M0–M6), the UX layer afterward (M7+).

The port is not a thin wrapper or a Compose-based re-implementation. Warp's own `warpui` framework (a Vulkan/GPU-accelerated UI crate derived from GPUI) runs natively on-device via the Android NDK. An Android-specific backend (`warpui::platform::android`) drives a real `ANativeWindow` surface using `ash` (Vulkan), `cosmic-text` for text shaping, and Android system fonts including CJK. A foreground service manages PTY sessions; Block detection comes from the same DCS-hook parser (`ESC P $ d ... 0x9c`) that Warp's desktop shell integration uses.

Starting with M4, the port bundles a forked `termux-packages` collection so the device ships a proper Linux `$PREFIX` (zsh, GNU coreutils, APT). The terminal GUI substrate is Warp's `warpui`; the runtime is Termux's package ecosystem rehosted under this project's package name. This is not a Termux fork — we do not fork `termux-app`.

This is a solo-developer project on a 12-18 month constrained-beta timeline. It is honest-beta software: flagship Android devices show the engine working; low-end devices, full Termux integration, and the Warp-shaped UX layer are all in progress. F-Droid and GitHub Releases are the primary distribution targets; Play Store is a v3+ optional path.

---

## Current status

| Milestone | Description | State | Stories |
|-----------|-------------|-------|---------|
| **M0** | Foundation spike — Vulkan recreate, symlink-jniLibs, warpui trait diff | CLOSED ✅ | — |
| **M1** | Android PTY/FGS prototype — no UI; service + PTY plumbing only | CLOSED ✅ | 10/10 PASS |
| **M2** | `warpui::platform::android` backend — Vulkan renderer, FontDB, IME, gestures | CLOSED ✅ | 12/14 PASS |
| **M3** | Facade + DCS parser + Block model + dynamic_grid renderer | CLOSED ✅ | 12/12 PASS |
| **M4** | Termux runtime — zsh + GNU coreutils + APT + F-Droid distribution prep | CLOSED ✅ | 14/15 PASS |
| **M5** | Mobile UX polish — selection / accessory row / blocks / paste / paste UX | CLOSED PARTIAL ✅ | 5/8 PASS |
| **M6** | AI integration — Haiku inline ghost-text, Sonnet agent (BYOK) | CLOSED ✅ | 7/7 PASS |
| **M-W1–W8** | Foundation + SSH + Security + MCP + Split Panes + Skills + Test Pyramid + Release Pipeline | COMPLETED ✅ | Issues #6, #19, #22-#30 |
| **v1.1** | SELinux nativeLibraryDir refactor — `$PREFIX/bin/*` exec restored | Planned | `.omc/v1.1-plan-selinux-nativelib.md` |
| **M7–M10** | **Warp-shape UX layer** (sidebar / Block cards / agent-first prompt screen / model picker / tab manager) — see "What does NOT work yet" below | Not started | TBD |

**Primary test device**: Galaxy S24 Ultra (Snapdragon 8 Gen 3 / Adreno 750 / API 36).
**Minimum supported**: Android 12 (API 31), Adreno 6xx+ GPU (raised from API 26 in Plan Amendment 3 after Mali-G71 devices failed the 200ms swapchain gate).
Low-end Adreno 618-642L devices (Pixel 4a, Galaxy A52s) are on the roadmap but not yet verified against M3 acceptance criteria.

---

## What works today (M3 close)

All of the following are empirically verified on Galaxy S24 Ultra.

**Vulkan rendering**

- 60fps per-cell dynamic_grid renderer during active touch-drag scroll (`p95 = 13ms`, 44% margin under the 16.6ms gate; `peak_fps = 144`)
- Per-cell colored glyph rendering via `warpui::platform::android` + `cosmic-text`; CJK characters render without tofu via Android system font fallback
- Swapchain recreate across `onPause`/`onResume`/rotation tested at 100 cycles; p95 < 200ms on Adreno 6xx+

**Terminal pipeline (end-to-end)**

- Real PTY → terminal model → per-cell renderer (`ls -la /system` works: 995 glyph quads, 39 atlas glyphs, 1323 bytes ingested, 19 visible rows)
- SGR ANSI color (RED/GREEN/BLUE/reset) correctly routed through the renderer; toybox `ls` on stock Android does not emit ANSI colors — GNU coreutils `ls --color=auto` via Termux (M5) closes that gap
- Scrollback ring buffer: ≥1000 lines retained; 2000 lines injected → 1000 retained correctly

**Block model**

- DCS hook parser extracted from upstream Warp (`ESC P $ d ... 0x9c` frame sequence); `dcs_hook_count = 9`, `dcs_error_count = 0` in device smoke test
- `Block` objects produced with `start_time`, `command`, `exit_code`; 3-command test (`ls`, `whoami`, `false`) yields `exit_codes = [0, 0, 1]`
- `terminalBlocksDump` JNI export produces JSON-serialized Block list accessible from Kotlin

**Gestures and input**

- Touch-drag scroll: 195 distinct clamped offset positions observed over 5s gesture; fling momentum via Android `GestureDetector`
- Gboard (English + Pinyin) IME: one character per keystroke on editable region; composing-text (Chinese) updates in-place without flicker
- `WindowInsets` correctly reserves bottom region for IME; full-screen mode hides nav bar

**APK size**

- Release APK: **7.4 MB** (7,775,816 bytes); 90.7% margin under the 80 MB gate
- Combined APK + bootstrap: 7.4 MB today; ~73 MB headroom for the Termux bundle planned in M4
- Vulkan validation layer absent from release build

**Upstream compatibility**

- Cherry-pick dry-run against 10 upstream `warpdotdev/Warp` commits: 3 conflicting files (1 in `app/`, 1 in `warpui/`, 1 in `warpui_core/`); estimated full resolution 25-50 min

---

## What does NOT work yet (M7+ scope)

These are the things a user familiar with Warp Desktop will notice missing the moment they tap the icon. They are real gaps, not polish:

| Missing UX | What's there now | Plan |
|---|---|---|
| Sidebar with sessions / agents / files | Single fullscreen surface, no sidebar | M7 |
| Top search bar ("Search sessions, agents, files…") | None | M7 |
| Agent-first prompt screen ("New Oz agent conversation") | Raw mksh prompt | M9 |
| Block rendered as interactive card with copy / explain / re-run | Flat Vulkan grid text | M8 |
| Bottom prompt box ("Warp anything…") with autocomplete + model picker | System Gboard + AccessoryRow | M9 |
| `/model`, `/remote-control`, command palette | None | M9 |
| Tab management | None — single grid | M7 |
| `$PREFIX/bin/*` commands runnable (zsh, ls, cat, …) | Blocked by SELinux `app_data_file` exec denial | v1.1 (`.omc/v1.1-plan-selinux-nativelib.md` — relocate Termux binaries into `nativeLibraryDir`) |

The engine pieces are in place: Block aggregation logic, BYOK Anthropic client (Haiku ghost-text + Sonnet agent), Termux bootstrap zip, font shaping with CJK, IME → PTY input pipeline. M7+ work is to surface them through a Warp-shaped chrome layer instead of the current bare Vulkan grid + Gboard.

See `.omc/v1-prep-uiux-verification.md` for screenshots of the current launcher path and the gap-analysis honest accounting that drove this section.

---

## Architecture

The project follows a 5-layer model:

```
L0  Android Host Service        Activity / Service lifecycle, FGS notification, JNI shim,
                                IME, clipboard. Rust crate: crates/android-host/.
                                (M1 closed: PTY plumbing, 45 unit tests passing)

L1  WarpUI Android backend      warpui::platform::android — Vulkan (ash + ANativeWindow),
                                FontDB (cosmic-text + system fonts), TextLayoutSystem,
                                IME input, gestures, rotation lifecycle.
                                Derived from Warp's headless backend; 4 hand-written areas.
                                (M2 closed: static_grid + dynamic_grid; M3 adds per-cell DCS)

L2a Terminal crates             crates/warp_terminal and clean dependencies — reused
                                largely as-is from upstream Warp (warp-src/). No Android
                                modifications needed.

L2b Warp facade                 warp_terminal_mobile_facade — wraps the app::terminal::*
                                subset needed on Android (Block, BlockList, ANSI/DCS parser,
                                Session API, AppContext, FeatureFlag, SSH-noop stub).
                                Compiled via cargo ndk; app/ crate NOT in Android build graph
                                (Plan Amendment 5: extraction, not cfg-gating).
                                (M3 closed: 7 modules + extracted app_terminal::* subtree)

L3  Termux Runtime              Fork of termux-packages retargeted to dev.warp.mobile prefix.
                                Bootstrap zip bundled in APK; first-launch extraction to
                                /data/data/dev.warp.mobile/files/termux/.
                                (M4 in progress: zsh + GNU coreutils + APT)
```

Cloud AI (Anthropic API — Haiku + Sonnet) runs as a separate concern, not a layer. User-supplied API key (BYOK). Planned for M6; AGPL §13 not triggered by client-only API consumption.

**Plan Amendment 5 (M3)**: the original plan gated `app::terminal::*` desktop-only code paths with `#[cfg]` so the full `app/` crate could build for Android. Empirical measurement found 41 cfg-gate lines yielded 145 compile errors across 19 `app/` subsystems — architecture mismatch, not a budget overrun. Amendment 5 pivoted to extraction: the relevant `Block`, `BlockList`, DCS parser, and ANSI dispatch types are extracted into `warp_terminal_mobile_facade::app_terminal::*`. The `app/` crate does not appear in the Android build graph at all.

For the full plan including all 5 amendments and M0-M6 acceptance criteria, see [`.omc/plans/ralplan-warp-on-mobile.md`](.omc/plans/ralplan-warp-on-mobile.md).

Upstream project: [warpdotdev/Warp](https://github.com/warpdotdev/Warp) (also AGPL-3.0).

---

## Building locally

### Prerequisites

- **Rust** 1.88+ with target `aarch64-linux-android` (`rustup target add aarch64-linux-android`)
- **cargo-ndk** 4.1+ (`cargo install cargo-ndk`)
- **Android NDK** r25c or newer (r29 also works; set `ANDROID_NDK_HOME`)
- **Android SDK** with build-tools 34+ and platform `android-36`
- **JDK 17** (for Gradle)
- **bash** 4.2+ (macOS ships bash 3; install via Homebrew: `brew install bash`)
- **direnv** (recommended) — auto-loads `.envrc` with NDK/SDK paths

### Fresh-clone setup

```bash
# 1. Clone this repo
git clone https://github.com/ImL1s/warp-mobile-android.git
cd warp-mobile-android

# 2. Clone the Warp upstream fork (gitignored — separate git repo)
git clone https://github.com/ImL1s/warp warp-src
cd warp-src && git checkout warp-mobile/m0-facade && cd ..

# 3. (M4+) Clone termux-packages fork (gitignored — separate git repo)
git clone https://github.com/ImL1s/termux-packages termux-packages
cd termux-packages && git checkout warp-mobile/main && cd ..

# 4. Render the local Cargo config from the checked-in template
#    (the template avoids committing machine-absolute NDK paths)
bash tools/scripts/setup-cargo-config.sh

# 5. Build the native Rust library for Android arm64
cargo ndk -t arm64-v8a build -p warp-mobile-android-host

# 6. Build the debug APK
cd android && ./gradlew :app:assembleDebug && cd ..

# 7. Install on a connected device (API 31+, Adreno 6xx+)
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.warp.mobile/.MainActivity
```

### Build sanity checks

```bash
# Host-side unit tests (45 passing at M3 close)
cargo test -p warp-mobile-android-host

# Facade tests in warp-src workspace (73 passing at M3 close)
cargo test -p warp_terminal_mobile_facade --manifest-path warp-src/Cargo.toml

# Release APK size check (should be ~7.4 MB at M3 baseline)
cd android && ./gradlew :app:assembleRelease
du -h app/build/outputs/apk/release/app-release-unsigned.apk
```

### Cutting a release (v1-prep)

Solo-dev release packaging composes signed (or unsigned) APK + bootstrap zip + SHA256SUMS into one drop:

```bash
# Build artifacts only (no upload)
./tools/scripts/release.sh 1.0.0

# Or build + push to GitHub Releases (requires gh CLI auth + tag pushed)
git tag -a v1.0.0 -m "v1.0.0 release baseline"
git push origin v1.0.0
./tools/scripts/release.sh 1.0.0 --upload
```

For signed APKs, populate `android/keystore.properties` (gitignored — see `android/app/build.gradle` for the format). Without it, the script produces an unsigned APK matching the F-Droid build path. CI workflow `.github/workflows/release.yml` does the same on every `v*` tag push, with optional signing via `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` repo secrets.

### Building the Termux bootstrap zip (M4+)

The Termux runtime layer (zsh, GNU coreutils, APT) is shipped as a `bootstrap-aarch64.zip` extracted at first launch into `/data/data/dev.warp.mobile/files/usr/`. Building this zip is fully automated and free — no Android SDK, no Docker, no Rust toolchain required for this step. Byte-stable reproducibility (rebuilding at a fixed upstream snapshot to produce identical SHA256) is M4-S08 work.

**Required tooling**: `bash`, `python3`, `curl`, `tar`, `xz`, `zip`, `unzip`, `find`, `grep`, `sed`, `awk`, `file`, `sha256sum`, and `patchelf`. All are stock on Linux distros except `patchelf` (`sudo apt install patchelf`); on macOS install via Homebrew (`brew install bash coreutils patchelf`). The script verifies these on startup and fails with a clear message if anything is missing.

**Local build** (on your dev machine):

```bash
# Default: aarch64, full 7-package list (bash, zsh, coreutils-gnu,
# findutils, apt, pkg, git + all transitive deps). Output goes to $PWD.
./tools/scripts/build-bootstrap.sh

# Or with explicit args (arch, package list, output dir):
./tools/scripts/build-bootstrap.sh aarch64 \
    tools/scripts/m4-bootstrap-packages.txt \
    "$PWD/_bootstrap-out"

# Output (in the chosen output dir):
#   bootstrap-aarch64.zip          ~43 MB, contains the entire $PREFIX rootfs
#   bootstrap-metadata.json        size, sha256, package count, retargeting stats
```

The script downloads upstream Termux prebuilt `.deb` packages from `packages-cf.termux.dev` (the same source Termux's own CI uses for fast `generate-bootstraps.sh` runs), retargets paths from `com.termux` to `dev.warp.mobile` across:

- **Shell scripts and config files**: literal-string sed replacement (`/data/data/com.termux/...` → `/data/data/dev.warp.mobile/...`). 215 text files rewritten on the canonical 7-package list.
- **ELF dynamic-linker paths**: `patchelf --set-rpath` rewrites the `DT_RUNPATH` entry on every shared object and executable so libs resolve at `/data/data/dev.warp.mobile/files/usr/lib` without needing `LD_LIBRARY_PATH` at every spawn. 307 ELF binaries patched.
- **Absolute symlink targets**: 20 symlinks pointing into `/data/data/com.termux/...` are rewritten to point at `/data/data/dev.warp.mobile/...` and stored in `SYMLINKS.txt` sidecar (the format the Termux app extractor expects).

Residual `com.termux` strings in ~116 files are compile-time config defaults (zsh `module_path`, git `libexec/git-core`, OpenSSL CA path, terminfo path, locale path, etc.). These are not dynamic-linker concerns — they're runtime defaults overridable via either shell-array assignment in `$ZDOTDIR/.zshenv` (for zsh-specific paths like `module_path` which ignores the env var) or env vars at PTY spawn (`GIT_EXEC_PATH`, `SSL_CERT_FILE`, `TERMINFO`, `LOCPATH`, `HOME`, `ZDOTDIR`). M4-S06 wires the user-shell side; M4-S07 covers the package-manager side.

See [`.omc/m4-artifacts/M4-S03-strategy.md`](.omc/m4-artifacts/M4-S03-strategy.md) for the full strategy decision and rationale.

**CI build** (on GitHub Actions, free):

```bash
# From the repo, push a change to the workflow or package list:
git push
# Watch the run:
gh run watch --workflow=build-bootstrap.yml
# Download the artifact:
gh run download --name=bootstrap-aarch64
```

The CI workflow ([`.github/workflows/build-bootstrap.yml`](.github/workflows/build-bootstrap.yml)) calls the same `build-bootstrap.sh` script on `ubuntu-latest`, completes in ~5 min, uses zero external services, and uploads the zip + metadata as a workflow artifact (30-day retention).

### Device test drivers

All device drivers take `<serial>` as the first argument — never hardcoded. Find your serial with `adb devices`.

```bash
# PTY reattach across rotation (M1 acceptance #2)
bash tools/scripts/test-pty-reattach.sh <serial>

# DCS hook parser + ANSI color smoke (M3-S05)
bash tools/scripts/test-ansi-color.sh <serial>

# Per-cell renderer + live ls -la /system (M3-S08)
bash tools/scripts/test-dynamic-grid.sh <serial>

# Block model via DCS hook (M3-S07)
bash tools/scripts/test-block-model.sh <serial>

# Scrollback 1000 lines + 60fps touch-drag scroll (M3-S09)
bash tools/scripts/test-scroll.sh <serial>
```

---

## Project structure

```
warp-mobile-android/
├── Cargo.toml                  Rust workspace root
├── LICENSE-AGPL                AGPL-3.0-only (inherited from warpdotdev/Warp)
├── NOTICE.md                   Third-party attributions and license obligations
├── CLAUDE.md                   AI agent entry point (if you're an AI session, read this)
├── progress.txt                Iteration log with lessons learned
│
├── crates/
│   └── android-host/           Rust JNI host (~48 exported functions): PTY, renderer,
│                               IME, gestures, Block aggregation, dynamic_grid
│
├── android/                    Gradle project
│   └── app/src/main/java/
│       └── dev/warp/mobile/    Kotlin: MainActivity, WarpTerminalService,
│                               PtyManager, NativeBridge, WarpInputView, ...
│
├── tools/
│   └── scripts/                Device test drivers (test-*.sh <serial>)
│
├── spikes/                     M0 spike crates (vulkan-surface-recreate, symlink-jnilibs)
│
├── warp-src/                   GITIGNORED — Warp upstream fork (separate git repo)
│                               Clone: git clone ImL1s/warp → checkout warp-mobile/m0-facade
│
├── termux-packages/            GITIGNORED — Termux fork (separate git repo, M4+)
│                               Clone: git clone ImL1s/termux-packages → checkout warp-mobile/main
│
└── .omc/
    ├── plans/                  Canonical RALPLAN with 5 amendments
    ├── m0-artifacts/           M0 evidence + go/no-go
    ├── m1-artifacts/           M1 evidence + go/no-go (10/10 PASS)
    ├── m2-artifacts/           M2 evidence + go/no-go (12/14 PASS)
    └── m3-artifacts/           M3 evidence + go/no-go (12/12 PASS)
```

Note: `.cargo/config.toml` is gitignored because it contains machine-absolute NDK paths. The template at `.cargo/config.toml.template` is the source of truth; run `tools/scripts/setup-cargo-config.sh` to render it.

---

## Contributing

This project is AGPL-3.0-only. Any derivative work you distribute — including modified APKs — must also be AGPL-3.0. See [`LICENSE-AGPL`](LICENSE-AGPL) and [`NOTICE.md`](NOTICE.md) for the full obligation summary.

The project is currently solo-developer driven. Pull requests are welcome, but please open an Issue first to coordinate — changes that conflict with the active milestone scope are likely to be deferred. The milestone breakdown and acceptance criteria are in [`.omc/plans/ralplan-warp-on-mobile.md`](.omc/plans/ralplan-warp-on-mobile.md) §6.

Distribution: F-Droid + GitHub Releases are the primary targets. F-Droid metadata (`fastlane/metadata/android/` + reproducible build declaration) is a M4 deliverable. Play Store is a v3+ optional path.

---

## License and acknowledgments

**License**: AGPL-3.0-only. See [`LICENSE-AGPL`](LICENSE-AGPL).

This project is built on:

- [warpdotdev/Warp](https://github.com/warpdotdev/Warp) — parent project; `warpui`, `warp_terminal`, `warpui_core`, and the DCS hook parser are reused and extended. Also AGPL-3.0.
- [termux/termux-packages](https://github.com/termux/termux-packages) — package ecosystem (GPL-3.0-or-later). Forked and retargeted to `dev.warp.mobile` prefix for M4+ bundled runtime. Binary distributions include corresponding source per AGPL §6 + GPL obligations.
- [pop-os/cosmic-text](https://github.com/pop-os/cosmic-text) — text shaping and layout used in the `FontDB` and `TextLayoutSystem` implementations inside `warpui::platform::android`.

Architecture reference (no code copied): [termux/termux-app](https://github.com/termux/termux-app) (GPL-3.0-only) for Android terminal app patterns and [itsbalamurali/gpui-mobile](https://github.com/itsbalamurali/gpui-mobile) for `ANativeWindow` / platform trait patterns.

See [`NOTICE.md`](NOTICE.md) for the full attribution table including pinned upstream commit hashes and per-project license obligation notes.
