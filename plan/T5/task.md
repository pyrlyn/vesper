---
status: in progress
priority: P0
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T5. Unicode tables

`vesper-unicode`: multi-stage width and grapheme-break tables generated from the Unicode data files, one width function shared by the scanner and the grid, mode 2027, and a setting for ambiguous width and Unicode version. Done when `GraphemeBreakTest.txt` passes and emoji ZWJ, variation selectors and combining marks have golden tests.

## Execution plan

The Rust tree at 69d9be4 already solved this (its `unicode` crate, its UCD generator); the port keeps its rules, its pinned Unicode 18.0.0 files and their SHA-256 sums.

1. No library: Kotlin/Native has no maintained width or segmentation library, and the table must take an ambiguous-width setting and feed graphemes one code point at a time.
2. Generator in `build-logic` (`vesper.ucd` plugin): `:vesper-unicode:generateUcd` downloads the pinned files into `build/ucd/<version>/`, checks each SHA-256, and writes `Tables.kt` (three-stage lookup stored as string constants, so nothing runs at start-up) and the `GraphemeBreakTest.txt` cases into `commonTest`. `checkUcd` fails when a committed output is stale; T25 puts it in CI. It lives in `build-logic` rather than `tools/` because the JVM has HTTP and SHA-256 and Kotlin/Native has neither.
3. `vesper-unicode`: `width(cp, options)`, `GraphemeState.next(cp)`, `ClusterWidth` and `clusterWidth` with `ClusterPolicy` (mode 2027), `AmbiguousWidth`, `UnicodeVersion`.
4. Tests: every `GraphemeBreakTest.txt` line; golden tests for emoji ZWJ, flags, skin tones, variation selectors, combining marks, Hangul, CJK and ambiguous width; every scalar value has properties; seeded random inputs never give a width over 2.
5. Verify with `./gradlew check` on macOS and on linuxX64 under Colima, and `checkUcd`.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
