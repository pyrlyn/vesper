# 27 · SSH — verify host & passphrase

Figma frame: [27 · SSH — verify host & passphrase](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=90-7343) (`90:7343`)

A modal on the first connection to a host.

## Components

- The host key fingerprint (`ED25519`, SHA256) with a copy button.
- "Remember this host in known_hosts".
- The passphrase field for the key.
- An option to save the passphrase in the platform keychain.
- Footer: the jump host the connection goes through.
- Cancel and Connect.

## States

- Unknown host: fingerprint shown, Connect enabled.
- Passphrase needed: the field is shown and focused.
- Connecting.
- Wrong passphrase: the field clears and shows the error; attempts are counted.

## Errors and offline

- A host key that differs from `known_hosts` is a blocking warning: it shows both fingerprints and has no one-click accept.
- `known_hosts` cannot be written: the connection may proceed, and the modal says the host will be asked about again.
- Keychain unavailable: the save option is disabled with the reason.
- The host is unreachable: the modal closes to [18-ssh-connection-lost.md](18-ssh-connection-lost.md).
