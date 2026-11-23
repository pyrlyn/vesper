# T7. Terminal state: done

## What was done

- `scull-term` is the Kotlin/Native terminal: `Terminal` (parser plus state, `feed`, `grid`, `cursor`, `pen`, `modes`, `margins`, `isAltScreen`, `takeReplies`, `takeBell`, `pollEvent`) and the internal `State` handler, one file per concern: `Dispatch`, `Print`, `Motion`, `Edit`, `Sgr`, `Pen`, `Modes`, `Screen`, `Charset`, `TabStops`, `Reply`, `Events`.
- T7.1: print with autowrap, wide characters and grapheme clusters (mode 2027), C0, cursor movement, ED/EL/ECH/ICH/DCH/IL/DL, REP, SGR (16, 256, truecolour, underline styles and colour, colon and semicolon forms), DECSTBM, tab stops, IND/RI/NEL.
- T7.2: ANSI and DEC private modes with DECRQM, G0-G3 with DEC special graphics and the UK set, DECLRMM and DECSLRM, alt screen 47/1047/1049, DECSC/DECRC per screen, DA1, DA2, DSR, CPR and DECXCPR through a reply queue capped at 4096 bytes, RIS and DECSTR.
- Events: the coalesced bell and the OSC 0/1/2 title through a queue capped at 32 events and 1024 bytes of text. Every other OSC is ignored until T13.
- `scull-unicode`: `GraphemeState.reset()` and a public `appendCodePoint`. `scull-grid`: `Row.putAscii`, one call for a run of narrow cells.
- `scull-harness`: `readFixture` and `environment` moved from its tests to its main source set, so the harness tests, the `scull-term` tests and `scull-bench` share one copy. The `SCULL_TESTS_DIR` wiring moved to the `scull.kmp` convention plugin, which also declares `tests/` as an input of every native test task: before that an edited fixture did not rerun the tests.
- `scull-bench`: a `term` table and a "plain line as one run" row in the grid table; the generated inputs and the timing are shared by the parser and the terminal tables.

## Reuse

Searched before writing: `scull-parser` (the `Handler` actions and `Params`), `scull-grid` (cells, styles, rows, scrolling, clusters), `scull-unicode` (width, grapheme breaks) and `scull-harness` (fixture reading) are used as they are or extended in place. No new dependency. The terminal semantics are ported from the Rust `scull-term` at 69d9be4; there is no maintained Kotlin/Native terminal state library to take instead.

## How it was checked

- `./gradlew check` on macOS arm64: ktlint and all tests pass. `scull-term` has 49 tests, among them the conformance runner over the 14 fixture files (226 cases, all pass), and a seeded random-stream test of 1500 cases (no exception, cursor and margins on screen, no half wide character, the same state whatever the chunking).
- The runner was proven to compare: with two fixtures deliberately broken it reported exactly those two cases.
- linuxX64: the test binaries of `scull-unicode`, `scull-parser`, `scull-grid`, `scull-harness`, `scull-term` and `scull-bench` pass in an Ubuntu 24.04 container (amd64 on Colima).
- mingwX64: `compileKotlinMingwX64` passes for every module. The tests were not run on Windows: no workflow runs in this repository yet.
- Throughput on an Apple M3 Max: 220–230 MiB/s for ASCII lines, 49 MiB/s for UTF-8 lines, 101–104 MiB/s for SGR-heavy output (`docs/benchmarks/term.md`).

## Left for later tasks

- Mouse modes, alternate scroll and the kitty keyboard stack report "not recognised" until T12.
- UTF-8 text is 4.5 times slower than ASCII; a run path for narrow non-ASCII text is the next step if it matters.
