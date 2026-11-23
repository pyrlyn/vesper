# 04 · Command palette (⌘K)

Figma frame: [04 · Command palette (⌘K)](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=75-1180) (`75:1180`)

The command palette over the main window. One palette searches hosts, workflows, commands, history and directories. The frame shows the query `prod`.

## Components

- Search field with the query and a clear button.
- Filter badges, one per kind of result.
- Results grouped into **Hosts**, **Workflows** and **Commands**; each item has an icon, a title, a secondary line and its shortcut.
- Footer: key hints and the result count (`12 results`).
- A scrim dims the window behind the palette.

## States

- Empty query: recent items per group.
- Typing: results filter on every keystroke, the first item is selected.
- A filter badge on: only that group is listed.
- No results: one line saying so, with the query echoed.
- Item hover and keyboard selection share one highlight.

## Errors and offline

- Running an item that fails (host unreachable, workflow missing) closes the palette and reports the failure where the action lands: a failed block or the connection-lost screen, not inside the palette.
- Offline: hosts stay listed, because connecting may still work on a local network; items that need the network to exist at all (shared links, agent asks) are hidden.
- A source that is slow to load (large history) must not block typing: its group appears when ready.
