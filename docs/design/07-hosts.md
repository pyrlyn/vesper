# 07 · Hosts & connections

Figma frame: [07 · Hosts & connections](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=77-2084) (`77:2084`)

The host manager: saved SSH hosts and the ones read from `~/.ssh/config`.

## Components

- Left: a searchable host list grouped into Production, Staging and Local, with counts.
- Right: the selected host (`prod-eu-1`: OS, region, last connection) with Connect and other actions.
- **Connection** form: name, host, port, user, authentication, jump host.
- **Behaviour** toggles (for example confirm destructive commands, restore on launch).

## States

- No host selected: the right side shows a hint.
- Host selected, form clean.
- Form dirty: Save and Discard appear.
- Search with no match: empty list with the query echoed.
- A host from `~/.ssh/config` is read-only where the file defines the value; that is said on the field.

## Errors and offline

- Invalid field (empty host, port out of range): the error sits under the field and Save stays disabled.
- `~/.ssh/config` cannot be read or parsed: saved hosts still list; one line says the file was skipped and why.
- Connect fails: the new tab opens on [18-ssh-connection-lost.md](18-ssh-connection-lost.md) with the reason.
- Offline: the list and editing work; Connect stays enabled, since the host may be on the local network.
