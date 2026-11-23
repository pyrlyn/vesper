# T5. Unicode tables — done

## What was done

- `scull-unicode` ports the Rust crate from 69d9be4 to Kotlin/Native:
  - `width(cp, options)` gives 0, 1 or 2 cells.
  - `GraphemeState.next(cp)` segments extended grapheme clusters one code point at a time (UAX #29, Unicode 18.0.0).
  - `ClusterWidth`/`clusterWidth` use `ClusterPolicy` for DEC private mode 2027.
  - Options: `AmbiguousWidth` and `UnicodeVersion`.
  - The lookup returns a packed `Int` and allocates nothing.
  - A value outside the code space reads as U+FFFD, so every function is total.
- The tables are a three-stage lookup (4/5 bits, 31 property records) stored as string constants. They are static data, so nothing runs when the library loads.
- Generator: `build-logic` (`scull.ucd` plugin, `scull/ucd/Ucd.kt`).
  - `./gradlew :scull-unicode:generateUcd` downloads the seven pinned Unicode 18.0.0 files once into `scull-unicode/build/ucd/`.
  - It checks each file's SHA-256 and writes `Tables.kt` and the `GraphemeBreakTest.txt` cases (`GraphemeBreakCases.kt` in `commonTest`).
  - `checkUcd` fails when a committed output is stale. It stays out of `check` because it needs unicode.org; T25 puts it in CI.
  - It lives in `build-logic` rather than `tools/` because the JVM has HTTP and SHA-256, and Kotlin/Native has neither.
- No library: Kotlin/Native has no maintained width or segmentation library with an ambiguous-width setting and incremental segmentation.

About 930 hand-written lines (generator, library and tests), over the 500-line budget. The generator, the tables and the code reading them only make sense together, so the task was not split.

## How it was checked

- `./gradlew check` on macOS (M3 Max) passes, with 24 scull-unicode tests:
  - Every line of `GraphemeBreakTest.txt` (853 cases).
  - Golden tests for ZWJ families, HEART ON FIRE with and without VS16, flags, skin tones, VS15/VS16, keycaps, mode 2027 reset, combining marks, Indic conjuncts, Hangul jamo, CJK, halfwidth and ambiguous width.
  - Every code point has properties.
  - A width never exceeds 2.
  - Seeded random text segments and sizes without error.
- The same 24 tests pass on linuxX64 in Ubuntu 24.04 (amd64) on Colima.
- `checkUcd` passes, and fails when a generated file is edited.
- mingwX64 is covered by the CI of https://github.com/pyrlyn/scull/pull/68.
