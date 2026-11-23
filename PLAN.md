# Scull: Kotlin/Native rewrite plan

Status: not started. The previous Rust core was removed in the pull request that added this
file (see [Repository reset](#7-repository-reset)). Every task in [`plan/`](plan/README.md)
is reset to not started; phases below map onto those tasks.

## Contents

1. [Goal](#1-goal)
2. [Scope and non-goals](#2-scope-and-non-goals)
3. [Architecture](#3-architecture)
4. [Implementation phases](#4-implementation-phases)
5. [Testing strategy](#5-testing-strategy)
6. [CI](#6-ci)
7. [Repository reset](#7-repository-reset)
8. [Risks and open questions](#8-risks-and-open-questions)

## 1. Goal

Scull is a terminal emulator with a **Kotlin/Native** core and a native UI on each platform:
SwiftUI on macOS, WinUI 3 on Windows, joined by a C ABI. The core parses, keeps state and
encodes input; the platform draws text with its own text stack and handles windows, input
methods and accessibility.

The design, taken from the research in [`research.md`](research.md), is unchanged: take the
best part of kitty, WezTerm, Alacritty, foot, Contour and Warp at each pipeline stage; no UI
types in the core; the UI reads a resolved frame snapshot and never holds a core lock.

## 2. Scope and non-goals

In scope for the first working apps:

- the seven core modules in [section 3](#3-architecture);
- a C ABI (`scull.h` and committed C# P/Invoke bindings) that the existing Swift and C# apps
  can load;
- macOS 26+ (arm64) and Windows (WinUI 3) hosts already in `macos/` and `windows/`;
- conformance tests, a throughput harness, and renderer measurements against the T3 baseline.

Not in scope until they move from [`ideas.md`](ideas.md) to [`roadmap.md`](roadmap.md):

- a Linux front-end (GTK) over the same C ABI;
- session persistence and a detachable server;
- bidirectional text in the core;
- publishing the core as a standalone embeddable library;
- OSC 66 text sizing, cold scrollback, command-block selection.

## 3. Architecture

```
        Swift (macOS)                         C# (Windows)
  SwiftUI chrome + NSView surface       WinUI 3 chrome + SwapChainPanel
  Metal + CoreText, NSTextInputClient   D3D11 + DirectWrite, TSF, UIA
              │                                      │
              └────────── C ABI (scull.h) ───────────┘
                               │
   scull-ffi      handles, frame snapshot, event queue, exception guard
   scull-term     session: screens, modes, cursor, selection, search
   scull-input    key, mouse, paste, focus encoders
   scull-grid     cells, rows, ring, reflow, damage, images, links
   scull-unicode  generated width and grapheme tables
   scull-parser   bulk scanner + sequence machine → typed actions
   scull-pty      PTY, reader thread, back-pressure
```

Build: Gradle with the Kotlin Multiplatform plugin. The core is Kotlin/Native.

| Target | Role |
| --- | --- |
| `macosArm64` | primary; static or shared library linked by the Swift package |
| `linuxX64` | CI and a future Linux host; C ABI tests under sanitizers |
| `mingwX64` | shared library loaded by the C# host |

cinterop: POSIX PTY (`posix_openpt`, `fork`/`exec`, `winsize`) on Unix; ConPTY on Windows.
No other native libraries are required for the core. Image codecs (PNG, JPEG, zlib) are
Kotlin or cinterop, chosen in T19/T34.

Rules of the boundary (research.md §7, `docs/research/ffi-native-ui.md` Part 4):

- The core owns one reader thread per terminal. The UI owns the main and render threads.
- The UI owns the frame object; `scull_frame_update` copies only changed rows under a short lock.
- The platform shapes text. The core alone decides cell widths.
- One coalesced wakeup, then a polled event queue.
- Every C export catches uncaught exceptions; a poisoned terminal returns `TT_POISONED`.
- ABI version check at start-up, `struct_size` first in every struct, append-only enums.
  Generated `scull.h` and C# bindings are committed and diff-checked in CI.

## 4. Implementation phases

Each phase lists the existing `plan/T*` tasks. Every checkbox starts unchecked.

### Phase 0: decisions and skeleton

- [ ] **T1.** Reaffirm `research.md` §8; record that the VT core is Kotlin/Native.
- [x] **T2.** Gradle KMP workspace, seven modules, pinned JDK 21, ktlint, `./gradlew check`, CI skeleton.

### Phase 1: harness

- [ ] **T3.** Conformance and benchmark harness (recorded streams, golden frames, fuzz corpus, throughput vs reference terminals).

### Phase 2: VT core

- [x] **T4.** Parser: bulk scanner in front of a table state machine, hard caps, fuzz target.
- [x] **T5.** Unicode tables: generated width and grapheme-break tables, mode 2027.
- [x] **T6.** Grid and scrollback: fixed cells, interning, power-of-two ring.
- [x] **T7.** Terminal state: modes, cursor, SGR, alt screen, device reports.
- [ ] **T22.** No-op link clears are generation-neutral.
- [ ] **T23.** Interner marks/sweep contract.
- [ ] **T24.** Harness wrap vs xterm deferred DECAWM.
- [ ] **T26.** Grid-layer test gaps.

### Phase 3: I/O and frames

- [ ] **T8.** PTY and reader thread (cinterop; ConPTY on Windows); back-pressure so a flooding child cannot starve a frame read.
- [ ] **T9.** Damage, synchronized output (mode 2026), UI-owned frame snapshot.
- [ ] **T11.** One-pass reflow with tracking points.

### Phase 4: C ABI

- [ ] **T10.** Opaque handles, frame API, wakeup, polled events, exception poisoning, committed header and C# bindings, C tests under sanitizers.
- [ ] **T13.3.** Events (title, directory, shell marks, clipboard, links, notifications) over the C ABI.

### Phase 5: input, selection, images, config core

- [ ] **T12.** Input encoding (legacy, kitty keyboard, win32-input-mode, mouse, paste, focus).
- [ ] **T13.** Selection, search, OSC 8/52/7/133 in the core.
- [ ] **T13.2.** Selection and scrollback search, with frame marks.
- [ ] **T19.** Images (kitty, iTerm2, sixel) with quotas.
- [ ] **T34.** Allocation-capped shared PNG decoder.
- [ ] **T30.** Config watcher never misses a reload under load.

### Phase 6: macOS app

- [ ] **T14.** First light: a window running the user's shell.
- [ ] **T15.** Metal renderer, CoreText atlas, measurements.
- [ ] **T16.** Input method and accessibility.
- [ ] **T13.4.** Selection, copy and find UI.
- [ ] **T27.** Powerline and Nerd Font symbols.
- [ ] **T28.** Powerline separators from cell geometry.

### Phase 7: Windows app

- [ ] **T17.** WinUI app and Direct3D renderer.
- [ ] **T18.** TSF input method and UIA accessibility.

### Phase 8: chrome

- [ ] **T20.** Configuration, fonts and themes (and the settings UI on both hosts).
- [ ] **T21.** Tabs, splits and windows.

### Phase 9: platform features

- [ ] **T31.** OSC 8 hyperlinks on both platforms, with the open-rule in the core.
- [ ] **T32.** Desktop notifications from OSC 9 and OSC 777.
- [ ] **T33.** Jump between shell prompts with OSC 133 marks.

### Phase 10: CI and hardening

- [ ] **T25.** Fuzz smoke and Unicode table freshness in CI.
- [ ] **T29.** Green required checks on `main`.
- [ ] **T900.** Dependency advisory updates on the Gradle graph.

## 5. Testing strategy

- **Core:** Kotlin/Native tests per module, golden fixtures in the harness, property tests
  (dirty-row repaint equals a full repaint; no tracking point lost on reflow; encoders do not
  throw).
- **C ABI:** a C program drives create/feed/update/free from two threads under ASan, UBSan and
  TSan on Unix.
- **Unicode:** `GraphemeBreakTest.txt` in full; a freshness check against pinned data files.
- **Fuzz:** short, bounded smoke of parser and image targets in CI (T25).
- **Platform:** `swift test` in `macos/`; `dotnet test windows/Scull.slnx` and a WinUI build on
  Windows. Renderer tests use an offscreen target (Metal) or WARP (Direct3D).
- **Benchmarks:** T3 writes `docs/benchmarks/baseline.md`; T15 and T17 write the renderer
  tables. Numbers already in those files predate this rewrite and must be re-measured.
- **Gate:** `./gradlew check` locally and in CI. Claims of speed need a number from the harness.

## 6. CI

`.github/workflows/ci.yml` runs the `jactionlint` job (also the `hk.pkl` pre-push hook) and,
since T2, one `<os> (<target>)` job per target that runs `./gradlew check` and links the
`scull` shared and static libraries. `pipeline.yml` still runs the shared scans from
`pyrlyn/ci` (configured in `.github/infra.yml`).

The `<os> (<target>)` jobs grow these steps as the tasks land, and T29 makes them required:

| Runner | Target | What it runs |
| --- | --- | --- |
| macOS (hosted or the dedicated Mac) | `macosArm64` | `./gradlew check`, C ABI sanitizer test, `swift test` |
| Ubuntu | `linuxX64` | `./gradlew check`, C ABI sanitizer test, fuzz smoke, Unicode freshness |
| Windows | `mingwX64` | `./gradlew check`, `dotnet test`, `dotnet build` of the WinUI app |

Draft pull requests skip the heavy jobs until they are marked ready. `SKIP_CI_TESTS` and
`MACOS_CI_DISABLED` remain the only explicit skip switches.

## 7. Repository reset

This rewrite deletes the Rust implementation and starts the plan from zero. Kept, and not
rewritten as history:

- `research.md` and `docs/research/` (survey of other terminals; Scull's own stack lines now
  say Kotlin/Native);
- `docs/benchmarks/` (figures from the previous core; they will be re-run);
- `docs/config.schema.json`, `docs/site.md`;
- `macos/` and `windows/` (native UI; they will be re-linked to the new C ABI);
- `plan/` task statements (reset, retargeted);
- licences, `PRICING.md`, `roadmap.md`, `ideas.md`, `.github/FUNDING.yml`, `pipeline.yml`,
  `hk.pkl`, Renovate and CodeRabbit config.

Removed: the Cargo workspace and crates, fuzz and `tools/` Rust generators, `workspace-hack`,
`.cargo/`, Clippy/rustfmt, the Rust `Justfile`, the sccache action, and the Rust jobs in
`ci.yml`.

## 8. Risks and open questions

- Kotlin/Native cinterop for ConPTY and a stable shared-library ABI on `mingwX64` need an
  early spike in T8/T10; if the Windows library cannot load from C#, T17 is blocked.
- Exception handling at the C boundary must poison only one terminal and never unwind into
  Swift or C#.
- The existing Swift and C# hosts expect the previous `scull.h`. T10 should keep that ABI
  or document a version bump and update both hosts in the same change.
- Kotlin Gradle plugin 2.4 uses a plain enum as a Gradle attribute, a deprecation that
  Gradle 10 turns into an error; KGP 2.5 fixes it. Move to Kotlin 2.5 before Gradle 10.
- CodeQL has no Kotlin/Native extractor; scans stay on Actions, plus C# and Swift for the
  hosts.
