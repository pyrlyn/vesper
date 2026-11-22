# T29. Green CI on main

Status: not started

CI on `main` must stay green: Kotlin/Native `./gradlew check` on every target, the C ABI sanitizer test on Unix, `swift test` for the macOS app, and `dotnet test` / `dotnet build` for the Windows core and WinUI app. Required checks on `main` are named and documented. A failure on one platform must not be hidden by skipping the job.

Acceptance:
- [ ] The required GitHub checks are listed in `PLAN.md` and stay green on `main`.
- [ ] macOS, Linux and Windows jobs all run, with an explicit skip only when `SKIP_CI_TESTS` or `MACOS_CI_DISABLED` is set.
