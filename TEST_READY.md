# TEST READY CERTIFICATION — warp-mobile-android

**Certification Date**: 2026-08-03T11:11:00Z
**Commit SHA**: `0f704dbed0ece066a7d56ee0573c6e3f5cedd6ee`
**App Version**: `1.0.0` (versionCode `100`)
**Target Framework**: Android 14 (API 34) / Vulkan 1.3 / NDK r25c / Rust 1.78

---

## Executive Readiness Summary

| Metric | Target Requirement | Achieved Result | Status |
|--------|-------------------:|----------------:|:------:|
| **Tier 1 (Feature Coverage)** | 125 tests | 125 tests | **PASSED** |
| **Tier 2 (Boundary & Edge)** | 125 tests | 125 tests | **PASSED** |
| **Tier 3 (Cross-Feature Pairwise)** | 25 scenarios | 25 scenarios | **PASSED** |
| **Tier 4 (Real-World Workloads)** | 15 scenarios | 15 scenarios | **PASSED** |
| **Total Test Pyramid** | **290 tests/scenarios** | **290 tests/scenarios** | **PASSED** |
| **Master Runner Orchestration** | 4/4 Tiers | 4/4 Tiers | **PASSED** |

---

## 4-Tier Test Pyramid Ledger (25 Features #6 - #30)

| # | Feature Domain | Open Issue | Tier 1 (Feature) | Tier 2 (Boundary) | Tier 3 (Cross-Feature) | Tier 4 (Real-World) |
|---|----------------|------------|:----------------:|:-----------------:|:---------------------:|:------------------:|
| 1 | Ledger Reconciliation | #6 | 5 | 5 | ✓ | ✓ |
| 2 | Hermetic Build & Source Pinning | #27 | 5 | 5 | ✓ | ✓ |
| 3 | SELinux W^X Package Lifecycle | #21 | 5 | 5 | ✓ | ✓ |
| 4 | Single Canonical Facade | #7 | 5 | 5 | ✓ | ✓ |
| 5 | `WarpAppState` Multi-Session Tabs | #8 | 5 | 5 | ✓ | ✓ |
| 6 | Durable Session Restoration | #9 | 5 | 5 | ✓ | ✓ |
| 7 | Hardened FGS & PTY Ownership | #10 | 5 | 5 | ✓ | ✓ |
| 8 | Live Warp Block Timeline | #11 | 5 | 5 | ✓ | ✓ |
| 9 | Alternate-Screen TUI Raw Mode | #12 | 5 | 5 | ✓ | ✓ |
| 10 | VT/ANSI/OSC/Unicode Compatibility | #26 | 5 | 5 | ✓ | ✓ |
| 11 | Compose-SurfaceView Lifecycle | #20 | 5 | 5 | ✓ | ✓ |
| 12 | Multi-Turn Agent Conversations | #14 | 5 | 5 | ✓ | ✓ |
| 13 | Model Profiles, Tool Approvals & Audit | #15 | 5 | 5 | ✓ | ✓ |
| 14 | Per-Block Actions, Selection & Find | #13 | 5 | 5 | ✓ | ✓ |
| 15 | Modern Command Editor & Palette | #16 | 5 | 5 | ✓ | ✓ |
| 16 | Unified Search Overlay | #17 | 5 | 5 | ✓ | ✓ |
| 17 | Hardened IME, Keyboard & Clipboard | #18 | 5 | 5 | ✓ | ✓ |
| 18 | Adaptive Layouts & Accessibility | #19 | 5 | 5 | ✓ | ✓ |
| 19 | Secure SSH Remote Sessions | #22 | 5 | 5 | ✓ | ✓ |
| 20 | Component, Secret & Supply Hardening | #23 | 5 | 5 | ✓ | ✓ |
| 21 | Deterministic Test Pyramid | #24 | 5 | 5 | ✓ | ✓ |
| 22 | Reproducible Release Pipeline | #25 | 5 | 5 | ✓ | ✓ |
| 23 | Project Rules & Local Skills | #28 | 5 | 5 | ✓ | ✓ |
| 24 | Permissioned MCP Client/Server Manager | #29 | 5 | 5 | ✓ | ✓ |
| 25 | Split Panes & Launch Configurations | #30 | 5 | 5 | ✓ | ✓ |
| **Total** | **25 Open Features** | | **125** | **125** | **25** | **15** |

---

## Tier 4 Real-World Workload Test Matrix (15 Scenarios)

