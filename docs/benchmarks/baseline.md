# Baseline

One wall-clock feed of the same 1000000 bytes on 2026-11-22, arm64 / macosx. The Scull row is `Stub(80, 24).feed` from `scull-harness`, the median of 9 feeds after a warm-up. A missing binary is `not installed`. A terminal with no headless stdin feed is `unavailable` and is not compared. Numbers are this run only.

Reproduce with `./gradlew :scull-bench:runReleaseExecutableMacosArm64` (or `LinuxX64`).

| terminal | status | bytes | seconds | MiB/s |
| --- | --- | --- | --- | --- |
| scull stub | ok | 1000000 | 0.003491 | 273.19 |
| kitty | not installed | 1000000 | - | - |
| WezTerm | not installed | 1000000 | - | - |
| Alacritty | not installed | 1000000 | - | - |
| foot | not installed | 1000000 | - | - |
| Contour | not installed | 1000000 | - | - |
| Warp | unavailable: no headless stdin feed | 1000000 | - | - |
