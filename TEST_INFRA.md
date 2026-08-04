# E2E Test Infra: warp-mobile-android

## Test Philosophy
- Requirement-driven, opaque-box testing across 25 open issues (#6 to #30).
- Methodology: 4-Tier Approach (Category-Partition + Boundary Value Analysis + Pairwise Combinatorial + Real-World Workload Testing).

## Feature Inventory & Test Coverage Targets
| # | Feature | Issue | Tier 1 (Feature) | Tier 2 (Boundary) | Tier 3 (Cross-Feature) | Tier 4 (Real-World) |
|---|---------|-------|:----------------:|:-----------------:|:---------------------:|:------------------:|
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
| **Total** | **25 Features** | | **125** | **125** | **25** | **15** |

## Test Architecture & Frameworks
1. **Rust Engine Unit Tests**: `cargo test --workspace` (Rust unit, parser, and facade tests).
2. **Kotlin JVM Unit Tests**: `./gradlew testDebugUnitTest` (MockK + JUnit 5 testing Kotlin managers and state classes).
3. **Compose UI & Accessibility Tests**: `./gradlew connectedDebugAndroidTest` (Compose UI testing framework + TalkBack AccessibilityChecks).
4. **Device ADB Stress Scripts**: `tools/scripts/test-*.sh` (Shell-driven real device execution test harnesses).
5. **Release & Hermeticity Gate**: `./tools/scripts/release.sh` & SHA256SUMS byte comparison.

## Coverage Thresholds
- Tier 1: ≥5 test cases per feature (125 total)
- Tier 2: ≥5 boundary/corner cases per feature (125 total)
- Tier 3: 25 pairwise feature interaction combinations
- Tier 4: 15 real-world workload application scenarios
- **Total Minimum Target: 290 test cases / scenarios**
