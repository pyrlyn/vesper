# 28 · Quit — running processes

Figma frame: [28 · Quit — running processes](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=90-7910) (`90:7910`)

A quit confirmation shown when something is still running.

## Components

- A list of what will stop: a dev server, an SSH session with active port forwards, a workflow run, each with a status dot.
- "Restore tabs and scrollback on next launch".
- A hint that pressing `⌘Q` again forces the quit.
- Cancel and Quit.

## States

- Shown only when at least one process is running; otherwise the app quits at once.
- Quitting: processes get SIGTERM and the list shows each one ending.
- Forced by the second `⌘Q`.

## Errors and offline

- A process that ignores SIGTERM is killed after a short grace period; the dialog never hangs on it.
- Saving the session for restore fails: the app says so and asks whether to quit anyway.
- Nothing here needs the network.
