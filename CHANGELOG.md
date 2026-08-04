# Changelog

All notable changes to Warp Mobile Android. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to [Semantic Versioning](https://semver.org/) for the v1+ series. Pre-v1 versions follow the solo-dev scheme `<major>.<milestone>.<patch>-m<N>` where `major=0`, `milestone=highest closed milestone`, `m<N>` matches the milestone tag.

For per-milestone narrative + ledger detail (codex review rounds, per-layer GO/CONDITIONAL/NO-GO, carry-over rationale), see the close-out docs:

- [`.omc/m6-artifacts/M6-go-no-go.md`](.omc/m6-artifacts/M6-go-no-go.md)
- [`.omc/m5-artifacts/M5-go-no-go.md`](.omc/m5-artifacts/M5-go-no-go.md)
- [`.omc/m4-artifacts/M4-go-no-go.md`](.omc/m4-artifacts/M4-go-no-go.md)
- [`.omc/m3-artifacts/M3-go-no-go.md`](.omc/m3-artifacts/M3-go-no-go.md)
- [`.omc/m2-artifacts/M2-go-no-go.md`](.omc/m2-artifacts/M2-go-no-go.md)
- [`.omc/m1-artifacts/M1-go-no-go.md`](.omc/m1-artifacts/M1-go-no-go.md)
- [`.omc/m0-artifacts/M0-go-no-go.md`](.omc/m0-artifacts/M0-go-no-go.md)

For per-iteration lessons learned, see [`progress.txt`](progress.txt).

## [Unreleased]

### Added (Wave 6-8: Issues #19, #22-#25, #28-#30)

- **Secure SSH Remote Sessions (#22)**: Rust state machine model (`ssh_client.rs`) with `SshConnectionConfig`, `SshAuthMethod`, `SshSession`, `SshHostKeyVerification`. Kotlin `SshConnectionManager`, `HostKeyVerificationDialog`, `SshSessionViewModel`.
- **Security Hardening (#23)**: `LogcatSanitizer` (regex-based API key/token/password redaction), `SecureLogger` (auto-sanitizing Log wrapper), `KeyStoreManager` (AES-256-GCM with Android KeyStore). `security-audit.sh` CI script.
- **MCP Client/Server Manager (#29)**: JSON-RPC 2.0 message types (`McpMessage`), `McpToolRegistry` with permission gating, `McpTransport` interface with `StdioTransport` implementation.
- **Split Panes & Launch Config (#30)**: `PaneLayout` calculator (horizontal/vertical splits), `LaunchConfig` JSON serialization (per-pane cwd/env/startupCommand), `PaneInputRouter` (focus-based input routing).
- **Project Rules & Local Skills (#28)**: `.warprules` file parser (`ProjectRulesParser`), local skill registry scanning `.warp/skills/` with case-insensitive keyword matching.
- **Adaptive Layouts & Accessibility (#19)**: `WindowSizeClassResolver`, `WarpAdaptiveLayout`, `FoldablePostureHandler`, `DeXLayoutHandler`, `WarpAccessibilityNodeProvider`.
- **Deterministic Test Pyramid (#24)**: 805 tests (642 Kotlin JVM + 163 Rust) across 4 tiers, 0 failures.
- **Release Pipeline (#25)**: `release.sh` with APK + bootstrap zip + SHA256SUMS packaging, `ReleasePipelineValidationTest.kt`.

### Changed

- **Terminal parser upgraded**: UTF-8 streaming (`utf8_buf`), CJK wide char support (`unicode-width`, `ATTR_WIDE_CONTINUATION`), grapheme cluster rendering (`Cell.grapheme: Option<String>`), OSC 0/2 (title), OSC 7 (cwd), OSC 8 (hyperlinks), DECSET 1049/47/1047 alt screen tracking, 256-color (`38;5;N`), truecolor (`38;2;R;G;B`), colon-separated SGR subparameters, strikethrough (SGR 9/29).
- **CI workflow upgraded**: JDK 17 → 21, compile-only → full 642-test unit test suite, `testDebugUnitTest` replaces `compileDebugKotlin`.
- **Fastlane changelog** (`changelogs/100.txt`) updated with Wave 6-8 features.

## [1.0.0] — 2026-08-03 — Milestone M-W1 Task 1 Release Reconciliation Baseline

### Changed
- **Version & Spec Reconciliation (Issue #6)**: Reconciled project version across Cargo workspace (`1.0.0`), Gradle build configuration (`versionCode 100`, `versionName "1.0.0"`), F-Droid recipe (`metadata/dev.warp.mobile.yml`), Fastlane changelogs (`fastlane/metadata/android/en-US/changelogs/100.txt`), release scripts (`tools/scripts/release.sh`), GitHub workflows (`.github/workflows/release.yml`), issue templates (`.github/ISSUE_TEMPLATE/bug_report.yml`), and documentation (`README.md`, `CLAUDE.md`, `PROJECT.md`).
- **Canonical Ledger**: Closed Issue #6 and updated Milestone M-W1 Task 1 ledger status in `PROJECT.md`.

## [0.6.0-m6-v1prep] — 2026-05-02

In progress: engine layer + launcher-path UIUX + IME→PTY input now functional on Galaxy S24 Ultra. v1.0 ship is **gated on M7-M10** (Warp-shape UX layer — sidebar, agent-first prompt screen, Block-as-card rendering, model picker, tab manager) per `.omc/m7-plan-warp-ux-layer.md`. Keystore generation + F-Droid recipe submission still gated on user.

### Fixed (iteration 19, 2026-05-02 — IME → PTY input)

- **Gboard typing now reaches the spawned shell** (commit `c6a7359`, "Blocker #4"). Previously every key tap on Gboard updated only the M2-S10 IME stats counter (the Rust `ime::commit_text` driver-test instrumentation) and never wrote any bytes to the PTY. Every prior "device-verified" device test bypassed the InputConnection entirely by sending `am broadcast PTY_WRITE --es data …`, so this regression had been latent since M2 and was not caught by any milestone gate. `WarpInputConnection.commitText` / `sendKeyEvent` / `deleteSurroundingText` now broadcast to the active PTY cmd_id via the existing `PTY_WRITE` pipeline. ASCII DEL (0x7F) for Backspace, `\n` for Enter, `\t` for Tab, `\x1B` for Escape, `ESC[A/B/C/D` for arrow keys, `ESC[3~` for forward-delete, Ctrl+letter → 0x01..0x1F. Verified: 4 IC.commitText → 4 PTY_WRITE → 4 echoed chars on Galaxy S24 Ultra (no double-write). Screenshots `12-` and `13-` in `.omc/v1-prep-screenshots/`.

### Documented (iteration 19, 2026-05-02 — Warp UX layer plan)

- **`.omc/m7-plan-warp-ux-layer.md`** — explicit plan to build the Warp UX layer (sidebar / Block cards / agent-first prompt screen / model picker / tab manager) that M0–M6 deliberately punted on. Recommends Option A (Compose chrome around the existing Vulkan terminal pane), spans 4 milestones (M7-M10) at 7-10 weeks solo-dev. Lists 5 open architectural questions + 4 user decision gates needed before M7-S01.
- **README** reframed: explicit "What does NOT work yet (M7+ scope)" table; status line clarifies engine-layer vs UX-layer; current-status table extended through v1-prep / v1.1 / M7-M10. Removes the implicit "v1.0 imminent" framing of the prior README.

### Fixed (iteration 18, 2026-05-02 — launcher-path UIUX)

- **Plain launcher Intent now produces a working terminal.** Previously the `android.intent.action.MAIN` / `category.LAUNCHER` Intent reached `MainActivity` with no extras, hit the `terminal_mode=false` branch, and rendered the Vulkan magenta clear color forever — never spawning a shell. `MainActivity.kt` now treats the no-extras path as default-on `terminal_mode` + auto-spawn of the configured shell, and defaults to fullscreen so the status bar doesn't overlay row 0. Driver-style `--ez terminal_mode true …` invocations are unchanged.
- **Grid sized from `displayMetrics`** instead of the VT100-shaped hardcoded 80×24. On a 1080×2340 portrait flagship the launcher path now produces a 45-col × 54-row grid; the 24×40 px cell defaults still apply when no driver overrides are supplied.
- **Configured shell that fails fast falls back to `/system/bin/sh`.** When the auto-spawned `$PREFIX/bin/zsh` dies within 1.5 s of spawn, `WarpTerminalService` respawns mksh under the same cmd_id with the same env, sending `ESC[2J ESC[H` first so the failed shell's stderr diagnostic doesn't bleed into the user-facing grid.

### Diagnosed (iteration 18, 2026-05-02 — root cause of "zsh dies in PTY")

`execve` on `$PREFIX/bin/zsh` returns EACCES (errno 13). Android's SELinux policy denies the `untrusted_app` domain `execute` access to `app_data_file`-labelled binaries since API 29 (`neverallow untrusted_app app_data_file:file execute`). The bundled `$PREFIX/bin/*` tree is `app_data_file`-labelled, so zsh — and GNU coreutils + every other binary under `/data/user/0/dev.warp.mobile/files/usr/bin/` — cannot be exec'd from the app's own foreground-service process. v1.0 ships with the mksh fallback as the user-facing shell; v1.1 will load Termux binaries from `nativeLibraryDir` (`system_lib_file`-labelled, exec-allowed) so `$PREFIX/bin/*` becomes runnable again.

### Added

- **GitHub Actions test CI** (`.github/workflows/test.yml`) — runs 157 host tests (66 host + 18 ai client + 73 facade) + Android Kotlin compile smoke + `cargo audit --deny warnings` on every push to main + every PR. Concurrency cancels superseded PR runs. paths-ignore skips doc-only commits.
- **GitHub Actions release CI** (`.github/workflows/release.yml`) — fires on `v*` tag push. Builds signed APK + bootstrap zip + SHA256SUMS + RELEASE_NOTES.md + creates gh release. Optional signing via `KEYSTORE_BASE64` repo secret; without it, ships unsigned APK matching F-Droid path.
- **Local release packaging script** (`tools/scripts/release.sh <version> [--upload] [--dry-run]`) — composes APK + bootstrap zip + SHA256SUMS + RELEASE_NOTES.md into `dist/<version>/`. Guards: version-mismatch check, dirty-tree refusal (override via `ALLOW_DIRTY_TREE=1`), tag-existence verification, gh-auth check.
- **Opt-in release signing config** (`android/app/build.gradle`) — reads from `android/keystore.properties` (gitignored). Without it, builds produce unsigned APK matching F-Droid path. Generation instructions inline in build.gradle.
- **Block output capture** (`Block.output: Vec<u8>` in mirror Block model) — captures stdout/stderr bytes between Preexec and CommandFinished, capped at 64 KB. ANSI escapes correctly excluded by the parser state machine. JSON dump includes `"output"` field. Closes the M5-S03 BlockActionsSheet "(no output captured)" caveat. M6-S04 round-2 Explain path now forwards real shell context to Sonnet.
- **Color emoji on Samsung devices** — `font_render` routes emoji-classified glyphs to `SamsungColorEmoji.ttf` (CBDT/CBLC bitmaps; swash 0.1.x decodes these correctly) instead of stock `NotoColorEmoji.ttf` (COLR v1; not yet decoded by swash 0.1.x). Zero APK size cost. On non-Samsung devices, falls back to monochrome Noto emoji until cosmic-text upstream absorbs swash 0.2.
- **Pure `pick_emoji_family()` helper** (`crates/android-host/src/font_picker.rs`) with 9 unit tests covering Samsung detection, Pixel fallback, OEM naming variants, case-insensitivity, and edge cases.

### Changed

- **APK release size**: 55 MB → **53.7 MB**. libwarp.so packaged: 8.6 MB → **4.5 MB** (-48%). Combined effect of:
  - `strip = "symbols"` removes debug + non-dynamic symbol tables (-2.6 MB packaged)
  - `lto = "fat"` + `codegen-units = 1` cross-crate inlining + DCE (-1.4 MB packaged)
  - `panic = "abort"` removes unwind tables (small)
- **Bootstrap snapshot pin** refreshed `a1a64f56 → a85e7511` (M4-S08 reproducibility gate; upstream Termux apt repo drifted since the original pin). Repo now buildable from clean clone without manual pin update.
- **Version bump**: `0.1.0-m1` → `0.6.0-m6` / versionCode 6 (was stale at M1).
- **Android targetSdk + JDK**: targetSdk 36, compileSdk 36, JDK 17, NDK r29 (29.0.13113456). minSdk 31 (Adreno 6xx baseline per Plan Amendment 3).
- **GHA workflows opted into Node.js 24** ahead of June 2026 forced switch (Node 20 removal Sept 16, 2026).

### Fixed

- **GhostSuggestController self-cancel cascade** — `fireSuggestion`'s defensive `cancelActiveStream()` at top called `debounceJob?.cancel()`, which cancelled the very coroutine running fireSuggestion. The CancellationException fired at the next `delay()` in the poll loop, finally compareAndSet'd + freed the handle within ~1 ms of starting. Stream never reached `:DONE:`. Hidden because the test API key always 401'd in ~600 ms after the orphan tokio task started — the 401 surfaced as the only outcome, masking the fact that no successful path could ever complete with a valid key. Fixed by removing the redundant call (scheduleSuggestion already covered the leftover-stream case).
- **AccessoryRow TOCTOU UAF** on `activeStreamHandle` — `cancelActiveStream` read handle, poll-loop finally freed Arc, cancel called Cancel on freed handle. Fixed by atomic-claim via `AtomicLong.getAndSet(0L)`.
- **GhostSuggestController state-mutation race** — `current = current.copy(...)` from 3 threads (UI / debounce / poll loop) lost writes. Fixed by `synchronized(stateLock)` wrap in a `mutateState(transform)` helper.
- **GhostSuggestController accept-fallback duplicate input** — when LLM rewrote the partial command, accept emitted full suggestion AFTER what user already typed → garbled append. Fixed by prepending Ctrl-U (clear-line) before the full-suggestion fallback.
- **GhostSuggestController buffer drift on backspace** — IME `deleteSurroundingText` + `KEYCODE_DEL` weren't tracked, so the controller's buffer stayed stuck at "lsx" after user backspaced to "ls". Fixed by hooking both delete paths.
- **Cross-platform errno** in `pty.rs` test — `libc::__error()` is macOS-specific. CI on Linux failed with `cannot find function __error`. Replaced with portable `io::Error::last_os_error().raw_os_error()`.

### Verified

- **CI green** end-to-end on Linux ubuntu-latest: 157/157 host tests + Android Kotlin compile + cargo audit clean (276 deps, 0 vulns).
- **Device verified** on Galaxy S24 Ultra R5CX10VFFBA: cold-start, all M6 features (BYOK, ghost-text streaming, agent task, telemetry, offline degrade, color emoji, BlockActionsSheet, IME debounced auto-trigger).

---

## [0.6.0-m6] — 2026-05-02 — M6 close

**Closing commit**: `40954d7` (`m6-s07: M6 milestone CLOSED CONDITIONAL GO 7/7 stories PASS`)
**Verdict**: CONDITIONAL GO. 7/7 M6 stories PASS. All 4 same-day carry-overs closed.

### Added

- **BYOK SettingsActivity** with EncryptedSharedPreferences AES256-GCM Keystore-backed master key (`warp-ai-key-v1`); FLAG_SECURE; redact() + scrub regex defenses; in-app ⚙ entry point preserves `exported=false`.
- **Ghost-text streaming via Claude Haiku** — Rust `warp_ai_mobile` crate (reqwest 0.12 + rustls-tls-webpki-roots + tokio); SSE parser; tokio runtime singleton via `OnceLock`; Arc<StreamHandle> across FFI; 5-fn JNI surface (Start/Poll/Cancel/Free); ESC button cancels in-flight stream.
- **Agent task UI (AgentBlockSheet)** — Dialog with FEATURE_NO_TITLE, Sonnet model + 2000-token cap; system preamble marks shell-context as DATA per death-pit #1 (prompt injection).
- **Offline degrade** (`AiConnectivity`) — ConnectivityManager.NetworkCallback singleton; NET_CAPABILITY_VALIDATED + INTERNET; pre-flight `isOnline()` on 💡 / 🤖 / Test paths + listener-based button grey-out within ~1s of network loss.
- **Telemetry** (`AiUsageTracker`) — AtomicLong session counters + 100-sample p95 latency rolling window per kind; opt-in CSV log to `$PREFIX/var/log/warp-ai-usage.csv`; Settings shows snapshot + reset button; cost-warning footer with concrete per-call estimates.

### Carry-overs closed same-day

- **#1 IME-bound ghost auto-trigger + Tab-accept** (`4f010c7` + `dcce36f` + `c1dc07a` + `fa0171a`) — `GhostSuggestController` debounced typing → Haiku stream pipeline; AccessoryRow ghost strip; Tab button accepts with suffix-or-Ctrl-U-clear emission.
- **#2 Real Block context for agent** (`06c86d7`) — `BlockActionsSheet` (📋 button) → `AgentBlockSheet` with XML-tagged `<command>` + `<output>` composedPrompt.
- **#3 Listener-based offline grey-out** (`8c3ffa1`) — `AccessoryRow` registers `AiConnectivity.Listener` in `onAttachedToWindow`; toggles button `isEnabled`+alpha on connectivity changes within ~200ms of recovery.
- **#4 Round-2 prompt-injection hardening** (`06c86d7`) — XML-tagged delimiters in BlockActionsSheet's composedPrompt.

---

## [M5 — Mobile UX] — 2026-05-01

**Closing commit**: `5665b2f`
**Verdict**: CONDITIONAL GO PARTIAL — 5/8 stories PASS. M5-S03 BottomSheet UI scaffold landed in v1-prep follow-up (`06c86d7`); M5-S05 user-deferred (real-world tester recruitment); M5-S06 + M5-S07 deferred to v1-release-prep / v1+1.

### Added

- **AccessoryRow** keyboard accessory bar above IME panel — Esc/Tab/Ctrl/Alt/↑↓←→/14 punctuation buttons + Copy All / Paste / 📋 / ⚙ / 💡 / 🤖 / 🎤 (placeholder).
- **ClipboardManager paste streaming** — 4 KB chunks + 1 ms gap so the PTY canonical-mode line buffer doesn't drop bytes on 10K+ char pastes.
- **Selection state machine** (`warp_mobile_android_link::selection`) — cell-coordinate space, scroll-independent, 11 host tests. Touch wiring + Vulkan rect overlay deferred to v1+1.
- **GestureRecognizer state machine** (`warp_mobile_android_link::gestures`) — tap/long-press/swipe-right discrimination, 12 host tests. Touch dispatch + BottomSheetDialog UI deferred (📋 button entry shipped in v1-prep as workaround).
- **Sticky modifier UX** — Ctrl/Alt buttons toggle for next keystroke; visual highlight when pending.
- **EXTRA_IS_SENSITIVE clipboard flag** on Android 13+ for Copy All — terminal output may contain secrets.

---

## [M4 — Termux runtime] — 2026-05-01

**Closing commit**: `de26e3a`
**Verdict**: CONDITIONAL GO. 14/15 stories PASS (M4-S14 closed in v1-prep follow-up `1e732c5`).

### Added

- **Termux bootstrap zip pipeline** (`tools/scripts/build-bootstrap.sh`) — downloads upstream Termux .debs from packages-cf.termux.dev, retargets `com.termux` → `dev.warp.mobile` paths via text sed + `patchelf --set-rpath` + symlink rewrite, packs into a Termux-app-compatible zip.
- **First-launch atomic extraction** to `/data/data/dev.warp.mobile/files/usr/`.
- **PTY spawn uses `$PREFIX/bin/zsh`** instead of `/system/bin/sh`.
- **APT runtime config** (`apt.conf`) overriding Termux's compile-time `Dir::*` defaults to dev.warp.mobile prefix.
- **Bootstrap zip reproducibility** (`tools/scripts/m4-bootstrap-snapshot.sha256`) — pinned to upstream Termux apt revision; `mtime` + zip-entry-order normalized; identical bytes across rebuilds at the same pin.
- **F-Droid metadata** (`metadata/dev.warp.mobile.yml`) + reproducible build recipe.
- **GHA bootstrap CI** (`.github/workflows/build-bootstrap.yml`).

---

## [M3 — Layer 2b: facade + DCS + Block + dynamic_grid] — 2026-04-30

**Closing commit**: `8ec75c8`
**Verdict**: CONDITIONAL GO. 12/12 stories PASS. 27 codex review rounds.

### Added

- **`warp_terminal_mobile_facade`** crate — extracted from upstream `app::terminal::model` (Plan Amendment 5: cfg-gate→extraction pivot at story M3-S03).
- **DCS hook parser** (`ESC P $ d ... 0x9c`) — drives Block model from zsh shell-integration emissions.
- **Block model** — Vec<Block> aggregator with Precmd / Preexec / CommandFinished reducer. JSON dump for JNI debug.
- **Per-cell dynamic_grid renderer** — 60 fps p95=13ms, peak_fps=144 on Galaxy S24 Ultra.
- **SGR ANSI color routing** — full `[m` parameter support (8 + 16 + 256 + truecolor).
- **Touch-drag scroll + fling momentum**.
- **Live IME** via Gboard (English + Pinyin).

---

## [M2 — `warpui::platform::android` backend] — 2026-04-29

**Closing commit**: `0506c35`
**Verdict**: CONDITIONAL GO. 12/14 stories PASS (M2-S13 user-deferred per 「先跳過便宜手機」).

### Added

- **`warpui::platform::android`** Android backend derived from `headless` per Plan Decision A4 + D1.5-hybrid.
- **`ash` Vulkan surface** on `ANativeWindow` — clear, present, capture-frame round-trip.
- **`cosmic-text` text shaping** + Android system fonts via `ASystemFontIterator` (NDK API 29+) — Roboto / Noto Sans CJK / Samsung Color Emoji.
- **`WarpInputView` IME attachment** — `WarpInputConnection.commitText/setComposingText/finishComposingText` routed to Rust state machine.
- **GestureDetector touch dispatch** — single-tap, long-press, scroll, fling.
- **Vulkan validation layer integration** in debug builds (M2-S04).

---

## [M1 — Android PTY/Service prototype] — 2026-04-30

**Closing commit**: `f7feb3f`
**Verdict**: CONDITIONAL GO. 10/10 stories PASS.

### Added

- **PTY foreground service** (`WarpTerminalService`) — `nix::pty::openpty` on Bionic Android API 26+; child reaping on drop; concurrent read+kill via Arc.
- **PTY broadcast pipeline** — `dev.warp.mobile.ACTION_WRITE` + `PtyBroadcastReceiver` routes Kotlin bytes to Rust PTY.
- **FGS clean-kill semantics** — `am force-stop` (per Plan Amendment 4: AOSP `am kill` is no-op against running FGS).
- **30-min idle stress test** + PTY reattach + PTY resize drivers.

---

## [M0 — Foundation spike] — 2026-04-25

**Closing commit**: `24a2c1c`
**Verdict**: CONDITIONAL GO.

### Verified empirically

- `nix::pty::openpty` works on Bionic API 26+ without modification.
- `warpui::platform::android` derives cleanly from `headless` (vs `linux` or `wasm`).
- `warp_terminal` compiles to `aarch64-linux-android` once `warpui::platform::android` stub is in place.
- Vulkan surface recreate works through screen rotation + IME show/hide.
- Render path goes through Vulkan device validation layer cleanly.
