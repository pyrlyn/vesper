# 26 · Settings — Account & sync

Figma frame: [26 · Settings — Account & sync](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=89-7439) (`89:7439`)

The Account section of settings.

## Components

- The signed-in profile with Sign out.
- What syncs across devices, one toggle per kind (settings, keybindings, hosts, workflows, history).
- Device list: OS, app version, last sync.
- Sync now.

## States

- Signed out: a sign-in action and nothing else.
- Signed in, in sync.
- Syncing.
- Sync paused by the user.
- This device is marked in the list.

## Errors and offline

- Offline: the section shows the last sync time and "waiting for network"; local changes queue and nothing is lost.
- A conflict (the same item changed on two devices) keeps both and asks which to use; it never overwrites silently.
- Sign-in fails or the session expired: the reason is shown and local data stays as is.
- Secrets and API keys never sync.
