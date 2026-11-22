# T22. No-op link clears are generation-neutral

Status: not started

Clearing a row link that is already empty must not bump the row generation. Otherwise a no-op clear looks like damage and forces a repaint. Done when a test shows that a second clear of an empty link leaves the generation unchanged, and that a real link change still bumps it.

Acceptance:
- [ ] The grid's link-clear path is generation-neutral for no-ops, with tests in the Kotlin/Native core.
- [ ] `./gradlew check` passes.
