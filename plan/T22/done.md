# T22. No-op link clears are generation-neutral: done

## What was done

- The behaviour was already in place: T6 ported the fix of the Rust tree (b65fa68), so `Row.clearLinks` bumps the generation only when `dropLinks` removed link coverage.
- `RowTest.clearingLinksWhereNoneExistIsGenerationNeutral` now covers what the task names: a second clear after the link is gone, clears next to a link that do not overlap it, empty and out-of-row ranges (all generation-neutral), and a partial and a full clear of a real link (both bump).
- The comment in `clearLinks` gave a reason that does not hold in this port (a plain cell write bumps the generation anyway); it now gives the one that does: a bump reads as damage to T9.

## Not covered here

The other half of the Rust fix bumps the generation when narrowing a row drops only links off its tail. Rows do not change width before T11 (resize and reflow), so that case has nothing to test yet; T11 has to carry it.

## How it was checked

- The test fails against two broken versions of `clearLinks`: one that always bumps ("nothing was linked") and one that bumps whenever the row holds any link ("ranges that touch the link without overlapping it hold none of it"). The source was restored after each run.
- `./gradlew check` on macOS arm64: ktlint and all tests pass (`vesper-grid` 58 tests).
- linuxX64: the `vesper-grid` test binary passes in an Ubuntu 24.04 container (amd64 on Colima); mingwX64 compiles.
