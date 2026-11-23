---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T19.3. Kitty graphics protocol

Part of T19, a pure decoder over the `scull-image` store. It covers:

- APC `G` key parsing;
- chunked transmission (`m=1`);
- formats 24, 32 and 100 (PNG through `scull-png`);
- zlib (`o=z`) through `platform.zlib` with a cap;
- the transmit, put and delete actions;
- client image ids and numbers;
- quiet levels and the reply strings.

kitty is GPL-3.0-only: implement from the protocol specification, never port its code.

Done when golden tests cover each action and reply, the decoder has a fuzz entry run by the tests, and `./gradlew check` passes.

## Findings

From the specification, https://sw.kovidgoyal.net/kitty/graphics-protocol/ (checked 2026-11-23,
documentation of kitty 0.51.1):

- A command is `ESC _ G <control data> ; <base64 payload> ESC \`. Control data is `key=value`
  pairs separated by `,`, each key one character.
- Defaults: `a=t` (transmit), `t=d` (direct), `f=32` (RGBA). `f=24` is RGB, `f=100` is PNG. `s`
  and `v` give the pixel size of raw data, whose byte count must match it. `o=z` means the data
  was zlib-deflated before base64.
- Chunks: the first carries all keys and `m=1`, later ones only `m` (and `q`), the last `m=0`.
  Every chunk but the last is a multiple of 4 base64 bytes, so the payloads concatenate.
- Replies: `ESC _ G i=<id>[,I=<n>][,p=<pid>];OK ESC \` or `;<CODE>:<detail>`, sent when the
  command named an image (`i` or `I`). `q=1` drops OK replies, `q=2` errors too.
- `i` is the client's id: sending it again replaces the image and its placements. `I` is an
  image number: the terminal picks the id and replies with both; later commands with `I` act on
  the newest image with that number. `i` and `I` together are an error (`EINVAL`).
- A placement is keyed by `(i, p)`; the same pair replaces it. `x,y,w,h` crop the image,
  `X,Y` offset it inside its first cell, `c,r` give its size in cells (one of them alone keeps
  the aspect ratio), `z` its stacking (below text when negative). Unless `C=1` the cursor moves
  right by the placement's columns and down by its rows.
- `a=d` deletes: `d` names what (`a` visible, `i` by id and `p`, `n` by number, `c` at the
  cursor, `p` at a cell, `q` at a cell and z, `r` an id range, `x` a column, `y` a row, `z` a
  z-index). Lower case keeps the image data, upper case also frees images nothing shows any
  more. A delete during a chunked upload aborts the upload.
- `a=q` checks that the terminal can load the data without storing it.

## Decisions

- Only direct media (`t=d`). Files, temp files and shared memory (`t=f,t,s`) answer `EBADF`: a
  terminal must not read local paths a remote program names.
- Animation (`a=f,a,c`, `d=f`), Unicode placeholders (`U=1`) and placements relative to another
  placement (`P`, `Q`, `H`, `V`) answer `EINVAL`, unsupported.
- Deletes never reply, as no client waits for one.
- Error codes: `EINVAL` bad keys or values, `ENOENT` a put naming a missing image, `ENODATA` raw
  data that does not match `s` by `v`, `EFBIG` data past the decoder's caps, `ENOSPC` an image
  larger than the store's whole quota, `EBADF` non-direct media.
- Ids the terminal picks for `I` count down from 2^32 − 1, away from the small ids clients pick.
- The decoder's maps (client ids, numbers, placement ids) drop entries whose images or
  placements the store evicted, so a client cannot grow them past what the store holds.

## Execution plan

1. `scull-png`: a public `Zlib` object, `deflate` (the encoder's, moved) and a capped `inflate`
   for `o=z`, with `ZlibException`.
2. `scull-image`: the base64 part buffer shared by the iTerm2 and kitty decoders; `Placement`
   gains the `X,Y` cell offsets.
3. `scull-image`: `KittyGraphics` (keys, chunks, formats, actions, ids, replies) and
   `fuzzKitty`.
4. Tests: golden replies per action and error, chunking, quiet levels, ids and numbers, each
   delete kind, a seeded mutated corpus through `fuzzKitty`; zlib tests in `scull-png`.
5. Verify: `./gradlew ktlintFormat check` on macOS; the `scull-png` and `scull-image` linuxX64
   test binaries in Colima.
