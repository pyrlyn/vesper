# 25 · Settings — Terminal profiles

Figma frame: [25 · Settings — Terminal profiles](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=89-7081) (`89:7081`)

The Terminal section of settings: shell profiles, scrollback and bell, in tabs.

## Components

- Tabs: Profiles, Scrollback, Bell.
- Profile list with add, duplicate and delete.
- Profile form: command, working directory, environment variables as key-value rows.
- A default-profile marker.

## States

- Profile: selected, default, dirty.
- Environment row: idle, editing, added, removed.
- The last profile cannot be deleted.

## Errors and offline

- A command that does not resolve to an executable is flagged under the field; saving is allowed, since the path may exist later.
- A working directory that does not exist is flagged the same way.
- A duplicate environment key is refused on entry.
- Nothing here needs the network.
