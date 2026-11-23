# Terminal throughput

T7 records how fast the Kotlin/Native `scull-term` takes PTY output: the parser plus the state behind it (cursor, wrapping, SGR, scrolling). The inputs are the three of [parser.md](parser.md), about 1 MiB each:

- **ASCII lines:** `lorem ipsum …`, broken into 80-column lines with CR LF.
- **UTF-8 lines:** Cyrillic, CJK, Hangul, Greek, accented Latin and emoji words, broken the same way.
- **SGR-heavy:** `ls --color`-style output in which every word carries its own 256-colour or truecolour SGR.

The terminal is 80 by 24 with 10 000 rows of scrollback. One terminal takes every feed of an input, so the timed feeds run with a full scrollback: each new line recycles the oldest row, as in a long session.

Machine: Apple M3 Max, macOS 27.0.1, Kotlin/Native 2.4.21 release build.

| Input | `Terminal.feed` | Parser alone |
| --- | --- | --- |
| ASCII lines | 4343–4542 µs (220–230 MiB/s) | 702 µs (1424 MiB/s) |
| UTF-8 lines | 20316–20445 µs (49 MiB/s) | 756 µs (1323 MiB/s) |
| SGR-heavy | 9635–9865 µs (101–104 MiB/s) | 2366 µs (423 MiB/s) |

Times are medians of 21 feeds after a warm-up feed, over three runs. The parser column is one run of the parser bench taken right after them.

There is no Rust column: the Rust `scull-term` this port replaces had no throughput bench, and it was not measured for this task.

What the numbers say:

- The state costs several times what the parser costs. On ASCII that is 3.6 ms on top of 0.7 ms per MiB, about 3.5 ns per byte.
- ASCII is written a run at a time through `Row.putAscii`. The grid bench measures the difference on a 72-cell line: 234–271 ns as one run against 1686–1769 ns with one `Row.put` per cell ([grid.md](grid.md)).
- UTF-8 text is the slow path: 19 ns per byte against 4 ns for ASCII. Every code point there goes through the grapheme-break state, the width tables and one `Row.put` of its own. Which of the three costs most was not profiled. A run path for narrow non-ASCII text is the next step if this matters.
- SGR-heavy output costs 9 ns per byte. Its words are ASCII, so the extra time over the first row is in the SGR sequences; it was not split further.

The linuxX64 build passes the same tests (Ubuntu 24.04, amd64 on Colima). Its bench figures are not recorded: the container runs under emulation on this machine.

## How to reproduce

```bash
./gradlew :scull-bench:linkReleaseExecutableMacosArm64
./scull-bench/build/bin/macosArm64/releaseExecutable/scull-bench.kexe term
```
