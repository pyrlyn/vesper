# T24. Reconcile harness eager-wrap with xterm's deferred DECAWM

Status: not started

The conformance harness must match xterm's deferred wrap (DECAWM): a printable character at the last column does not wrap until the next printable arrives. Today-style eager wrap will fail golden tests against xterm. Keep `scull-term` behaviour documented and fix the harness (or the core, if the tests prove the core is wrong).

Acceptance:
- [ ] Golden fixtures agree with xterm's deferred DECAWM, with tests in `scull-harness`.
- [ ] `./gradlew check` passes.
