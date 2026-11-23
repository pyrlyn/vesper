---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T28. Powerline separators from cell geometry

The Powerline separators of shell prompts come from the bundled symbols font (T27), whose glyphs do not fill the cell exactly, so thin seams show where prompt segments meet. Done when U+E0B0–U+E0BF are drawn from the cell's own geometry on both hosts, like the box sprites, and their edges reach the cell edges.

Check: sprite tests that every code point in the range has a sprite and its edges reach the cell edges.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
