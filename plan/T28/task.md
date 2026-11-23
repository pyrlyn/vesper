# T28. Powerline separators from cell geometry

Status: not started

The Powerline separators of shell prompts come from the bundled symbols font (T27), whose glyphs do not fill the cell exactly, so thin seams show where prompt segments meet. Done when U+E0B0–U+E0BF are drawn from the cell's own geometry, like the box sprites, and their edges reach the cell edges.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
