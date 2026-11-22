# 13 · Settings — Keybindings

Figma frame: [13 · Settings — Keybindings](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=80-3854) (`80:3854`)

The Keybindings section: every action with its shortcut, rebindable.

## Components

- Search field and filter badges.
- Rows grouped into General, Terminal and Agent: action name and shortcut.
- A shortcut cell that records a new one when clicked (`Press keys…`).
- Reset to default, per row and for all.

## States

- Idle.
- Recording: the cell waits for keys; `esc` cancels.
- Conflict: the row is flagged and names the other action (split pane right against the workflow's `⌘D`).
- Changed from default: the row shows a reset action.

## Errors and offline

- A conflicting shortcut is not saved until the user confirms taking it from the other action.
- A combination the system reserves is refused with that reason.
- A keybindings file that cannot be parsed loads defaults and names the bad entry.
- Nothing here needs the network.
