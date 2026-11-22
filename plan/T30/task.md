# T30. Config watcher never misses a reload under load

Status: not started

The config file watcher must not drop a reload when the config directory is flooded with events. Under a burst of file-system notifications, every write that changes a watched file still produces a reload (or a coalesced reload that includes that write). Tests stress the watcher and fail if an event is lost.

Acceptance:
- [ ] A stress test floods the config directory and still observes every meaningful write.
- [ ] `./gradlew check` passes.
