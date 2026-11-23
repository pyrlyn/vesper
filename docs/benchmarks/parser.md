# Parser throughput

T4 measures the Kotlin/Native `vesper-parser` against `vte` 0.15.0, the parser Alacritty uses. Both parse the same three inputs on the same machine:

- **ASCII lines:** `lorem ipsum …`, broken into 80-column lines with CR LF.
- **UTF-8 lines:** Cyrillic, CJK, Hangul, Greek, accented Latin and emoji words, broken the same way.
- **SGR-heavy:** `ls --color`-style output in which every word carries its own 256-colour or truecolour SGR.

Each input is about 1 MiB. The handler only counts printed bytes and CSI dispatches, so the time is the parser's own cost. `vte` delivers one `print` per character, which is how its API works; Scull delivers one per run of text.

Machine: Apple M3 Max, macOS 27.0.1, Kotlin/Native 2.4.21 release build, Rust 1.99.0 release build.

| Input | Scull (Kotlin/Native) | vte 0.15.0 | Scull / vte |
| --- | --- | --- | --- |
| ASCII lines | 686–746 µs (1340–1458 MiB/s) | 913 µs (1095 MiB/s) | 1.2–1.3× faster |
| UTF-8 lines | 744–748 µs (1337–1343 MiB/s) | 1419 µs (704 MiB/s) | 1.9× faster |
| SGR-heavy | 2363–2423 µs (413–423 MiB/s) | 2958 µs (338 MiB/s) | 1.2× faster |

Scull times are medians of 21 feeds after a warm-up, from two runs taken just before and just after the `vte` run. `vte` times are divan medians of 100 samples.

The Rust `vesper-parser` that this one ports scanned ASCII eight bytes at a time and ran the same input in 364 µs. The Kotlin port checks one byte per step. A word-at-a-time scan (`ByteArray.getLongAt` in a native source set) is the next step if ASCII throughput matters more than it does at this margin.

## How to reproduce

Scull:

```bash
./gradlew :vesper-bench:linkReleaseExecutableMacosArm64
./vesper-bench/build/bin/macosArm64/releaseExecutable/vesper-bench.kexe parser
```

`vte`: check out 69d9be4 (the last Rust tree), then run `cargo bench -p vesper-parser --bench throughput`. Its `benches/throughput.rs` generates the same three inputs and runs the Rust `vesper-parser` next to `vte`.
