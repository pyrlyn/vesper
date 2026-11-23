# T19.3. Done

## What changed

- `scull-png`: a public `Zlib` object.
  - `deflate` is the PNG encoder's, moved there.
  - `inflate(data, maxBytes)` grows its output only as zlib fills it, so a bomb stops at the
    cap. It throws `ZlibTooLargeException` or `ZlibException`.
- `scull-image`:
  - `KittyGraphics` decodes APC `G` commands into an `ImageStore`:
    - key parsing;
    - chunked uploads, whose later chunks may change the quiet level;
    - formats 24, 32 and 100, raw or `o=z`;
    - transmit (`t`, `T`, `q`), put (`p`) and every delete kind except animation frames;
    - client ids, image numbers (ids picked from 2^32 − 1 down) and placement ids;
    - quiet levels and `OK` / `CODE:detail` replies.
  - `KittyContext` carries the cursor, cell size and screen. `KittyOutcome` carries the reply
    and the cursor move.
  - What is refused and why is in `task.md` → Decisions.
  - `Placement` gains `offsetX` / `offsetY` for kitty's `X` and `Y`.
  - `Base64Parts` is the part buffer and capped base64 decode the iTerm2 decoder had.
    `ITerm2Images` and `KittyGraphics` now share it.
  - `fuzzKitty` is the fuzz entry. It checks that nothing escapes, that replies are well
    formed, and the store's and decoder's bookkeeping.
- `toolchain.md`: the `platform.zlib` row also names kitty's `o=z`.

Reused rather than written: zlib through the existing `platform.zlib` binding, PNG through
`decodePng`, base64 from the iTerm2 decoder (extracted), and eviction and quotas from
`ImageStore`.

## How it was checked

- `KittyTest` has 23 tests:
  - golden replies for each action and each error code;
  - chunking, aborts and caps;
  - quiet levels, ids and numbers;
  - placement geometry and every delete kind;
  - map bounds while the store evicts;
  - a seeded mutated corpus through `fuzzKitty`.
- `ZlibTest` covers the round trip, the exact cap, a 64 MiB bomb, broken streams and trailing
  bytes.
- macOS arm64: `./gradlew ktlintFormat check` passes.
- Linux x86_64: the `scull-png` and `scull-image` test binaries pass in Colima (`ubuntu:24.04`,
  amd64).

## Commits

- `T19.3: claim the task`
- `T19.3: kitty graphics protocol decoder`
- `T19.3: close the task`
