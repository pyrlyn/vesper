---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T27. Powerline and Nerd Font symbols on macOS

Prompts such as oh-my-posh and powerlevel10k print Powerline separators and Nerd Font icons from the Private Use Area. SF Mono has none of them and the system fallback finds none, so they show as `?` boxes unless the user installed a Nerd Font. Done when they draw with no font installed, as in Ghostty and Warp: the macOS renderer (T15) falls back to a bundled Symbols Nerd Font Mono (MIT), recorded in `toolchain.md`.

Check: a snapshot of a Powerline prompt with no Nerd Font installed.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
