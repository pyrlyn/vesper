---
status: in progress
priority: P0
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T4. Parser

`vesper-parser`: a bulk scanner that delivers text up to the next control byte as one run, in front of a table state machine for CSI, OSC, DCS and APC, emitting typed actions. Payloads are dispatched in place with hard length caps. Done when the fuzz target runs clean and throughput on plain text beats a published VTE-class parser baseline in the T3 benchmark.

## Execution plan

The state machine is our own table after Paul Williams' DEC parser, ported from the Rust `vesper-parser` removed in #62 (last at 69d9be4), which already passed its fuzz target. `vtparse` would add a C dependency and a cinterop step for less than that port gives.

1. `vesper-parser/src/commonMain/kotlin/vesper/parser/`:
   - `Params.kt`: up to 32 values with saturation and a colon sub-parameter mask.
   - `Scan.kt`: finds the next special byte, eight bytes per step.
   - `Handler.kt`: the typed actions `Esc`, `Csi`, `Osc` and `End`, and a handler interface with no-op defaults.
   - `Parser.kt`: the state machine with the caps `MAX_INTERMEDIATES`, `MAX_OSC_BYTES`, `MAX_DCS_BYTES` and `MAX_APC_BYTES`. UTF-8 split across `feed` calls is kept, C1 controls are read in their UTF-8 form, and text is delivered as validated UTF-8 byte ranges of the input, with no copy.
2. Tests in `commonTest`: every Rust unit test, one test per cap, and a seeded property corpus. Any chunking must give the same actions, text without controls must print unchanged, and lossy decoding must match.
3. Fuzz target: `parserFeedIsChunkingInvariant(ByteArray)` in `commonMain`, run over a seeded random corpus in the tests.
4. Bench: `vesper-bench` gains a `parser` mode that times plain ASCII, UTF-8 and SGR-heavy inputs (the same generators as the Rust bench). `vte` runs on the same machine from the Rust bench at 69d9be4, and both go into `docs/benchmarks/parser.md`.
5. Verify: `./gradlew check` on macOS, `linuxX64` tests in a Colima container, and `mingwX64` in CI.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
