# T3. Done

The harness half (the `Stub` core, golden frames, the seeded fuzz corpus and the bench) merged in
https://github.com/pyrlyn/vesper/pull/65. This closing half, in
https://github.com/pyrlyn/vesper/pull/48, times the reference terminals on the same
1,000,000-byte input.

- `vesper-bench write <file>` is the writer that runs inside a terminal's window. It writes the
  payload once untimed and five times timed, each write ended by `tcdrain`, and stores the median
  in nanoseconds. The baseline launches each installed terminal with it as the window's program,
  waits up to 60 s for the window to close and reads the file as untrusted text.
- `vesper-bench/terminals.sh` installs kitty, Alacritty, foot, cage, Xvfb and Mesa from apt, plus
  the WezTerm and Contour `.deb`s checked against pinned SHA-256s, inside a pinned `ubuntu:26.04`
  container. foot runs on a headless cage rather than weston, because weston's headless backend
  gave foot no seat. The other terminals run on Xvfb.
- The Linux CI job runs it after `check` with `continue-on-error`, so an outage at a mirror or
  release host cannot fail the build. The Windows and Linux bench tables go to the job summary and
  to the log, since `gh run view --log` can read the log without a GitHub sign-in.
- `docs/benchmarks/baseline.md` holds the table from run
  https://github.com/pyrlyn/vesper/actions/runs/45912179625. All five installable terminals were
  measured. Warp has no Linux package here and no launch that waits for its program.

Reused, not rewritten: the payload, `Row`, the table writer, `locate` and `programFile` from the
existing bench, `environment` from `vesper-harness` and Okio for the files.

How it was checked:
- `./gradlew ktlintFormat check linkDebugTestLinuxX64 :vesper-windows:linkDebugTestMingwX64` passes
  on macOS arm64.
- Every `linuxX64` test binary passes in an amd64 Colima container. That includes the new median
  and `readTime` tests.
- The script ran in an amd64 Colima container: WezTerm and Alacritty were measured there. kitty,
  foot and Contour failed only on qemu limits (IUTF8, `tcgetattr`, the CPU ISA level).
- shellcheck passes on the script.
- CI run 45912179625 is green on all three targets, and its log has every row above.
