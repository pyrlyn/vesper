# T26. Close grid-layer test gaps

Status: not started

Cover the end-of-row wide-wrap path for a leading spacer cell, and average more than a single pass in the scrollback-memory benchmark so baseline numbers are not noisy.

Acceptance:
- [ ] The wide-wrap spacer path has a failing-then-passing test in `scull-grid`.
- [ ] The scrollback-memory benchmark reports a stable average, not a single pass.
- [ ] `./gradlew check` passes.
