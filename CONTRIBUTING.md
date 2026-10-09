# Contributing to Kobapp

PRs are welcome — bug fixes, platform fixes, and UI polish especially.

## Getting set up

You need a JDK (25 or newer) and, for the Android targets, an Android SDK. See
[README.md](README.md#build-prerequisites) for the full list. Use the Gradle wrapper
(`./gradlew`), never a system Gradle.

The Desktop target needs no SDK beyond a JDK, and it is where the bulk of the test suite
runs — you can make and verify most changes without ever touching Android or Xcode.

## Build

```bash
./gradlew :composeApp:run                # Desktop, the fastest way to see a change
./gradlew :composeApp:hotRunDesktop      # same, with Compose hot reload
./gradlew :androidApp:assembleDebug      # Android debug APK
./gradlew :composeApp:wasmJsBrowserRun   # Web
```

## Test

```bash
./gradlew :composeApp:allTests           # everything the host can run
./gradlew :composeApp:desktopTest        # JVM + Compose UI tests
./gradlew :composeApp:desktopTest --tests "dev.kobbler.app.ui.reader.*"   # one package
```

Tests live in two source sets:

- `composeApp/src/commonTest` — pure logic that runs on every target.
- `composeApp/src/desktopTest` — Compose UI tests (`runComposeUiTest`) and anything needing a
  real HTTP server. `StubServer` in `ui/player/StubServer.kt` answers a fixed map of routes so
  real `KobblerApi` code can be driven without mocks.

Prefer the widest source set the test can live in.

## Bug fixes are test-first

A behaviour change — a bug fix, a logic change — starts with a failing test.

1. Write a test that reproduces the bug. Run it. Watch it fail, and check it fails *for the
   reason you expect*.
2. Fix it.
3. Run the test again and watch it pass.

Prefer integration-level tests over unit tests: drive the real composable or the real API
client rather than asserting on an intermediate. If the same bug is reachable from more than
one entry point, cover all of them — fixing one call site and missing the others is the
common failure mode here.

CI runs `composeApp:allTests` and `composeApp:assembleDebug` on every pull request.

## House style

- **Zero comments by default.** Names and types are the documentation. Comment only
  non-obvious *why* — a constraint, a workaround, an invariant, a coupling the reader cannot
  see from here. Do not write docstrings that restate the signature.
- **No compatibility shims or commented-out code.** Delete what you replace; git remembers.
- **Keep files small and single-purpose.** Before adding a component or helper, check whether
  one already exists. Duplicated logic with small variations is a bug factory — consolidate
  into one parameterised implementation.
- **Latest stable dependency versions.** When bumping one, read its changelog and update the
  usage rather than only the number. Versions are centralised in
  `gradle/libs.versions.toml`; several pins there carry a comment explaining why they are
  held — respect those.
- Match the surrounding code. This project has strong existing patterns (state hoisting,
  `expect`/`actual` for platform shims, the `kobfocus` focus graph for TV navigation); follow
  them rather than introducing a parallel approach.

## Pull requests

- One concern per PR. A fix plus an unrelated refactor is two PRs.
- Say what was broken and how you proved it is fixed.
- Make sure `./gradlew :composeApp:allTests` passes before opening.
- Do not commit machine-local files: `local.properties`, `.env`, `.kobup.json`, keystores.
  They are gitignored; keep it that way.

## Licence

Kobapp is licensed under the **AGPL-3.0**. By contributing, you agree that your contributions
are licensed under the same terms. See [LICENSE](LICENSE).
