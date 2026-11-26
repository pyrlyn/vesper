# T27. Done

- `vesper-macos/fonts/`: `SymbolsNerdFontMono-Regular.ttf` and its MIT `LICENSE` from nerd-fonts
  v3.5.1, their git blob hashes checked against the tag.
- The build links the font into every macOS binary, the app and the test executable, with
  `-sectcreate __TEXT __nerd_symbols` (as an Info.plist is embedded). No file has to sit beside the
  app, and the link reruns when the font changes.
- `macho.def`: cinterop bindings for `mach-o/getsect.h` and `mach-o/dyld.h`, which the Kotlin/Native
  platform libraries leave out. Headers only, no code of our own.
- `Symbols.kt`:
  - `bundledSymbols` reads the section of the running executable and makes a CoreText font
    descriptor from it in place. It is null when the section is missing, and the system fallback
    then works as before.
  - `withFallback` puts that font first in a face's cascade list.
- `TextStyle`: all four faces get the cascade, so the Metal renderer (`RunShaper`) and the CoreText
  fallback both reach the symbols before the system's fonts. Powerline separators (U+E0B0–E0BF)
  stay sprites; emoji still come from the colour emoji font.
- `toolchain.md`: a Bundled assets table with the font, and a note on the cinterop def.

Commits on branch `t27-macos-symbols`: the claim, the fallback with its tests, the close.

Checked:
- `./gradlew ktlintFormat check linkDebugTestLinuxX64` on macOS arm64: green.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`, `VESPER_TESTS_DIR` set): green.
- The linked app executable carries the `__TEXT,__nerd_symbols` section (`otool -l`).
- Tests, on a machine with no Nerd Font installed:
  - `SymbolsTest` (3):
    - the binary carries the font (`SymbolsNFM`);
    - Powerline and Nerd Font icons, one past the Basic Multilingual Plane, shape from it in the
      regular and bold italic faces;
    - letters, emoji (😀, ⚡, ♥️) and the separators do not.
  - `MetalRendererTest.powerlinePromptSnapshot`: a three-segment prompt (apple, folder and branch
    icons between solid separators). Each icon cell is inked and the separators meet their
    segments. The snapshot is `macos-powerline.png`.
- Not checked (needs a person): a real oh-my-posh or powerlevel10k prompt in the running app.
