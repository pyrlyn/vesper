# 29 · About & updates

Figma frame: [29 · About & updates](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=90-8438) (`90:8438`)

The About modal.

## Components

- Version, build date, architecture and channel.
- An update banner.
- The changelog of the next version.
- The update channel picker and the automatic update toggle.
- Check now, and Install and restart.

## States

- Up to date.
- Checking.
- Update available.
- Downloading, with progress.
- Ready to install.

## Errors and offline

- Offline or the check fails: the banner shows the last successful check and the reason; no error dialog.
- A download that fails or does not pass verification is discarded and reported; the current version keeps running.
- Install and restart with processes running goes through [28-quit-running-processes.md](28-quit-running-processes.md) first.
