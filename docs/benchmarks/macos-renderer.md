# macOS renderer

T15 measures the Metal renderer of the macOS app (`vesper-macos`): how much output it takes per second when every read is drawn, what one frame costs, and how long a key takes to reach the screen. The throughput input is the T3 baseline's: 1 000 000 bytes of `A`..`Z` with a line feed closing every 80th byte (`baselineInput()` in `vesper-harness`), so the rows read against [baseline.md](baseline.md).

Machine: Apple M3 Max, macOS 27.0.1, Kotlin/Native 2.4.21 release build, 2× backing scale.

Reproduce from a release build:

```bash
./gradlew :vesper-macos:linkReleaseExecutableMacosArm64
vesper-macos/build/bin/macosArm64/releaseExecutable/vesper-macos.kexe --bench-renderer
vesper-macos/build/bin/macosArm64/releaseExecutable/vesper-macos.kexe --latency
```

## Throughput

An 80 by 24 `Terminal` with 1000 rows of scrollback takes the input in reads of 4 KiB or 64 KiB. After every read it updates its frame; in the "frames drawn" rows the renderer then builds the damaged rows, encodes the frame into an offscreen texture of the window's size and waits for the GPU. Each value is the median of 9 runs after one untimed run.

| reads of | frames drawn | seconds | MiB/s |
| --- | --- | --- | --- |
| 4096 B | no | 0.017697 | 53.89 |
| 4096 B | yes | 0.122730 | 7.77 |
| 65536 B | no | 0.005968 | 159.80 |
| 65536 B | yes | 0.013001 | 73.36 |

The T3 baseline's Vesper row, a bare stub feed with no parser state behind it, took 273.19 MiB/s. The real terminal with frame updates reaches 159.80 MiB/s in 64 KiB reads.

Drawing after every read is the worst case: 4 KiB reads of this input make 245 frames, each waiting for the GPU, so the time goes to about 0.5 ms per frame rather than to bytes. The app never draws more often than the main queue turns. Output that arrives faster than frames are drawn is absorbed by the next frame's damage, and `nextDrawable` holds drawing to the display's pace. Under a flood, the app's throughput is the "no" rows plus one frame per display refresh.

## Frame cost

On a full 80 by 24 screen, the median time of one feed, update and offscreen frame (waiting for the GPU), over 300 frames:

| frame after | median µs |
| --- | --- |
| one cell changed | 335.1 |
| one line scrolled in | 356.5 |
| every row changed | 533.4 |

A scroll costs about one changed row. The rows that moved keep their slots, and only the new row is built and copied into the in-flight buffer. The fixed cost of about 0.3 ms is mostly the command buffer's round trip to the GPU, which the app does not wait for.

## Latency

The probe opens a window running `cat` and types `a` (Return every 60th key) through the view's own key path, 200 times, 50 ms apart. Each key's time runs from just before the key event to two marks of the first frame drawn after the echo arrives: the GPU finishing it (`MTLCommandBuffer` completed) and the display showing it (`MTLDrawable` presented time). The path covers the key map, the PTY write, the line discipline's echo, the session's wakeup, the frame update, the row builds, the encode and the GPU.

| key to | samples | min ms | median ms | p95 ms | max ms |
| --- | --- | --- | --- | --- | --- |
| GPU done | 200 | 0.51 | 1.19 | 2.45 | 13.21 |
| on screen | 0 | - | - | - | - |

The "on screen" row was measured with the screen locked, so the window server presented none of the 200 frames. With the screen unlocked, the same command fills that row. Such a frame waits for the next display refresh, adding up to one refresh interval to the GPU time: 8.3 ms at 120 Hz.
