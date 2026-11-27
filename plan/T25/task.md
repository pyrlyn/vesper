---
status: in progress
priority: P1
complexity: 2
readiness: 80%
agent: Claude Code / claude-opus-5-5
---

# T25. CI: fuzz targets and the Unicode table freshness check

CI must run the fuzz (or property) targets for the parser and image decoders, and must fail when the committed Unicode tables differ from the pinned data files. These checks stay out of the default `./gradlew check` only if a dedicated CI job runs them on every pull request.

Acceptance:
- [ ] A CI job smokes every fuzz target in a short, bounded run.
- [ ] A CI job regenerates or verifies the Unicode tables from the pinned sources and fails on drift.
- [ ] Both jobs are documented in `PLAN.md` and `toolchain.md`.

## Execution plan

The runner: no coverage-guided fuzzer runs Kotlin/Native code. kotlinx.fuzz is built on Jazzer and is JVM-only, and the modules have no JVM target; Kotlin/Native has no libFuzzer integration. So the fuzz targets keep their seeded corpora in the tests, and CI runs the same corpora for a time budget with a fresh seed.

1. `vesper-harness`: `fuzzCases(seed, cases) { random -> … }`. It runs `cases` cases from the fixed seed by default. When `VESPER_FUZZ_SECONDS` is set, it runs for that many seconds from a fresh seed instead, or from `VESPER_FUZZ_SEED` if that is set. A failure names the case and the seed that reproduces it.
2. The fuzz tests of `vesper-parser`, `vesper-harness` and `vesper-image` use it. These are the parser, the stub, and the iTerm2, store, sixel and kitty targets. `vesper-parser` and `vesper-image` take `vesper-harness` as a test dependency.
3. `vesper.kmp`: the native test tasks pass `VESPER_FUZZ_SECONDS` and `VESPER_FUZZ_SEED` through and declare them as inputs. A fuzz run with no fixed seed is never up to date.
4. CI: a `fuzz and Unicode (linuxX64)` job on every non-draft pull request. It runs `:vesper-unicode:checkUcd`, with the downloaded data files cached under their pinned hashes. It also runs the three modules' `linuxX64Test` with `VESPER_FUZZ_SECONDS` set.
5. Docs: `PLAN.md` (the T25 line, the CI table and the fuzz note), `toolchain.md` (the runner decision, the job) and `AGENTS.md` if it lists the test variables.
6. Verify: the gate, with the Linux tests in Colima both with and without a budget; `checkUcd` locally; a failing case reproduced through its seed; the CI run.
