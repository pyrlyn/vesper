# 10 · Workflows — editor

Figma frame: [10 · Workflows — editor](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=79-2954) (`79:2954`)

Editing the workflow **Deploy to staging**: a named sequence of shell commands with parameters.

## Components

- Main: title and description, **parameters** (branch, environment, image tag, run migrations), six numbered **steps**, each a shell command.
- Right panel **Workflow**: triggers (manual with `⌘D`, on push to `main`, on a schedule), where it runs, secrets (masked), recent runs with their result.
- Save, Run and Delete actions.

## States

- New workflow: empty title, one empty step.
- Clean, dirty (unsaved marker), saving.
- Step: idle, focused, dragged to reorder.
- Parameter: text, choice, toggle.
- Secret: masked; revealed only while its field is focused.

## Errors and offline

- Validation (empty title, empty step, a parameter used in a step but not declared): the error sits on the row and Save is disabled.
- Save fails (disk, permissions): the editor stays dirty and says why.
- A shortcut that is already bound is flagged on the trigger, as in [13-settings-keybindings.md](13-settings-keybindings.md).
- Offline: editing and manual runs of local steps work; push and schedule triggers are shown as paused.
