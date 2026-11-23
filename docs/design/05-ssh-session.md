# 05 · SSH session — prod-eu-1

Figma frame: [05 · SSH session — prod-eu-1](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=75-1824) (`75:1824`)

A session on a production host. The frame shows how a destructive command is confirmed and what the right panel shows for a remote session.

## Components

- **Production banner** above the blocks: destructive commands ask for confirmation.
- Inline **confirm card** for `sudo systemctl restart orbit-api`: the impact in plain words (about 4 s downtime, 2 SSE connections reconnect), Cancel and Run.
- Right panel **Host**: connection (address, user, port, key, jump host, latency), remote resources (CPU, memory, disk), port forwards, snippets saved for this host.
- Title bar, sidebar, blocks, prompt and status bar as in [01-main-macos.md](01-main-macos.md).

## States

- Connected, with latency in the panel and a green dot on the tab.
- Confirm card: waiting, confirmed (the command runs as a normal block), cancelled (the card collapses to one muted line).
- A host not marked production has no banner and no confirm card.
- Port forward row: active, failed to bind.

## Errors and offline

- Connection drop: switch to [18-ssh-connection-lost.md](18-ssh-connection-lost.md); never close the tab.
- Authentication or host key problem on connect: [27-ssh-verify-host.md](27-ssh-verify-host.md).
- Remote resources that cannot be read show a dash and the panel says why once (for example no `/proc`).
- A port forward that fails keeps its row with the error text and a retry action.
- Offline: the same as a connection drop; nothing is sent while disconnected.
