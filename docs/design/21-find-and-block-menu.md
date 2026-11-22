# 21 · Find in output & block menu

Figma frame: [21 · Find in output & block menu](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=88-5922) (`88:5922`)

Three things on one frame: the find bar, a block's context menu and a title bar tooltip.

## Components

- **Find bar** over the output: query (`invoice`), case and regex toggles, `1 of 12`, previous and next, close.
- Matches highlighted in the blocks; the current one in the accent colour.
- Block **context menu**: copy command, copy output, rerun, share block, bookmark, explain with the agent, delete block, each with its shortcut.
- Tooltip on a title bar button: name and shortcut.

## States

- Find: empty query, matches found, no matches (`0 of 0`, field tinted), stepping wraps around.
- Menu item: enabled, disabled (for example rerun while the block is running), destructive (delete).
- Tooltip: appears after a hover delay.

## Errors and offline

- An invalid regex tints the field and shows the parser's message; the previous matches clear.
- Search over very long output must not freeze typing: matches appear as they are found.
- Offline: share block and explain with the agent are disabled with a tooltip; the rest works.
