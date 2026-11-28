# T900. Done

Merged through https://github.com/pyrlyn/vesper/pull/51.

- **The audit.** It ran on the whole resolved Gradle graph, 113 artifacts, against OSV, which carries the GitHub advisories. `toolchain.md` → *Advisory audit (T900)* records how to rerun it and what it found.
- **Fixed: `ch.qos.logback:logback-core` 1.3.16.** It has GHSA-qqpg-mvqg-649v, GHSA-p47f-322f-whfh and GHSA-jhq6-gfmj-v8fx, all low.
  - It comes from the `ktlint` configuration through `ktlint-cli` 1.8.0, which is the latest release.
  - `vesper.kmp` now constrains `logback-classic` and `logback-core` on that configuration to 1.5.38 from the catalog, which raises `slf4j-api` to 2.0.17. Neither has an OSV entry.
- **Waived: `io.opentelemetry:opentelemetry-api` 1.41.0.** It has GHSA-ccrj-gw8r-854m and GHSA-rcgg-9c38-7xpx, both moderate.
  - It is reached only through KGP 2.4.21's Swift Export worker (`swiftExportClasspathResolvable`), which only an Xcode build invokes.
  - A run of the gate with a logging init script resolved that configuration in no module.
- **Housekeeping.** `.gitignore` ignores the audit's dry-run file. `PLAN.md` ticks T29 and T900.

Reused: the catalog lookup `vesper.kmp` already did for the ktlint version, now shared by both uses. No new tool: `osv-scanner` would read the dry-run file directly, but it was not installed in this session.

How it was checked:
- **Gate.** `./gradlew ktlintFormat check linkDebugTestLinuxX64 :vesper-windows:linkDebugTestMingwX64 --rerun-tasks` passes, with all 548 tasks executed.
- **ktlint still works.** A planted violation fails `:vesper-parser:ktlintCommonMainSourceSetCheck` and is reported.
- **The new graph.** `:vesper-parser:dependencies --configuration ktlint` shows `logback-classic 1.3.16 -> 1.5.38`.
- **The audit rerun** reports only the waived `opentelemetry-api`.
