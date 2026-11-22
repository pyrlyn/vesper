# 15 · Monitor — Logs

Figma frame: [15 · Monitor — Logs](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=81-4432) (`81:4432`)

The **Logs** tab of the Monitor panel: a live log stream.

## Components

- Source picker.
- Level badges that filter (error, warn, info, debug).
- A **following** indicator.
- The stream; one line expanded to its stack trace and request metadata, with actions on it.

## States

- Following: new lines scroll in.
- Paused: the user scrolled up; a button jumps back to the newest line.
- Line: collapsed, expanded.
- Filtered by level; empty result.
- Source with no output yet.

## Errors and offline

- The source ends or cannot be opened: the stream stops with one line saying why; earlier lines stay.
- A remote source whose connection drops shows a reconnect countdown above the stream.
- Lines arrive faster than they can be drawn: the view keeps the newest and says how many were skipped.
- Offline affects remote sources only.
