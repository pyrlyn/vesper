---
status: todo
priority: P1
complexity: 4
readiness: 0%
agent:
---

# T19.3. Kitty graphics protocol

Part of T19, a pure decoder over the `scull-image` store. It covers:

- APC `G` key parsing;
- chunked transmission (`m=1`);
- formats 24, 32 and 100 (PNG through `scull-png`);
- zlib (`o=z`) through `platform.zlib` with a cap;
- the transmit, put and delete actions;
- client image ids and numbers;
- quiet levels and the reply strings.

kitty is GPL-3.0-only: implement from the protocol specification, never port its code.

Done when golden tests cover each action and reply, the decoder has a fuzz entry run by the tests, and `./gradlew check` passes.
