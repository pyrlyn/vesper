---
status: in progress
priority: P0
complexity: 3
readiness: 80%
agent: Claude Code / claude-opus-5-5
---

# T3. Conformance and benchmark harness

Built before the features so every later task lands with tests. Recorded stream → expected grid ref tests, golden frame snapshots, fuzz targets, and a throughput and latency benchmark that runs the same input through Vesper and the six reference terminals. Done when the harness runs in CI against a stub core and the benchmark produces a baseline table for the reference terminals. "Better than all of them" is measured here, not claimed.

Execution plan (a port of the previous Rust harness and bench crates, last at 69d9be4):
1. `vesper-harness` library: the `Stub` grid (printable ASCII, xterm deferred wrap, no scroll) that later tasks replace with the real core. Its tests compare recorded streams with the golden frames in `tests/harness/*.txt`, read through Okio from the path Gradle passes in `VESPER_TESTS_DIR`.
2. Fuzz target: `stubFeedKeepsSize`, the invariant a fuzzer checks, lives in `vesper-harness`. A seeded corpus test (edge slices plus 64 LCG-generated inputs) runs it under `./gradlew check`. Picking a coverage-guided runner is T25.
3. `vesper-bench` executable: builds the fixed 1,000,000-byte payload (its FNV-1a hash pinned by a test), times the median of nine `Stub` feeds after a warm-up, looks up the six reference terminals on `PATH` (kitty is fed headless through `posix_spawn` with a 5 s timeout; the others have no headless stdin feed), and writes `docs/benchmarks/baseline.md`. `./gradlew :vesper-bench:runReleaseExecutableMacosArm64` reproduces it.
4. Verify: `./gradlew check` on macOS; the Linux test binaries in a Colima container; regenerate `baseline.md` on this machine.

The task stays open until the same input is timed on the six reference terminals; the harness half is done when it runs in CI against the stub.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native.
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.

## Progress

- Harness, fuzz invariant with its seeded corpus, and bench are in https://github.com/pyrlyn/vesper/pull/65. `./gradlew check` passes on macOS arm64; the `linuxX64` test binaries pass in an amd64 container on Colima.
- Open: none of kitty, WezTerm, Alacritty, foot or Contour is installed on the measuring Mac, and Warp has no headless stdin feed, so `docs/benchmarks/baseline.md` has only the stub row timed. The task closes when the reference terminals are timed on the same input.
