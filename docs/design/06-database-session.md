# 06 · Database — psql staging

Figma frame: [06 · Database — psql staging](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=75-2341) (`75:2341`)

A `psql` session on the staging database. The right panel turns into a schema browser.

## Components

- Blocks with query output rendered as the client printed it.
- Right panel **Schema**: a filter field, the tables of `public`, the columns of the selected table (`invoices`: name and type), saved queries.
- Everything else as in [01-main-macos.md](01-main-macos.md).

## States

- Schema: loading, loaded, filtered, empty filter result.
- Table row: collapsed, selected with its columns.
- Saved query: clicking inserts it into the prompt, it does not run.

## Errors and offline

- A failed query is a failed block with the server's message; the panel is not touched.
- Schema cannot be read (no privilege, connection gone): the panel shows the reason and a retry; the session itself keeps working.
- Connection lost: the block that was running fails, the tab's dot turns red, the prompt offers reconnect.
- Offline for a remote database behaves as connection lost; a local database is unaffected.
