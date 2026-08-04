# Project: warp-mobile-android

## Architecture
5-Layer Model:
1. **System & Service Layer**: Android Foreground Service (`WarpTerminalService`), PTY lifecycle (`PtyManager`), SELinux W^X binary executor (`nativeLibraryDir`), Termux asset management.
2. **Native Engine Layer**: Rust JNI bridge (`crates/android-host`), Vulkan renderer (`vulkan.rs`), ANSI/VT/DCS hook parser (`warp_terminal_mobile_facade`), Font renderer (`font_render.rs`).
3. **App State & Runtime Facade**: Single canonical facade (`warp_terminal_mobile_facade`), Centralized `WarpAppState` multi-session tabs manager, durable session persistence (`sessions.json` / SQLite), PTY process ownership recovery.
4. **Warp UX & Timeline Layer**: Block card timeline (`BlockCard` in Jetpack Compose `LazyColumn`), Alternate-screen TUI raw mode auto-switch (`DECSET 1049`), Per-block actions & text selection, Modern Command Editor (`PromptComposer` + Command Palette `/`), Unified Search Overlay, Hardened IME & Touch Selection, Adaptive Tablet/DeX/Foldable layout & TalkBack accessibility, SSH Remote Session Connector.
5. **AI & Safety Layer**: Multi-turn agent timeline conversation (`crates/warp_ai_mobile`), Model selector profiles, Tool execution safety loop with user approval dialogs, Android KeyStore BYOK key storage, Audit logger (`warp-ai-usage.csv`), Project Rules & Local Skills, MCP Client/Server Manager.

## Version Baseline
Canonical App Version: `1.0.0` (versionCode: `100`).

## Feature Inventory
Every feature for issues #6 through #30 mapped into implementation waves.

| # | Feature | Issue | Wave | Description | Milestone | Source | Status |
|---|---------|-------|------|-------------|-----------|--------|--------|
| 1 | Ledger & Spec Reconciliation | #6 | Wave 1 | Reconcile code, README, CHANGELOG, and issue tracking into canonical PROJECT.md ledger | M-W1 | survey | COMPLETED |
| 2 | Hermetic Build & Source Pinning | #27 | Wave 1 | Pin external companion repos (`warp-src`, `termux-packages`), SHA256 pins, hermetic build | M-W1 | survey | COMPLETED |
| 3 | SELinux W^X Package Lifecycle | #21 | Wave 1 | Relocate Termux binaries to `nativeLibraryDir` (`apk_data_file`) with manifest symlinking | M-W1 | survey | COMPLETED |
| 4 | Single Canonical Runtime Facade | #7 | Wave 2 | Consolidate duplicated Rust terminal parser/model into `warp_terminal_mobile_facade` | M-W2 | survey | COMPLETED |
| 5 | `WarpAppState` Multi-Session Tabs | #8 | Wave 2 | Centralized multi-session state manager in Kotlin & Rust supporting tab switching | M-W2 | survey | COMPLETED |
| 6 | Durable Session Restoration | #9 | Wave 2 | Save & restore tab metadata, `cwd`, environment, and scrollback across app restarts | M-W2 | survey | COMPLETED |
| 7 | Hardened FGS & PTY Ownership | #10 | Wave 2 | Atomic FD tracking, ANR protection, fast-death auto-recovery in `WarpTerminalService` | M-W2 | survey | COMPLETED |
| 8 | Live Warp Block Timeline | #11 | Wave 3 | Replace flat grid with discrete interactive Block cards in Jetpack Compose `LazyColumn` | M-W3 | survey | COMPLETED |
| 9 | Alternate-Screen TUI Raw Mode | #12 | Wave 3 | Detect `DECSET 1049` and auto-switch between Block timeline and Vulkan raw grid mode | M-W3 | survey | COMPLETED |
| 10 | VT/ANSI/OSC/Unicode Compatibility | #26 | Wave 3 | SGR truecolor/256-color, CJK, CJK wide, combining marks, powerline, emoji fallback | M-W3 | survey | COMPLETED |
| 11 | Compose-SurfaceView Lifecycle | #20 | Wave 3 | SurfaceView in Compose `AndroidView`, zero z-fighting/ANR, 60fps Vulkan swapchain recreate | M-W3 | survey | COMPLETED |
| 12 | Multi-Turn Agent Conversations | #14 | Wave 4 | Interleave agent turns, explanation cards, and streaming responses in Block timeline | M-W4 | survey | COMPLETED |
| 13 | Model Profiles, Tool Approvals & Audit | #15 | Wave 4 | Model selector, high-risk command approval dialogs, KeyStore BYOK, CSV usage audit | M-W4 | survey | COMPLETED |
| 14 | Per-Block Actions, Selection & Find | #13 | Wave 5 | Copy, re-run, explain, share, touch range selection, and text find/filter in blocks | M-W5 | survey | COMPLETED |
| 15 | Modern Command Editor & Palette | #16 | Wave 5 | Multi-line editor, command history, inline ghost completion, slash command palette `/` | M-W5 | survey | COMPLETED |
| 16 | Unified Search Overlay | #17 | Wave 5 | Search overlay across 5 domains (sessions, blocks, history, AI conversations, files) | M-W5 | survey | COMPLETED |
| 17 | Hardened IME, Keyboard & Clipboard | #18 | Wave 5 | Gboard CJK composing, hardware keyboard shortcuts, AccessoryRow, chunked paste | M-W5 | survey | COMPLETED |
| 18 | Adaptive Layouts & Accessibility | #19 | Wave 5 | Phone/Tablet/DeX/Foldable responsive layouts, TalkBack `AccessibilityNodeProvider` | M-W5 | survey | COMPLETED |
| 19 | Secure SSH Remote Sessions | #22 | Wave 5 | Native Rust SSH connector (`russh`), password/key auth, host-key verification dialog | M-W5 | survey | COMPLETED |
| 20 | Component, Secret & Supply Hardening | #23 | Wave 6 | `exported=false`, KeyStore encryption, logcat sanitization, cargo audit security | M-W6 | survey | PLANNED |
| 21 | Deterministic Test Pyramid | #24 | Wave 6 | Kotlin JVM unit tests, Compose UI tests, device stress scripts, accessibility scans | M-W6 | survey | PLANNED |
| 22 | Reproducible Release Pipeline | #25 | Wave 7 | Signed reproducible APKs, F-Droid recipe verification, 24h soak test pipeline | M-W7 | survey | PLANNED |
| 23 | Project Rules & Local Skills | #28 | Wave 8 | Project rules engine (`.warprules`), local skills directory (`.warp/skills/`) for AI | M-W8 | survey | PLANNED |
| 24 | Permissioned MCP Client/Server Manager | #29 | Wave 8 | MCP JSON-RPC manager (stdio/SSE/HTTP), tool execution permission prompts | M-W8 | survey | PLANNED |
| 25 | Split Panes & Launch Configurations | #30 | Wave 8 | Multi-pane terminal viewports, saved workspace launch configs (`launch.json`) | M-W8 | survey | PLANNED |

