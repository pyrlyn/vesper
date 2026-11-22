# 23 · History — search

Figma frame: [23 · History — search](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=88-7121) (`88:7121`)

Full command history as a searchable table.

## Components

- Search field and the result count.
- Table grouped by day: command, where it ran, duration or exit code, time.
- Right panel **Insights · 7 days**: metric cards and the top commands with their counts.
- Row actions: run again, copy, insert into the prompt.

## States

- All history, filtered, no match.
- Row: idle, hover, selected.
- Failed commands show their exit code in place of the duration.
- Empty history: one line and no table.

## Errors and offline

- A history file that cannot be read or is partly corrupt loads what it can and says how many entries were skipped.
- Insights need at least a few days of data; with less, the panel says so, with no zeros.
- Commands typed after a leading space are not recorded and never appear here.
- Nothing here needs the network.
