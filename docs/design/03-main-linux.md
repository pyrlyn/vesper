# 03 · Main — Linux (GNOME)

Figma frame: [03 · Main — Linux (GNOME)](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=74-1456) (`74:1456`)

The main window with GNOME window controls. Layout and content match [01-main-macos.md](01-main-macos.md).

## Components

- `TitleBar`: client-side decorations with the close button on the right, as GNOME draws them.
- Shortcuts use `Ctrl`.
- Every other component is the one described in [01-main-macos.md](01-main-macos.md).

## States

- The states of [01-main-macos.md](01-main-macos.md).
- Window: restored, maximised, tiled.

## Errors and offline

- The rules of [01-main-macos.md](01-main-macos.md).
- On a desktop that draws its own title bar (server-side decorations) the custom `TitleBar` keeps tabs and search but hides its window buttons.
