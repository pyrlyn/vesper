# 22 · Share block

Figma frame: [22 · Share block](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=88-6556) (`88:6556`)

A modal that shares one block as a link.

## Components

- Who can view: the team with sign-in, or anyone with the link (read-only).
- Toggles: include the environment, include the full output (`2,140 lines`).
- The link with a copy button, its expiry, and who has viewed it.
- Create link, Revoke and Done.

## States

- Before creation: options only.
- Creating.
- Created: the link and viewers appear.
- Revoked.
- Redaction preview: values matching the redaction patterns are shown replaced before anything leaves the machine.

## Errors and offline

- Offline or upload fails: the modal stays open with the reason and Retry; nothing is half-shared.
- Not signed in: the team option is disabled and points to [26-settings-account-sync.md](26-settings-account-sync.md).
- Output over the size limit: the modal says how much will be cut before the link is created.
- Revoke fails: the link is still shown as active, never as revoked.
