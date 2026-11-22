# 11 · Workflows — run in progress

Figma frame: [11 · Workflows — run in progress](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=79-3446) (`79:3446`)

Run #142 of a workflow, while it executes.

## Components

- Summary line: `Running · step 3 of 6`, branch, image, target, ETA.
- Step list with a status per step and the live output of the current one.
- Right panel: run details (start, trigger, runner, commit, approvals), runner load, and what happens when it finishes (desktop notification, post to a channel, open a URL).
- A toast that reports progress.
- Stop action.

## States

- Queued, running, waiting for approval, succeeded, failed, stopped.
- Step: pending, running, done with its duration, failed, skipped.

## Errors and offline

- A failed step stops the run: the step shows its exit code and output, later steps are skipped, Retry from this step is offered.
- Stop sends SIGTERM to the current step and marks the run stopped; it never leaves a step running unseen.
- The connection to a remote runner drops: the run is shown as unknown, not failed, with a reconnect countdown; local output received so far stays.
- A finish action that cannot be delivered (channel post while offline) is reported in the toast and does not change the run's result.
