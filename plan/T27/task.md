# T27. Powerline and Nerd Font symbols on macOS

Status: not started

Prompts such as oh-my-posh and powerlevel10k print Powerline separators and Nerd Font icons from the Private Use Area. SF Mono has none of them and the system fallback finds none, so they show as `?` boxes unless the user installed a Nerd Font. Done when they draw with no font installed, as in Ghostty and Warp.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
