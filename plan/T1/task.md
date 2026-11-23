---
status: in progress
priority: P0
complexity: 1
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T1. Settle the open decisions

Answer the open questions of `research.md` §8; T1 blocks all other work. The earlier answers still stand, with one stack change: the VT core is our own Kotlin/Native code, not Rust and not libghostty-vt. Record the decisions in `AGENTS.md`.

Acceptance:
- [x] Every §8 question is answered and written down in `AGENTS.md` and `research.md` §8.
- [x] The core language is recorded as Kotlin/Native.

## Execution plan

1. Compare the seven decisions in `research.md` §8 with `AGENTS.md` → Decisions.
2. Fix whatever disagrees or went stale.
3. Close with `done.md`. No code changes, so the check is the comparison itself.
