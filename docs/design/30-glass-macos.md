# 30 · Glass — macOS Liquid Glass

Figma frame: [30 · Glass — macOS Liquid Glass](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=108-8212) (`108:8212`)

The main window of [01-main-macos.md](01-main-macos.md) as a translucent window over a wallpaper, using the macOS 26 Liquid Glass material for the window, sidebar and panels.

## Components

- The components of [01-main-macos.md](01-main-macos.md).
- Material layers: window background, sidebar, right panel and title bar each sit on glass; block cards keep an opaque enough fill for text contrast.

## States

- The states of [01-main-macos.md](01-main-macos.md).
- Window active and inactive: the inactive window loses vibrancy as the system does.

## Errors and offline

- The rules of [01-main-macos.md](01-main-macos.md).
- On a system without the material (older macOS), or with Reduce Transparency on, the window is solid, as if Solid were chosen in [35-settings-window-material.md](35-settings-window-material.md).
- Text contrast must hold over any wallpaper; raise the fill opacity before lowering text contrast.
