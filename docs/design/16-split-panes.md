# 16 · Split panes

Figma frame: [16 · Split panes](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=81-5015) (`81:5015`)

Three panes in one window: the local shell on the left; on the right `journalctl -f` on `prod-eu-1` above a `psql` session.

## Components

- Pane header: status dot and title.
- Dividers that drag to resize.
- A hint about dragging and about the modifier plus arrows to move focus.
- Each pane holds the blocks and prompt of [01-main-macos.md](01-main-macos.md); the title bar, sidebar and status bar are shared.

## States

- Pane: focused (accent border), unfocused (dimmed header).
- Zoomed: one pane fills the area, the others keep running.
- Divider: idle, hover, dragging.
- Closing the last pane closes the tab.

## Errors and offline

- A pane whose session ended keeps its scrollback and shows restart or close; the other panes are unaffected.
- A pane too small for its minimum grid stops shrinking; the divider stops there.
- One pane offline (its SSH host dropped) shows the connection-lost state inside that pane only.
