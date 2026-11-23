# T25. CI: fuzz targets and the Unicode table freshness check

Status: not started

CI must run the fuzz (or property) targets for the parser and image decoders, and must fail when the committed Unicode tables differ from the pinned data files. These checks stay out of the default `./gradlew check` only if a dedicated CI job runs them on every pull request.

Acceptance:
- [ ] A CI job smokes every fuzz target in a short, bounded run.
- [ ] A CI job regenerates or verifies the Unicode tables from the pinned sources and fails on drift.
- [ ] Both jobs are documented in `PLAN.md` and `toolchain.md`.
