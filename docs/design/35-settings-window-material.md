# 35 · Settings — Window material

Figma frame: [35 · Settings — Window material](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=109-9719) (`109:9719`)

The Window material section of settings, shown over a wallpaper.

## Components

- Material choice: Solid (no transparency), Liquid Glass (macOS 26), Acrylic (Windows 11), Frosted (Linux, KDE and GNOME). Only the native material of the platform is offered beside Solid.
- Fine-tune sliders: opacity (`58%`, how much of the desktop shows through) and blur (`48 px`, higher reads easier).
- A live preview.

## States

- Solid: the sliders are disabled.
- A material chosen: the sliders apply live to the preview and the window.
- Reset to defaults.

## Errors and offline

- A material the system cannot provide is shown disabled with the reason (OS version, compositor, Reduce Transparency), and the window stays solid.
- If applying a material fails at run time the app falls back to Solid and keeps the saved choice for the next launch.
- Nothing here needs the network.
