# 08 · AI agent — natural language

Figma frame: [08 · AI agent — natural language](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=77-2666) (`77:2666`)

A prompt line starting with `#` is a request to the agent (`find what's listening on 5173 and kill it`). The agent answers with a plan the user approves before anything runs.

## Components

- Prompt in agent mode, marked by the leading `#`.
- **Plan block**: the context it used, an explanation, numbered commands with a reason each (`lsof …`, `kill -TERM 48211`, falling back to SIGKILL after 5 s), Run and Cancel.
- Right panel **Agent**: the context sent (working directory, git diff, last blocks with a token estimate, Monitor data; environment variables never), safety toggles (ask before running, allow destructive commands), the model, recent asks.

## States

- Thinking: the plan block streams in.
- Plan ready: waiting for approval.
- Running: steps run one by one as normal command blocks; the plan marks the current step.
- Done, or stopped at a failed step.
- Cancelled: the plan collapses and nothing ran.

## Errors and offline

- No API key, or the key is rejected: the block says so and links to [24-settings-agent.md](24-settings-agent.md). Nothing is sent.
- Offline or the request times out: the block reports the failure with Retry. The typed request stays in the prompt history.
- Rate limit or quota: the message from the provider is shown as is, with the time to retry when known.
- A step that fails stops the plan; later steps do not run, and Explain is offered on the failed block.
- Text matching a redaction pattern is replaced before sending; the panel shows how many values were hidden.
