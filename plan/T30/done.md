# T30. Done (moved into T20.3)

Not implemented here, on the creator's word: the Kotlin core has no config file or watcher yet.
They were in the Rust `config` crate this port removed, and their Kotlin port is T20.1 to
T20.3.

T30's requirement and acceptance test are now part of T20.3 in `plan/T20/task.md`: the watcher
drops no reload under a flood of directory events, and a stress test proves it. T30 closes so the
work is tracked in one place.
