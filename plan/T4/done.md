# T4. Parser: done

`vesper-parser` is a Kotlin/Native port of the Rust parser removed in #62. It keeps the same state machine, caps and fuzz-found fixes.

- **State machine:** our own table after Paul Williams' DEC parser, not `vtparse`. The port reuses a design that already passed its fuzz target, and needs no C dependency or cinterop.
- **Bulk scanner:** text up to the next control byte goes out as one `print` of the input's own bytes, with no copy. UTF-8 is validated in the same pass, and two- and three-byte forms take a fast path. Invalid input arrives as U+FFFD, one per maximal invalid subpart. A character cut by a `feed` boundary is completed on the next call. C1 controls are read in their UTF-8 form.
- **Sequences and actions:** CSI with colon sub-parameters, ESC, OSC (BEL or ST), DCS and APC are streamed to a `Handler` with no-op defaults, as the typed actions `Esc`, `Csi`, `Osc` and `End`.
- **Caps, each with a test:** `MAX_PARAMS` 32 (values saturate at 65535), `MAX_INTERMEDIATES` 2, `MAX_OSC_BYTES` 16 MiB (buffer shrinks back to 64 KiB), `MAX_DCS_BYTES` 16 MiB, `MAX_APC_BYTES` 4 MiB.
- **Fuzz target:** `parserFeedIsChunkingInvariant`. Whole, byte-by-byte and data-chosen chunked feeds must give the same actions, and every print must be strict UTF-8. It runs over a seeded corpus of 3000 inputs in the tests.
- **Bench:** `vesper-bench parser`. On an M3 Max, plain ASCII runs at 1340–1458 MiB/s against 1095 MiB/s for `vte` 0.15.0 on the same input; UTF-8 and SGR-heavy output are faster too. Numbers are in `docs/benchmarks/parser.md`.

Size: about 1170 lines of main code and bench, and 520 of tests, over the 500-line budget. It was kept as one task because the parser, its caps and its tests only make sense together.

Checked:
- macOS: `./gradlew check` passes, 46 parser tests.
- Linux: the same tests on `linuxX64` in an amd64 Ubuntu 24.04 container on Colima.
- Windows: `mingwX64` in CI on the PR.

PR: https://github.com/pyrlyn/vesper/pull/67
