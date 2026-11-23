# T1. Done

All seven questions in `research.md` §8 were already answered, both there and in `AGENTS.md` →
Decisions. The two files agree:

1. "Wrapp" is Warp.
2. The Windows host is all C#.
3. The VT core is our own Kotlin/Native code, not libghostty-vt.
4. The PTY lives inside the core.
5. The minimum macOS is 26.
6. The licence is GPL-3.0-or-later.
7. Split panes on Windows share one swap chain per window.

`research.md`'s opening line also names the Kotlin/Native core.

One sentence was stale. `AGENTS.md` said T17 would pick the C# interop library, but §8 and §6.1
record the choice of Microsoft.Windows.CsWin32. `windows/Scull.App` and `windows/Scull.Render`
pin it at 0.3.358, and `toolchain.md` lists it. `AGENTS.md` now names it.

How it was checked: the two decision lists were compared line by line, and `git grep` found
CsWin32 in the Windows `.csproj` files and in `toolchain.md`. The change is docs only, so no build
was run.
