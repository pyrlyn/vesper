# 14 · Monitor — Ports

Figma frame: [14 · Monitor — Ports](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=81-3854) (`81:3854`)

The **Ports** tab of the Monitor panel.

## Components

- Listening ports: port, protocol, owning process, and where it came from (this session, Docker, a Homebrew service, the debugger).
- SSH forwards with the host they go through.
- An inline confirmation to stop a port's process that names the block it will end.

## States

- List: loading, populated, empty.
- Row: idle, hover with Stop, confirming.
- A port opened by a block in this window links to that block.

## Errors and offline

- Ports owned by other users need privileges: those rows show the port without a process name instead of being hidden.
- Stop fails (process gone, not permitted): the row reports it; the list refreshes.
- On a platform where the listing is unsupported the tab says so once, with no empty table.
- Offline changes nothing: the data is local. Forwards of a dropped SSH session are marked inactive.
