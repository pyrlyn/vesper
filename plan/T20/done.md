# T20. Done

Done in five slices, one pull request each:

1. `vesper-config` core (#52): `Settings`, `Font`, `Rgb`, `Scheme` and `Theme` with the built-in
   schemes, strict TOML through ktoml, the caps, errors that name the file, and the default path.
2. Key bindings and the schema (#53): `[[keybind]]` chords over `vesper-input`, aliases, the typing
   guard, duplicates and the cap, and a test that fails when `docs/config.schema.json` is stale.
3. Edits and live reload (#54): `editConfig` validates the whole result, then writes through a temp
   file and an atomic rename (through a symlink, not over it). `LiveConfig` polls the file, keeps the
   last good settings when an edit is bad, and survives the flood test T30 asked for. Locks and
   threads moved from `vesper-pty` into the new `vesper-sync`.
4. The macOS host (#55): the palette from the theme, `KeyBindings` and font zoom in `vesper-host`;
   `vesper-macos` applies `~/.config/vesper/config.toml` live and has a Settings window (⌘,).
5. The Windows host: `vesper-windows` applies `%APPDATA%\vesper\config.toml` live (the watcher posts
   `WM_APP + 1` to the window) and has a Settings dialog, opened with Ctrl+, or from the system
   menu. The default key bindings are now per platform: Command on macOS, Ctrl and Ctrl+Shift on
   Windows and Linux, where the Windows key belongs to the system. The settings fields' keys,
   labels, text and commit rules are shared in `vesper-host` (`SettingField`) by both hosts.

How it was checked:

- `./gradlew check` on macOS arm64, with the mingwX64 test binaries of `vesper-windows`,
  `vesper-config`, `vesper-pty` and `vesper-host` linked.
- The linuxX64 test binaries in Colima.
- Tests: parsing, defaults, every validation error and cap (`SettingsTest`, `LoadTest`), key
  bindings for both platforms (`KeybindTest`), the schema (`SchemaTest`), atomic edits, live reload
  and the flood (`LiveConfigTest`), the shared host mapping (`BindingsTest`, `SettingFieldTest`),
  the macOS window and view (`vesper-macos` `SettingsTest`), and the Windows dialog, font fallback
  and live app (`vesper-windows` `SettingsTest`, which runs on the Windows CI runner).

Left to other tasks:

- The prompt jumps the bindings name beep until T33 keeps prompt marks.