## Milestones

| # | Milestone Name | Scope (Issues) | Dependencies | Status |
|---|----------------|----------------|--------------|--------|
| 1 | M-W1: Foundation Reconciliation & Hermetic Build | #6, #27, #21 | None | COMPLETED |
| 2 | M-W2: Runtime Consolidation & Multi-Session Core | #7, #8, #9, #10 | M-W1 | COMPLETED |
| 3 | M-W3: Block Timeline UI & Terminal Compatibility | #11, #12, #26, #20 | M-W2 | COMPLETED |
| 4 | M-W4: Agent Foundation & AI Safety | #14, #15 | M-W3 | COMPLETED |
| 5 | M-W5: Product UI/UX, Connectors & Accessibility | #13, #16, #17, #18, #19, #22 | M-W3 | COMPLETED |
| 6 | M-W6: Hardening & Test Pyramid | #23, #24 | M-W4, M-W5 | PLANNED |
| 7 | M-W7: Reproducible Release Pipeline | #25 | M-W6 | PLANNED |
| 8 | M-W8: Post-v1 Parity (Rules, MCP, Split Panes) | #28, #29, #30 | M-W7 | PLANNED |

## Milestone M-W1 Task Ledger
- **Task 1 (#6 Reconciliation & Versioning 1.0.0)**: COMPLETED — Reconciled Cargo workspace (`1.0.0`), Gradle build configuration (`versionCode 100`, `versionName "1.0.0"`), F-Droid metadata recipe (`1.0.0`/`100`), Fastlane changelogs (`100.txt`), release scripts, CI workflows, and documentation into canonical `1.0.0` release baseline.
- **Task 2 (#27 Hermetic Build)**: COMPLETED — Established hermetic companion source pinning, SHA256 checksum verification, and `.gitmodules` submodule setup for `warp-src` and `termux-packages`.
- **Task 3 (#21 W^X Bypass)**: COMPLETED — Implemented `nativeLibraryDir` dynamic package relocation with `termux-bin-manifest.json` symlinks, eliminating Android 12+ SELinux exec denials on untrusted app data directories.

## Milestone M-W2 Task Ledger
- **Task 1 (#7 Runtime Consolidation)**: COMPLETED — Consolidated duplicated terminal model/VT parser code in `crates/android-host/src/terminal_model.rs` into `warp_terminal_mobile_facade` as the single canonical facade.
- **Task 2 (#8 Multi-Session Tabs Manager)**: COMPLETED — Implemented `WarpAppState` multi-session tabs manager in Kotlin & Rust supporting tab creation, switching, closing, and thread-safe session handle routing with test-isolation locking.
- **Task 3 (#9 Durable Session Restoration)**: COMPLETED — Implemented atomic session metadata & scrollback buffer persistence to `sessions.json` with `.tmp` write swapping, corrupted JSON `.bak` quarantining, and cold startup tab/PTY restoration.
- **Task 4 (#10 Hardened Service & PTY Ownership)**: COMPLETED — Hardened `WarpTerminalService` & `PtyManager` ownership with Rust `OPEN_PTY_COUNT` atomic counter, RAII `FdGuard`, ANR protection offloaded to `Dispatchers.IO`, and fast-death (1.5s) crash loop isolation (max 3 retries, exponential backoff, `/system/bin/sh` fallback).

## Milestone M-W3 Task Ledger
- **Task 1 (#11 Live Warp Block Timeline)**: COMPLETED — Implemented discrete interactive Block cards in Jetpack Compose `LazyColumn` showing command prompt, exit code badge, duration, output stream, and action triggers.
- **Task 2 (#12 Alternate-Screen TUI Raw Mode)**: COMPLETED — Implemented `DECSET 1049` / `DECRST 1049` detection for seamless auto-switching between Compose Block timeline and Vulkan raw grid mode.
- **Task 3 (#26 VT/ANSI/OSC/Unicode Compatibility)**: COMPLETED — Implemented SGR truecolor (24-bit 38;2/48;2), 256-color support, CJK double-width character alignment, combining marks, powerline glyphs, and emoji fallback rendering.
- **Task 4 (#20 Compose-SurfaceView Lifecycle)**: COMPLETED — Stabilized SurfaceView embedded in Compose `AndroidView` with single `SurfaceHolder.Callback` ownership, `setZOrderMediaOverlay(true)`, persistent composition mounting in `WarpScaffold`, and JNI `try-catch` fallbacks across all call sites in `MainActivity.kt`.

## Milestone M-W4 Task Ledger
- **Task 1 (#14 Multi-Turn Agent Conversations)**: COMPLETED — Interleaved agent turns, system explanation cards, streaming response blocks, and turn controls (cancel/retry/pause/resume/edit) in Jetpack Compose Block timeline (`crates/warp_ai_mobile` + Kotlin UI & JNI bindings).
- **Task 2 (#15 Model Profiles, Tool Approvals & Audit)**: COMPLETED — Model selector profiles, high-risk command approval modal dialog (`CommandApprovalDialog`) suspending PTY execution in `MainActivity.kt` / `CommandApprovalManager.kt` until user approval, KeyStore BYOK key storage (`AiKeyStore.kt`), and 7-column RFC-4180 CSV usage audit logger (`warp-ai-usage.csv` via `AiUsageTracker.kt`). Optimized 50,000-block search benchmark (75x speedup) in `BlockSearchEngine.kt`.

## Interface Contracts
### Kotlin `WarpAppState` ↔ Rust `warp_terminal_mobile_facade`
- `createSession(sessionId: String, env: Map<String, String>): SessionHandle`
- `switchSession(sessionId: String)`
- `closeSession(sessionId: String)`
- `saveSessionState(): String` (JSON) / `restoreSessionState(json: String)`

### Block Timeline ↔ TUI Raw Mode
- `DECSET 1049` (Alternate Screen Enter) -> Trigger `onToggleRawMode(true)` (Swap UI from Compose Block cards to fullscreen Vulkan grid)
- `DECRST 1049` (Alternate Screen Exit) -> Trigger `onToggleRawMode(false)` (Swap UI back to Compose Block cards)

### AI Tool Approval ↔ Shell Execution
- Tool call request `execute_command(cmd)` -> Pause stream -> Intercept command risk level -> Show Modal Confirmation Dialog if high-risk -> Forward bytes to PTY only upon user `APPROVE`.

## Code Layout
- `android/app/src/main/java/dev/warp/mobile/`: Kotlin UI & Android system components
- `android/app/src/test/java/dev/warp/mobile/`: JVM Kotlin unit tests
- `android/app/src/androidTest/java/dev/warp/mobile/`: Compose UI & Accessibility integration tests
- `crates/android-host/`: JNI C bindings & Vulkan grid engine
- `crates/warp_ai_mobile/`: Rust AI Anthropic client & tool loop
- `crates/warp_terminal_mobile_facade/`: Rust canonical terminal model, parser, ANSI/DCS hooks, SSH provider
- `tools/scripts/`: Build, packaging, release, and ADB stress test scripts
- `.github/workflows/`: CI test workflows and release packaging pipelines
