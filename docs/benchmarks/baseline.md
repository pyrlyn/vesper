# Baseline

The same 1000000 bytes on 2026-11-26, x64 / linux. The Vesper row is `Stub(80, 24).feed` from `vesper-harness`, the median of 9 feeds after a warm-up. Each terminal opens one window running `vesper-bench write`, which writes the bytes to it once untimed and 5 times timed, each write ended once the terminal has read all of it; its row is the median. A missing binary is `not installed`; a terminal that cannot be run this way or fails is `unavailable` and is not compared. Numbers are this run only.

Reproduce with `./gradlew :vesper-bench:runReleaseExecutableMacosArm64` (or `LinuxX64`, or `MingwX64` on Windows); the Linux terminals run in a container through `vesper-bench/terminals.sh`.

Recorded by CI run https://github.com/pyrlyn/vesper/actions/runs/45912179625 (commit 255ef68), job `ubuntu-24.04 (linuxX64)`, inside the pinned `ubuntu:26.04` container of `vesper-bench/terminals.sh`. The displays are headless and render in software: X11 on Xvfb with Mesa for kitty, WezTerm, Alacritty and Contour, Wayland on cage with pixman for foot. The stub draws nothing, so its row is the parser and grid alone.

| terminal | status | bytes | seconds | MiB/s |
| --- | --- | --- | --- | --- |
| vesper stub | ok | 1000000 | 0.002191 | 435.27 |
| kitty | ok | 1000000 | 0.017199 | 55.45 |
| WezTerm | ok | 1000000 | 0.020280 | 47.02 |
| Alacritty | ok | 1000000 | 0.016049 | 59.42 |
| foot | ok | 1000000 | 0.006454 | 147.76 |
| Contour | ok | 1000000 | 0.013639 | 69.92 |
| Warp | not installed | 1000000 | - | - |
