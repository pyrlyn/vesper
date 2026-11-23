# 09 · AI agent — explain & fix error

Figma frame: [09 · AI agent — explain & fix error](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=77-3030) (`77:3030`)

**Explain** on a failed block. The agent describes the failure and proposes a fix.

## Components

- The failed block, highlighted as the subject.
- Right panel **Explain error**: what happened in plain words, a suggested fix as a diff of the named file (`src/billing/pdf.ts`), other options with their trade-offs, a "Was this helpful?" prompt.
- Apply and Copy actions on the diff.

## States

- Loading: the panel streams the explanation.
- Explained, with a fix.
- Explained, no fix to propose: the diff section is absent.
- Fix applied: the diff is marked applied and re-run is offered.
- Feedback sent.

## Errors and offline

- Agent unavailable (no key, offline, quota): the panel says why and offers Retry; the block is unchanged.
- The file in the diff changed since the explanation: Apply is refused with that reason, never a partial write.
- Output too long for the context: the panel says the output was cut and from where.
