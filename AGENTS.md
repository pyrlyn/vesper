# Vesper — instructions for agents

**What this is.** A terminal emulator written in Kotlin. The `vesper-*` modules are the Kotlin/Native core; each platform gets a Kotlin/Native host over its own APIs (AppKit, Metal and CoreText on macOS; Win32, Direct3D, DirectWrite, TSF and UIA on Windows) that calls the core directly: the core computes, the platform renders. Write everything in Kotlin where Kotlin can do it; native code a host cannot avoid is written in Zig first, C or C++ only where Zig does not fit.

If an `AGENTS.md` or `CLAUDE.md` exists higher in the tree, follow it too; on conflict, ask the creator.

## Read first

- `PLAN.md` — goal, architecture, phases and CI for the Kotlin/Native rewrite.
- `research.md` — the architecture report and the reasons behind every choice.
- `docs/research/` — per-terminal findings with `path:line` sources.
- `docs/design/` — the UI design, one Markdown file per Figma frame: what the screen is, its components, its states, how it behaves on errors and offline, and the link to the frame. UI work starts from the file of its screen; read the frame with the Figma MCP tools. Markdown only: no images or other binaries in that folder.
- `plan/` — tasks, one folder each (`plan/<id>/task.md`); a closed task also has `done.md`.

## Hard rules

- **No UI types in the core.** Core modules speak cells, rows, columns and plain integers. Pixel metrics, colours of the toolkit, key types of the toolkit and windows stay on the platform side.
- **The UI never holds a core lock.** It reads frames through the snapshot API only.
- **The core never calls the UI while holding a lock.** The only callback is the wakeup; everything else is a polled event.
- **One failing terminal never takes the app down.** An uncaught exception in a terminal's threads or calls stops that terminal only.
- **Everything from the PTY is untrusted input.** Every payload, table and queue it can grow has a hard cap and a test.
- **Licences.** The project is `GPL-3.0-or-later`. kitty's file headers say "GPL3" with no "or later" wording, so any file ported from kitty is marked `GPL-3.0-only` and kept apart from our own files; prefer reimplementing from kitty's protocol specifications. Code may be ported from MIT, Apache-2.0 and GPL-3 sources (Alacritty, WezTerm, foot, Contour, Ghostty, Windows Terminal, kitty) with their notices kept and the origin named in the commit. Warp's core is AGPL-3.0: study it, never port it. Take Alacritty-derived design from upstream Alacritty, not from Warp's copies.
- **Claims of speed need a number.** "Faster" or "better" goes into a doc or commit only with a benchmark from the harness.

## Decisions

Settled by the creator; reasons are in `research.md` §8.

- The structural reference named "Wrapp" in the brief is Warp.
- **Hosts:** Kotlin/Native on both platforms (T902). macOS: AppKit, Metal and CoreText through the Kotlin/Native platform libraries. Windows: Win32, Direct3D 11, DirectWrite, TSF and UIA through cinterop. The Swift, C# and Compose apps were removed.
- **No C ABI:** the hosts call the core modules directly; there is no exported header or bindings.
- **VT core:** our own Kotlin/Native state and grid. libghostty-vt is not embedded.
- **PTY:** inside the core, through Kotlin/Native cinterop (POSIX PTY on Unix, ConPTY on Windows).
- **Minimum macOS:** 26. No fallbacks for older systems.
- **Split panes on Windows:** one swap chain per window; panes are viewports.
- **Project licence:** `GPL-3.0-or-later`. The creator sells subscription services around the terminal; the client itself stays GPL.

## Commands

jactionlint and hk are pinned in `mise.toml`. `.github/jactionlint.yaml` applies the default profile and the
baseline `.github/jactionlint-baseline.json`. `hk.pkl` runs jactionlint as a pre-push hook:

```bash
mise install                           # JDK 21 and the lint tools
mise x -- jactionlint                  # lint workflows, composite actions, dependabot.yml
mise x -- jactionlint --baseline-write # refresh the baseline after fixing findings
mise x -- hk install --mise            # install the pre-push hook once per clone
./gradlew check                        # merge gate: ktlint and the native tests of the host's targets
./gradlew :vesper-bench:runReleaseExecutableMacosArm64  # rewrite docs/benchmarks/baseline.md
./gradlew :vesper-bench:runReleaseExecutableMacosArm64 --args=parser  # parser throughput table (docs/benchmarks/parser.md)
./gradlew :vesper-bench:runReleaseExecutableMacosArm64 --args=grid    # grid memory and scroll tables (docs/benchmarks/grid.md)
./gradlew :vesper-bench:runReleaseExecutableMacosArm64 --args=term    # terminal throughput table (docs/benchmarks/term.md)
./gradlew :vesper-unicode:generateUcd   # regenerate the Unicode tables from the pinned data files (downloads once)
./gradlew :vesper-unicode:checkUcd      # fail when the committed Unicode tables are stale; not in check, it needs unicode.org
VESPER_FUZZ_SECONDS=30 ./gradlew :vesper-image:macosArm64Test  # run each fuzz corpus for 30 s from a fresh seed
VESPER_FUZZ_SEED=<seed> VESPER_FUZZ_SECONDS=600 ./gradlew :vesper-image:macosArm64Test  # rerun the seed a failure printed
```
