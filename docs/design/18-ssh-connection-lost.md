# 18 · SSH — connection lost

Figma frame: [18 · SSH — connection lost](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal?node-id=82-5062) (`82:5062`)

A dropped SSH session. This frame is the reference for every lost-connection state in the app.

## Components

- A banner that counts down to the next reconnect attempt.
- The blocks, visible but frozen.
- **Reconnect card**: when the host was last seen, the reason (`read: connection timed out`), the backoff (2 s up to 30 s), Reconnect now and Close.

## States

- Waiting for the next attempt.
- Reconnecting.
- Reconnected: the banner clears and a new prompt appears; scrollback is kept.
- Gave up after the last attempt: the countdown is replaced by a manual Reconnect.

## Errors and offline

- This screen is the error state. The reason shown is the one the transport reported, unedited.
- Offline (no network at all): the countdown pauses instead of burning attempts and resumes when the network returns.
- A command that was running is closed as a failed block marked interrupted; it is never re-run on reconnect.
- A changed host key on reconnect goes to [27-ssh-verify-host.md](27-ssh-verify-host.md), not to a silent retry.
