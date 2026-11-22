# 01 · Main — macOS

Figma frame: [01 · Main — macOS](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=74-2) (`74:2`)

The main window on macOS in the dark theme, built from components. It is the reference for the shared layout that most other frames reuse. Three blocks are shown: `git status -sb` (done), `pnpm test` (128 passed) and `pnpm dev` (running); the prompt holds a half-typed `git commit` with autocomplete open.

## Components

- `TitleBar`: traffic-light buttons on the left, session tabs with shortcuts (`⌘1`…`⌘3`), a new-tab button, the search field that opens the command palette (`⌘K`), buttons for the right panel and settings.
- `Sidebar`: three lists, **Sessions** (local shell, SSH host, database, log tail, each with a status dot), **Workflows** (saved command sequences with shortcuts) and **History** (recent commands with their age), and an **Agent ready** card at the bottom.
- Main header: working directory and session meta (`3 blocks · session 12m`).
- `CommandBlock`: status icon, command, badge (`128 passed`, `running · 00:42`), duration and wall-clock time, then the output. A running block has Stop and **Explain** actions and shows its PID.
- Context chip row: directory, git branch with ahead and dirty counts, runtime versions.
- `Prompt` with `Autocomplete`: an AI suggestion drawn from the git diff mixed with history entries, with key hints (`↑↓` navigate, `⇥` accept, `esc` dismiss).
- `Right panel`: a per-session tool panel, by default **Monitor** with tabs System, Ports and Logs.
- `StatusBar`: connection state, shell version, git branch, encoding, grid size (`120×38`), the `⌘K` hint and the clock.

## States

- Block: success, running (live output, elapsed time ticking, Stop enabled), failed (exit code in the badge, Explain offered).
- Prompt: empty, typing with ghost text, autocomplete open, disabled while a full-screen program owns the pane.
- Sidebar and right panel: shown or collapsed; the main area takes the freed width.
- Session status dot: connected, connecting, disconnected.
- A full-screen program (vim, htop, less) replaces the blocks with a plain cell grid until it exits.

## Errors and offline

- A command that fails is a failed block, not a dialog: red status icon, exit code, output kept.
- The shell process dying ends the session: the last block is closed as failed, the prompt is replaced by a restart action, scrollback stays.
- Offline: the local shell keeps working. The status bar shows the offline state; the AI suggestion row drops out of autocomplete and only history entries remain; the Agent card reads unavailable.
- Monitor data that cannot be read (permissions, unsupported platform) shows a dash in place of the value, never zero.
