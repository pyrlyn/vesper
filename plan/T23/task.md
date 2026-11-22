# T23. Guard the interner marks/sweep contract against interleaved interns

Status: not started

The cluster and style interner must not reclaim a live id if a caller interns between taking marks and sweeping. `sweep` must skip ids at or beyond the marks' length (or the lengths are asserted), so a missed mark cannot free a still-used style or cluster.

Acceptance:
- [ ] The interner contract is documented and tested in `scull-grid`, including an interleaved intern between marks and sweep.
- [ ] `./gradlew check` passes.
