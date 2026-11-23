---
status: in progress
priority: P0
complexity: 5
readiness: 100%
agent: Claude Code / claude-fable-5-2
---

# T7. Terminal state

`vesper-term`: the handler for the typed actions. Cursor, SGR, modes, charsets, tab stops, scroll regions, left and right margins, alt screen, saved cursor, device reports. Done when the conformance cases listed in `tests/README.md` under T7.1 and T7.2 pass against the Kotlin/Native terminal.

## Scope

- T3 has not chosen esctest and vttest cases, so T7 uses the subset a headless core can check (recorded stream in; expected grid, cursor, styles and replies out). The cases are the fourteen files `tests/fixtures/{cursor,erase,insert_delete,scroll,tabs,sgr,wrap,unicode,modes,margins,screens,charsets,reports,reset}.txt`; `shell.txt` and `selection.txt` stay with T13 and T13.2.
- T7.1: print with wrap and wide characters, C0, cursor movement, ED/EL/ECH/ICH/DCH/IL/DL, SGR (16, 256, truecolour, underline styles and colour), scroll regions (DECSTBM), tab stops, IND/RI/NEL.
- T7.2: modes (ANSI and DEC private, DECRQM incl. 2027), charsets (G0-G3, DEC special graphics), left and right margins (DECLRMM, DECSLRM), alt screen 47/1047/1049, DECSC/DECRC, device reports (DA1, DA2, DSR, CPR) through a capped reply queue, RIS and DECSTR.
- Events: only what the T7 cases need, the coalesced bell and the OSC 0/1/2 title, through a capped queue. Every other OSC is T13.
- Not here: mouse modes (9, 1000-1003, 1005, 1006, 1015, 1016), alternate scroll (1007) and the kitty keyboard stack. Their state types belong to `vesper-input`, which T12 writes; until then DECRQM reports them as not recognised.

## Execution plan

1. `vesper-unicode`: `GraphemeState.reset()`, so ending a cluster on every control allocates nothing.
2. `vesper-grid`: `Row.putAscii`, one call for a run of narrow cells (T6 measured about 16 ns per `Row.put` call).
3. `vesper-term` (`src/commonMain/kotlin/vesper/term/`): `Terminal` (parser plus state, `feed`, read accessors, `takeReplies`, `takeBell`, `pollEvent`), `State` (the `Handler`), and one file per concern: `Dispatch`, `Print`, `Motion`, `Edit`, `Sgr`, `Pen`, `Modes`, `Screen`, `Charset`, `TabStops`, `Reply`, `Events`.
4. Tests in `vesper-term/src/commonTest`: the conformance runner over `tests/fixtures` (read through `VESPER_TESTS_DIR`, as `vesper-harness` does), unit tests per file, and a seeded random-stream test (no exception, cursor and margins on screen, no half wide character).
5. `vesper-bench`: a `term` table (bytes per second through `Terminal.feed`), recorded in `docs/benchmarks/term.md`.
6. Verify: `./gradlew check` on macOS, the linuxX64 test binaries in a Colima container.

## Acceptance

- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
