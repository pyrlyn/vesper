# T25. Done

Merged through https://github.com/pyrlyn/vesper/pull/49.

- **No coverage-guided runner.** None runs Kotlin/Native code. kotlinx.fuzz is built on Jazzer and
  is JVM-only, the modules have no JVM target, and Kotlin/Native has no libFuzzer integration.
  `toolchain.md` records this.
- **`vesper.harness.fuzzCases(seed, cases) { random -> … }`** drives every fuzz corpus: the
  parser, the stub, iTerm2, the image store, sixel and kitty.
  - Under `./gradlew check` it runs the fixed seeded cases.
  - With `VESPER_FUZZ_SECONDS` set, it runs for that budget (capped at an hour) from a fresh seed,
    or from `VESPER_FUZZ_SEED`.
  - A failure names its case and the seed that reproduces it.
- **The test tasks.** `vesper.kmp` passes both variables to the native test tasks and declares them
  as inputs. A budgeted run without a fixed seed is never up to date and never cached.
- **The CI job `fuzz and Unicode (linuxX64)`** runs on every non-draft pull request:
  - `:vesper-unicode:checkUcd`, with the pinned data files cached under the hash of `Ucd.kt`;
  - the parser, harness and image corpora for 30 s each.
- **Docs.** `PLAN.md` and `toolchain.md` describe the job. `AGENTS.md` shows how to run a budget
  and how to rerun a seed.

Reused: `environment` from `vesper-harness` reads the variables. The fuzz targets and their seed
corpora are the existing ones; they moved onto the helper, and no generator was copied. The stub's
LCG corpus became a `Random` one.

How it was checked:
- **Local gate.** `./gradlew ktlintFormat check linkDebugTestLinuxX64
  :vesper-windows:linkDebugTestMingwX64` and `:vesper-unicode:checkUcd` pass on macOS arm64.
- **Linux tests.** Every `linuxX64` test binary passes in an amd64 Colima container, and the three
  fuzz modules also pass there with `VESPER_FUZZ_SECONDS=3`.
- **Seed reproduction.** A throwaway probe, since removed, failed a budgeted run at case 5278.
  Rerunning with the seed that run printed failed at the same case.
- **CI.** Run https://github.com/pyrlyn/vesper/actions/runs/45924719498 is green:
  - the new job ran `checkUcd`, downloading and caching the data files, in 36 s;
  - its fuzz step took 3 min 48 s;
  - every other check passed.
