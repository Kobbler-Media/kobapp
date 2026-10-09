# Kobapp

Kobapp is the native client for [Kobbler](https://github.com/Kobbler-Media/kobbler), a self-hosted
media library server. It is a Kotlin Multiplatform / Compose Multiplatform app: one Kotlin
codebase, native UI on every target.

**Kobapp is a client only.** It does nothing on its own — you need a running Kobbler server
to point it at.

## Platforms

| Target      | Status | Notes |
|-------------|--------|-------|
| Android     | yes    | `minSdk` 24, `targetSdk` 36 |
| Android TV  | yes    | D-pad focus navigation, TV-specific layouts |
| Desktop     | yes    | Linux / macOS / Windows via JVM + Compose Desktop; video playback needs libvlc, with optional GPU decode on Linux; native window memory, MPRIS and screensaver-inhibit integration on Linux |
| iOS         | sources only | Swift sources under `iosApp/` are WIP and not buildable from a clean clone — no Xcode project file is committed yet |
| Web (WASM)  | yes    | `wasmJs` browser target |

## What it does

Browse and play the library your Kobbler server exposes: movies, TV, live TV and IPTV with an
EPG and recordings, books with a built-in EPUB reader (text-to-speech read-aloud and
audiobook "listen along" when the server offers them), a downloads queue, calendar, search,
and per-household profiles with optional profile PINs.

## Connecting to a server

Kobapp does not ask you to type a server URL. It pairs:

1. Launch Kobapp. It displays a short PIN.
2. Open your Kobbler server's web UI and enter that PIN.
3. The server hands back its address and a device token through a small pairing relay, and
   Kobapp stores it as a profile.

Multiple profiles can be added, each pointing at its own server.

The pairing relay is a public stateless service (`pin.biboop.com`) that only carries the
address and a 90-day admin-scoped token between your server and your device, for the
lifetime of the PIN (10 minutes, unattended). If it's down, pairing a new device is down
with it — there's no fallback login. The wire protocol is fully specified in kobbler's
`docs/pin-login.md`, so running your own relay is a real option: build with
`-PpinServiceUrl=https://your-relay` (or set `pinServiceUrl` in `~/.gradle/gradle.properties`)
and set `KOBBLER_PIN_SERVICE_URL` on the server to match.

## Build prerequisites

- **JDK 25 or newer.** CI builds on Temurin 25 — the Desktop target's `UseCompactObjectHeaders`
  flag needs it. The Gradle wrapper pins Gradle 9.5.1 — use `./gradlew`, not a system Gradle.
- **Android SDK** for the Android and Android TV targets. Point at it with the `ANDROID_HOME`
  environment variable, or create a `local.properties` in the repo root containing
  `sdk.dir=/path/to/Android/sdk`. `local.properties` is gitignored — it is machine-specific.
- **Xcode** (and therefore macOS) to build the iOS target, which also needs its own Xcode
  project generated first — see the Platforms table above. The iOS targets are *only declared
  when the build host is a Mac*, so nothing iOS-related breaks the build elsewhere.
- **libvlc** on the system for Desktop video playback. Arch: `sudo pacman -S vlc`.
  Debian/Ubuntu: `sudo apt install vlc`.

No Android SDK or Xcode is needed to build and test the Desktop target, which is where most
of the test suite runs.

## Run from source

```bash
./gradlew :composeApp:run                # Desktop
./gradlew :composeApp:hotRunDesktop      # Desktop with Compose hot reload
./gradlew :composeApp:wasmJsBrowserRun   # Web
./gradlew :androidApp:installDebug       # Android (USB-connected device or emulator)
```

## Test

```bash
./gradlew :composeApp:allTests           # everything the host can run
./gradlew :composeApp:desktopTest        # JVM + Compose UI tests
```

## Modules

| Module           | What it is |
|------------------|------------|
| `composeApp`     | the app: data layer, all screens, platform `expect`/`actual` shims |
| `androidApp`     | Android/Android TV entry point, Cast integration, packaging |
| `iosApp`         | Xcode project wrapping the shared UI |
| `kobplayer`      | multiplatform video/audio player abstraction (libvlc on Desktop, Media3 on Android, MobileVLCKit on iOS) |
| `kobpalette`     | colour extraction for artwork-derived theming |
| `kobfocus`       | declarative D-pad focus graph used by the TV layouts |
| `baselineprofile`| generates the Android baseline profile |

## Desktop install (Linux)

A standalone install with a bundled JRE, a `Kobbler` entry in your application menu, and a
`kobapp` launcher on `$PATH`:

```bash
./install-desktop.sh
```

Installs to `~/.local/share/kobapp/`, symlinks `~/.local/bin/kobapp`, and drops
`~/.local/share/applications/kobapp.desktop`. Re-run it to update. Override the install root
with `PREFIX=/opt/kobapp ./install-desktop.sh`.

## Building installers for distribution

`compose.desktop.nativeDistributions` is wired up. Each command builds an installer for the
host OS only (a jpackage limitation):

```bash
./gradlew :composeApp:packageDeb        # .deb     (Linux host)
./gradlew :composeApp:packageRpm        # .rpm     (Linux host)
./gradlew :composeApp:packageDmg        # .dmg     (macOS host)
./gradlew :composeApp:packagePkg        # .pkg     (macOS host)
./gradlew :composeApp:packageMsi        # .msi     (Windows host)
./gradlew :composeApp:packageExe        # .exe     (Windows host)
```

Output lands in `composeApp/build/compose/binaries/main/`. The version comes from
`version.properties` (`versionName`, with any `+sha` suffix stripped).

System dependencies (libvlc) are not declared inside the .deb/.rpm — install VLC alongside
the app on the target machine.

## Release signing

`androidApp/build.gradle.kts` reads the release keystore from Gradle properties, so no
keystore or password lives in the repo. Put your own in `~/.gradle/gradle.properties`:

```properties
kobapp.storeFile=/absolute/path/to/release.jks
kobapp.storePassword=...
kobapp.keyAlias=...
kobapp.keyPassword=...
```

Debug builds need none of this. Without it, release builds come out unsigned and
`publishToKobup` refuses to run.

## OTA releases (optional)

`./gradlew :androidApp:publishToKobup` publishes an Android build to a self-hosted
[kobup](https://github.com/Kobbler-Media/kobup) distribution server. It only exists if a sibling
`../kobup` checkout is present, so it is invisible to everyone else. It needs:

```bash
cp .kobup.json.example .kobup.json   # then edit "server" to point at your own kobup
```

plus a `KOBUP_TOKEN` in `.env` (see `.env.template`) or in the environment. `.kobup.json` and
`.env` are gitignored.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Engineering conventions live in
[CLAUDE.md](CLAUDE.md).

## Licence

AGPL-3.0. See [LICENSE](LICENSE).
