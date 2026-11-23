# Vesper: Kotlin/Native plan

The core is built (phases 0 to 5 below). The hosts are next: since T902 both are Kotlin/Native
and call the core directly, so the Swift, C# and Compose apps and the C ABI are gone. Tasks are
in [`plan/`](plan/README.md); phases below map onto them.

## Contents

1. [Goal](#1-goal)
2. [Scope and non-goals](#2-scope-and-non-goals)
3. [Architecture](#3-architecture)
4. [Implementation phases](#4-implementation-phases)
5. [Testing strategy](#5-testing-strategy)
6. [CI](#6-ci)
7. [Repository history](#7-repository-history)
8. [Risks and open questions](#8-risks-and-open-questions)

## 1. Goal

Vesper is a terminal emulator written in Kotlin: a **Kotlin/Native** core and a Kotlin/Native
host on each platform over its own APIs. The core parses, keeps state and encodes input; the
host draws text with the platform's text stack and handles windows, input methods and
accessibility.

The design, taken from the research in [`research.md`](research.md), is unchanged: take the
best part of kitty, WezTerm, Alacritty, foot, Contour and Warp at each pipeline stage; no UI
types in the core; the UI reads a resolved frame snapshot and never holds a core lock.

## 2. Scope and non-goals

In scope for the first working apps:

- the core modules in [section 3](#3-architecture);
- a macOS 26+ (arm64) host: Kotlin/Native over AppKit, Metal and CoreText;
- a Windows host: Kotlin/Native over Win32, Direct3D 11, DirectWrite, TSF and UIA;
- conformance tests, a throughput harness, and renderer measurements against the T3 baseline.

Not in scope until they move from [`ideas.md`](ideas.md) to [`roadmap.md`](roadmap.md):

- a Linux host;
- session persistence and a detachable server;
- bidirectional text in the core;
- publishing the core as a standalone embeddable library;
- OSC 66 text sizing, cold scrollback, command-block selection.

## 3. Architecture

```
     Kotlin/Native (macOS)                 Kotlin/Native (Windows)
  AppKit window + NSView surface        Win32 window + DXGI swap chain
  Metal + CoreText, NSTextInputClient   D3D11 + DirectWrite, TSF, UIA
              │                                      │
              └────────── direct Kotlin calls ───────┘
                               │
   vesper-term     session: screens, modes, cursor, selection, search, frame
   vesper-input    key, mouse, paste, focus encoders
   vesper-grid     cells, rows, ring, reflow, damage, links
   vesper-unicode  generated width and grapheme tables
   vesper-parser   bulk scanner + sequence machine → typed actions
   vesper-pty      PTY, reader thread, back-pressure
   vesper-image    images and placements under a byte quota; iTerm2, sixel and kitty decoders
   vesper-png      header-first PNG decode under a caller-set allocation cap
```

Build: Gradle with the Kotlin Multiplatform plugin; every module is Kotlin/Native.

| Target | Role |
| --- | --- |
| `macosArm64` | primary; the macOS host |
| `linuxX64` | CI for the core |
| `mingwX64` | the Windows host |

cinterop: POSIX PTY (`posix_openpt`, `fork`/`exec`, `winsize`) on Unix; ConPTY on Windows.
The macOS host uses the AppKit, Metal, QuartzCore and CoreText platform libraries that ship
with Kotlin/Native. The Windows host binds Win32, Direct3D 11, DXGI, TSF and UIA through their C
interfaces; DirectWrite has none, so T17 picks a shim (Zig first). PNG is decoded in Kotlin over
zlib (`platform.zlib`, shipped with Kotlin/Native; T34).

Rules between core and host (research.md §7):

- The core owns one reader thread per terminal. The host owns the main and render threads.
- The host owns the frame object; `Terminal.updateFrame` copies only changed rows under a short lock.
- The platform shapes text. The core alone decides cell widths.
- One coalesced wakeup, then a polled event queue.
- An uncaught exception in one terminal stops that terminal only.

## 4. Implementation phases

### Phase 0: decisions and skeleton

- [x] **T1.** Reaffirm `research.md` §8; record that the VT core is Kotlin/Native.
- [x] **T2.** Gradle KMP workspace, pinned JDK 21, ktlint, `./gradlew check`, CI skeleton.
- [ ] **T902.** Remove the Swift, C# and Compose apps and the C ABI; rename the project to Vesper everywhere.

### Phase 1: harness

- [ ] **T3.** Conformance and benchmark harness (recorded streams, golden frames, fuzz corpus, throughput vs reference terminals).

### Phase 2: VT core

- [x] **T4.** Parser: bulk scanner in front of a table state machine, hard caps, fuzz target.
- [x] **T5.** Unicode tables: generated width and grapheme-break tables, mode 2027.
- [x] **T6.** Grid and scrollback: fixed cells, interning, power-of-two ring.
- [x] **T7.** Terminal state: modes, cursor, SGR, alt screen, device reports.
- [x] **T22.** No-op link clears are generation-neutral.
- [x] **T23.** Interner marks/sweep contract.
- [x] **T24.** Harness wrap vs xterm deferred DECAWM.
- [x] **T26.** Grid-layer test gaps.

### Phase 3: I/O and frames

- [x] **T8.** PTY and reader thread (cinterop; ConPTY on Windows); back-pressure so a flooding child cannot starve a frame read.
- [x] **T9.** Damage, synchronized output (mode 2026), UI-owned frame snapshot.
- [x] **T11.** One-pass reflow with tracking points.

### Phase 4: events

- [x] **T10.** Handles, frame API, wakeup and polled events (its C ABI was removed in T902).
- [x] **T13.3.** Events: title, directory, shell marks, clipboard, links, notifications.

### Phase 5: input, selection, images

- [x] **T12.** Input encoding (legacy, kitty keyboard, win32-input-mode, mouse, paste, focus).
- [x] **T12.1.** Key encoding: legacy xterm, the kitty keyboard protocol with its per-screen flag stack, win32-input-mode.
- [x] **T12.2.** Mouse (all tracking modes and encodings, SGR-pixel included), bracketed paste and focus reports.
- [x] **T13.** OSC 8/52/7/133 and notifications in the core (selection and search are T13.2).
- [x] **T13.2.** Selection and scrollback search, with frame marks.
- [x] **T19.** Images (kitty, iTerm2, sixel) with quotas.
- [x] **T19.1.** Image store with a byte quota and LRU eviction; iTerm2 inline images.
- [x] **T19.2.** Sixel decoder.
- [x] **T19.3.** Kitty graphics protocol.
- [x] **T19.4.** Images in the terminal and the frame.
- [x] **T34.** Allocation-capped shared PNG decoder.
- [x] **T30.** Config watcher never misses a reload under load (moved into T20).

### Phase 6: macOS host

- [ ] **T14.** First light: a window running the user's shell.
- [ ] **T15.** Metal renderer, CoreText atlas, measurements.
- [ ] **T16.** Input method and accessibility.
- [ ] **T13.4.** Selection, copy and find UI.
- [ ] **T27.** Powerline and Nerd Font symbols.
- [ ] **T28.** Powerline separators from cell geometry.

### Phase 7: Windows host

- [ ] **T17.** Win32 app, Direct3D renderer and DirectWrite atlas.
- [ ] **T18.** TSF input method and UIA accessibility.

### Phase 8: chrome

- [ ] **T20.** Configuration, fonts and themes (and the settings view on both hosts).
- [ ] **T21.** Tabs, splits and windows.

### Phase 9: platform features

- [ ] **T31.** OSC 8 hyperlinks on both platforms, with the open rule in the core.
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
- **Unicode:** `GraphemeBreakTest.txt` in full; a freshness check against pinned data files.
- **Fuzz:** short, bounded smoke of parser and image targets in CI (T25).
- **Hosts:** Kotlin/Native tests of each host's pure parts (input mapping, text and range
  models, policies) on every runner; renderer tests on an offscreen target (Metal) or WARP
  (Direct3D) on their own OS. Input methods, screen readers and how a window looks need a human.
- **Benchmarks:** T3 writes `docs/benchmarks/baseline.md`; T15 and T17 write the renderer
  tables.
- **Gate:** `./gradlew check` locally and in CI. Claims of speed need a number from the harness.

## 6. CI

`.github/workflows/ci.yml` runs the `jactionlint` job (also the `hk.pkl` pre-push hook) and one
`<os> (<target>)` job per target that runs `./gradlew check`. `pipeline.yml` runs the shared
scans from `pyrlyn/ci` (configured in `.github/infra.yml`).

| Runner | Target | What it runs |
| --- | --- | --- |
| macOS (hosted or the dedicated Mac) | `macosArm64` | `./gradlew check` (core and macOS host) |
| Ubuntu | `linuxX64` | `./gradlew check`, later fuzz smoke and Unicode freshness (T25) |
| Windows | `mingwX64` | `./gradlew check` (core and Windows host) |

T29 makes the `<os> (<target>)` jobs required. Draft pull requests skip the heavy jobs until
they are marked ready. `SKIP_CI_TESTS` and `MACOS_CI_DISABLED` remain the only explicit skip
switches.

## 7. Repository history

The Rust core was replaced by this Kotlin/Native rewrite; its code stays in the git history
(`crates/` before the rewrite). T902 removed the Swift app (`macos/`), the C# app (`windows/`),
the Compose app (`composeApp/`) and the C ABI module; they are in the history at eaeb20f and
serve as reference designs for the Kotlin/Native hosts. Kept: `research.md` and
`docs/research/` (the survey of other terminals), `docs/benchmarks/`,
`docs/config.schema.json`, `docs/site.md`, `docs/design/`.

## 8. Risks and open questions

- DirectWrite has no C interface, so the Windows host needs a shim for it (T17's first step).
- Kotlin/Native over AppKit and Metal: frame-time cost of Kotlin objects on the render path is
  not measured yet; T15 measures it against the T3 baseline.
- Kotlin Gradle plugin 2.4 uses a plain enum as a Gradle attribute, a deprecation that
  Gradle 10 turns into an error; KGP 2.5 fixes it. Move to Kotlin 2.5 before Gradle 10.
- CodeQL has no Kotlin/Native extractor; scans stay on Actions.
