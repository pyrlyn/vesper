# 20 · Onboarding — first launch

Figma frame: [20 · Onboarding — first launch](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=88-5431) (`88:5431`)

A four-step first-launch flow: Welcome, Import shell setup, Pick a theme, Set up the agent.

## Components

- Step indicator.
- Import step: what was found, read without changing the originals: `~/.zshrc` (aliases, functions, framework), `~/.ssh/config` (hosts, jump hosts), shell history, iTerm2 profiles, plus the default shell. A checkbox per source.
- Theme step: theme cards with a preview.
- Agent step: API key field and what is sent.
- Back, Continue and Skip.

## States

- One step at a time; Back keeps what was entered.
- Import source: found (checked), not found (absent).
- Finished: the main window opens on [17-new-session-empty.md](17-new-session-empty.md).
- Shown once; reachable again from settings.

## Errors and offline

- A source that cannot be read is listed as skipped with the reason; the flow continues.
- An API key that fails validation shows the provider's message under the field; Skip stays available.
- Offline: the key cannot be checked, so it is saved unverified and checked on first use.
- Quitting mid-flow resumes at the same step on the next launch.
