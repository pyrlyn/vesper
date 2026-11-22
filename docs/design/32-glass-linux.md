# 32 · Glass — Linux frosted blur

Figma frame: [32 · Glass — Linux frosted blur](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=108-9160) (`108:9160`)

The main window of [03-main-linux.md](03-main-linux.md) with a frosted blur behind it (KDE and GNOME).

## Components

- The components of [03-main-linux.md](03-main-linux.md).
- A blurred translucent window background.

## States

- The states of [03-main-linux.md](03-main-linux.md).

## Errors and offline

- Blur depends on the compositor: where it is unavailable the window is translucent without blur only if contrast still holds, otherwise solid.
- On X11 without a compositor the window is solid.
