# 12 · Settings — Appearance

Figma frame: [12 · Settings — Appearance](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=80-3436) (`80:3436`)

The settings window on the Appearance section.

## Components

- Left: a searchable section list; the version and update state at the bottom.
- **Theme**: Orbit Dark, Orbit Light, Nord, Solarized.
- **Accent colour**, used for focus, running blocks and the agent.
- **Typography**: font family and size.
- **Live preview** on the right: a sample block and the ANSI palette.

## States

- Every change applies at once to the preview and to open windows; there is no Save button.
- Theme card: selected, hover.
- Section search: filtered list, no match.

## Errors and offline

- A font that is not installed falls back to the bundled mono font and the field says so.
- The settings file cannot be written: the change stays applied for this run and a line says it was not saved.
- The settings file is malformed at start: defaults load, the file is left untouched, the problem is named.
- Nothing here needs the network.
