# 24 · Settings — AI agent

Figma frame: [24 · Settings — AI agent](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=89-6397) (`89:6397`)

The AI agent section of settings.

## Components

- **Models**: provider, model, API key.
- **Behaviour**: the plan always shows before anything runs; ask before running; allow destructive commands.
- **Context & privacy**: what may be sent.
- Right: usage this month and the **redaction patterns** that are never sent (`*_KEY`, `*_TOKEN`, `*_SECRET`, `DATABASE_URL`, `~/.aws/credentials`), editable.

## States

- No key: the agent is off and the rest of the section is dimmed.
- Key saved and verified.
- Key saved, not yet verified.
- Pattern row: default (cannot be deleted), user-added.

## Errors and offline

- A rejected key shows the provider's message under the field and the agent stays off.
- Offline: the key is saved unverified; usage shows the last known value with its time.
- An invalid pattern is refused on entry with the reason.
- The key is stored in the platform keychain; if the keychain is unavailable the section says the key lasts for this run only.