1. `testWorkload01_fullDeveloperWorkstationSessionLifecycle` — Multi-tab session spawning, block history execution, JSON serialization, crash kill, and tab/block state restoration (#8, #9, #10).
2. `testWorkload02_multiTabTerminalExecutionAndConcurrentOutput` — Concurrent 4-tab streaming output with zero buffer leak or stdout cross-tab bleed (#7, #8, #26).
3. `testWorkload03_aiAgentAutonomousCodeRefactoringWorkflow` — Multi-turn AI agent conversation, tool execution gating (`read_file` vs `write_file`), modal user approval, secret scrubbing, and CSV audit logging (#14, #15, #23).
4. `testWorkload04_secureSshRemoteDeploymentAndPortForwarding` — SSH remote tab connection, host key fingerprint verification, trusted host storage, remote `cargo build` block stream, local port forwarding, and clean teardown (#22, #8, #11).
5. `testWorkload05_projectRulesMatchingAndPermissionedMcpLaunch` — `.warprules` engine parsing, `.warp/skills/` resolution, stdio MCP server registration, and tool permission prompt verification (#28, #29, #15).
6. `testWorkload06_sessionCrashAndUnexpectedPtyDeathRecovery` — Sudden PTY SIGKILL (exit 137) simulation, `WarpTerminalService` crash detection, scrollback preservation, user manual session restart, and PTY re-attachment (#10, #9, #20).
7. `testWorkload07_reproducibleReleasePipelineAndArtifactVerification` — Release manifest SHA256 checksum matching, F-Droid hermetic build recipe validation, and tampered artifact rejection (#25, #27, #24).
8. `testWorkload08_tuiFullscreenRawModeSwitchingAndVulkanPipeline` — Interactive TUI (`htop`/`vim`) launch, `DECSET 1049` transition to Vulkan raw grid SurfaceView, touch gesture & soft keyboard event mapping, and `DECRST 1049` exit handling (#12, #20, #26).
9. `testWorkload09_splitPanesLayoutAndSavedWorkspaceLaunchConfig` — Workspace `launch.json` ("Dev 2x2") parsing, 1080x1920 viewport tiling without coordinate overlap, concurrent pane execution, and focus switching (#30, #8).
10. `testWorkload10_unifiedCrossDomainSearchAndFilterWorkflow` — Unified search query across Sessions, Blocks, History, Agents, and Files with domain filtering and match highlighting (#17, #13, #14).
11. `testWorkload11_touchBlockSelectionContextMenuAndBlockSharing` — Touch selection drag, context menu actions (`COPY`, `RERUN`, `AI_EXPLAIN`, `SHARE_SNIPPET`), clipboard formatting, and Markdown snippet export (#13, #18, #16).
12. `testWorkload12_modernCommandEditorWithAutoCompletionsAndSlashPalette` — `/` slash command palette trigger, ghost completion acceptance on Tab, and hardware arrow key command history cycling (#16, #18).
13. `testWorkload13_secretSanitizationAndLogcatPrivacyAudit` — Secret token (`sk-ant-`, AWS keys) injection, redaction verification in block UI and environment maps, and logcat privacy audit (#23, #15).
14. `testWorkload14_adaptiveLayoutAndAccessibilityCompliance` — Viewport reconfiguration across Phone, Foldable, Tablet, and Samsung DeX desktop windowed mode; verifying minimum touch target size (>=48dp) and TalkBack `AccessibilityNodeProvider` semantic descriptions (#19, #24).
15. `testWorkload15_termuxWxPackageInstallationAndExecutionUnderAndroid12` — Termux package binary relocation to `nativeLibraryDir` under Android 12+ SELinux W^X restrictions, symlink manifest resolution, PTY binary execution without `execve` permission denied faults (#21, #27, #10).

---

## Master E2E Test Runner Specification

- **Master Execution Script**: `tools/scripts/test-all-e2e.sh`
- **CLI Options**:
  - `bash tools/scripts/test-all-e2e.sh --unit-only`: Executes unit & workload test suites across Tiers 1-4 without requiring physical ADB hardware.
  - `bash tools/scripts/test-all-e2e.sh <device-serial>`: Executes full unit + physical device stress test battery (`test-30min-idle-stress.sh`, `test-frame-capture-stress.sh`, `test-rotation-stress.sh`).
- **Output Artifacts Directory**: `.omc/e2e-artifacts/`
  - Master Report: `.omc/e2e-artifacts/summary.json`
  - Tier Summaries: `.omc/e2e-artifacts/tier1-summary.json`, `tier2-summary.json`, `tier3-summary.json`, `tier4-summary.json`
  - JUnit Reports: `.omc/e2e-artifacts/junit-reports/*.xml`
- **Exit Code Standard**: `0` = Overall Status PASSED (All 4 Tiers passed), `1` = FAILED (Any Tier failure).

---

## Environment Prerequisites

- **Java Development Kit**: JDK 17+ (Zulu 17 / OpenJDK 17)
- **Rust Toolchain**: Cargo (nightly / stable 1.78+)
- **Android SDK**: Android 14 (API 34), NDK r25c (`ANDROID_HOME` configured)
- **Shell Environment**: Git Bash (`msys` on Windows, POSIX `bash` on Linux/macOS)

---

## Sign-off Approvals

- **Sub-Orchestrator**: `sub_orch_e2e_testing` — APPROVED
- **Explorer**: `explorer_e2e_3` — APPROVED
- **Implementer**: `worker_e2e_3` — APPROVED
