# Vesper

https://github.com/pyrlyn/vesper

A terminal emulator written in Kotlin: a Kotlin/Native core and a Kotlin/Native host per platform (AppKit on macOS, Win32 on Windows) that calls it directly. The core computes, the platform renders. The design takes the best part of kitty, WezTerm, Alacritty, foot, Contour and Warp at each pipeline stage; see `research.md`.

A closed task has a `done.md`. Closed tasks that describe the removed Swift, C# and Compose apps or the C ABI are history: T902 removed that code, and the host tasks were re-planned for Kotlin/Native. The phased plan, architecture and acceptance criteria are in [`PLAN.md`](../PLAN.md).
