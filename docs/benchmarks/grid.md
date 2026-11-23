# Grid memory and scroll cost

T6 records what one scrollback row costs in the Kotlin/Native `vesper-grid`, and what one scrolled line costs. The fixture is the one of the Rust `vesper-grid` bench this port replaces (`benches/scrollback.rs` at 69d9be4): 120 columns, 40 screen rows, and three kinds of line:

- **Blank:** nothing written.
- **Plain text:** 72 cells in the default style, a typical log line.
- **SGR-heavy:** 120 cells with a style change in every cell, 33 styles in the table.

Machine: Apple M3 Max, macOS 27.0.1, Kotlin/Native 2.4.21 release build. The Rust figures are the ones recorded in the header of the Rust bench on 2026-10-07 (rustc 1.99.0, Apple Silicon). They were not run again for this task.

## Memory per scrollback row

| Row at 120 columns | Scull (Kotlin/Native), measured heap | Rust `vesper-grid`, computed |
| --- | --- | --- |
| Blank | 75 B | 56 B |
| Plain text (72 cells) | 675 B | 632 B |
| SGR-heavy (120 cells) | 1062 B | 1016 B |

The two columns do not count the same thing. The Rust figure is the row header plus its stored cells. The Kotlin/Native figure is heap as the collector counts it, so it also holds the allocator's header for each object, the row's slot in the ring and a share of the grid's tables.

A cell is 8 bytes, so the cells of a plain row are 576 B and those of an SGR-heavy row 960 B. A row with content therefore costs about 100 B on top of its cells, and a blank row 75 B with no cell array at all.

How it is measured:

- The figure is the growth of `GC.lastGCInfo.memoryUsageAfter["heap"]` over building a grid with a full history, divided by its history rows.
- The sample is the largest ring: 131 032 history rows plus the 40 screen rows fill all 131 072 slots. The collector's figure moves in steps of 128 KiB and more, which is tens of bytes per row over 10 000 rows and under 1 B per row here.
- The bench collects twice before each reading. After one collection the figure still counted garbage: an SGR-heavy row read 409 B.
- The three grids stay alive until the last reading. With a grid dropped between two readings a plain row read 619 B.
- The table is the median of five passes in one process (T26). Each pass builds the three grids again after the previous pass dropped its own, and takes its own first reading, so it subtracts nothing the previous pass held.
- The passes agree within 1 B per row: blank 75–76 B, plain 675–676 B, SGR-heavy 1061–1062 B, over three runs on macOS and two on the linuxX64 build (Ubuntu 24.04, amd64 on Colima). The first pass charged about 2 B more to the blank row, which was measured first: about 260 KB allocated once per process. The single pass of T6 recorded it as part of the row (77–78 B).

A one-off probe that is not part of the bench put a `Row` object at 64 B and a `LongArray` of `n` cells at 24 + 8·`n` B (500 000 rows and 100 000 arrays of each size). With the 8 B ring slot that accounts for 72, 672 and 1056 B of the three figures.

## Cost of a scrolled line

One line into a full history of 10 000 rows:

| Operation | Scull (Kotlin/Native) | Rust `vesper-grid` |
| --- | --- | --- |
| Scroll only | 27.5–27.7 ns | 25–66 ns |
| Write a plain line and scroll | 1559–1580 ns | 513 ns |
| Write an SGR-heavy line and scroll | 2887–2896 ns | 388 ns |
| Write a plain line as one run and scroll (T7) | 234–271 ns | not measured |

Scull times are medians of 21 batches of 20 000 lines after a warm-up batch, over three runs. Rust times are divan medians.

Scrolling is an offset change plus one recycled row, and costs what it cost in Rust. Writing a line is 3 to 7 times slower, and the time is in `Row.put`, not in the ring: in a one-off probe 72 `put` calls with no scroll took 1140 ns, 16 ns per cell. The disassembly shows why. Kotlin/Native enters a shadow-stack frame on every call, with a thread-local lookup and a block of zeroed slots, and `put` is called once per cell.

What follows from it:

- T7 should write a run of cells in one call, so the frame is paid per run and not per cell. T7 added `Row.putAscii` for it. The row "as one run" is that call on the plain line, from three runs in which the per-cell line took 1686–1769 ns: 6 to 7 times faster.
- `Row.compact` no longer copies an array that already fits. It saves one allocation for every full-width line. The SGR-heavy time moved from 2919–2952 ns to 2887–2896 ns, which is within the noise.
- Editing cell flags in place, without rebuilding the cell, was tried and dropped: 144 million `put` calls took 2.36 s with it and 2.22 s without.

## How to reproduce

```bash
./gradlew :vesper-bench:linkReleaseExecutableMacosArm64
./vesper-bench/build/bin/macosArm64/releaseExecutable/vesper-bench.kexe grid
```

Each memory pass keeps three full rings alive, about 250 MB of heap. The whole run takes about 6 s.

Rust: check out 69d9be4 (the last Rust tree), then run `cargo bench -p vesper-grid --bench scrollback`.
