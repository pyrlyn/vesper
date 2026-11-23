# T23. Guard the interner marks/sweep contract against interleaved interns: done

## What was done

- The guard was already in place: T6 ported the fix of the Rust tree (c7bc167). `Marks` carries the table's insert count, and a sweep with marks taken before the latest intern frees nothing; reclaim waits for the next sweep. This is stronger than the length check the card asks for, which would still free a freed id handed out again inside the marks' length.
- The public `Marks` type now states the contract (take the set, mark every id in use, sweep, with no intern in between), and the stamp comment says why a length check would not do.
- `TablesTest` checks the contract through the public tables the terminal layer and the C ABI call: an appended style and a cluster that reuses a freed id, each interned between marks and sweep, survive the sweep; fresh marks then sweep as before, and the default style is never swept.

## How it was checked

- With the stamp check removed from `Interner.sweep`, the two new tests and `InternerTest.internsBetweenMarksAndSweepSurvive` fail ("marks taken before an intern free nothing"); the source was restored afterwards.
- `./gradlew check` on macOS arm64: ktlint and all tests pass (`scull-grid` 60 tests).
- linuxX64: the `scull-grid` test binary passes in an Ubuntu 24.04 container (amd64 on Colima); mingwX64 compiles.
