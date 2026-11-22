# 17 · New session — empty state

Figma frame: [17 · New session — empty state](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=82-4720) (`82:4720`)

A new session before the first command.

## Components

- Headline "Ready when you are" with the directory, shell, runtime and branch.
- Quick actions: run a workflow, connect to a host, ask the agent, split pane.
- Scripts detected in `package.json`.
- Recent directories.
- The prompt, focused.

## States

- Shown only until the first command runs; then the blocks take the area.
- Sections with nothing to show (no scripts, no recent directories) are left out, not shown empty.

## Errors and offline

- The shell fails to start: the headline is replaced by the reason (binary not found, exit code) with Retry and a link to [25-settings-terminal-profiles.md](25-settings-terminal-profiles.md).
- The working directory no longer exists: the session starts in the home directory and says so.
- Offline: "ask the agent" is disabled with a tooltip; everything else works.
