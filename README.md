# Kobapp

Kobapp is the native client for [Kobbler](https://github.com/Kobbler-Media/kobbler), a self-hosted
media library server. It is a Kotlin Multiplatform / Compose Multiplatform app: one Kotlin
codebase, native UI on every target.

**Kobapp is a client only.** It does nothing on its own — you need a running Kobbler server
to point it at.

## Install

Android / Android TV: download an APK from [Releases](https://github.com/Kobbler-Media/kobapp/releases)
and sideload it. `-universal.apk` runs on every device; the per-ABI APKs are smaller
(`arm64-v8a` for almost any phone or recent TV box). For updates without re-sideloading, install
the [kobup](https://github.com/Kobbler-Media/kobup) updater and point it at a kobup server.

Releases are signed with this certificate; `apksigner verify --print-certs <apk>` should show the
same SHA-256 digest, and Android refuses an update signed by any other:

```
f9eb2181a9f70d167a894cfc92a687da17f8f9a86f46308cfb2af991d75c0351
```

Linux desktop (x86_64): download `-linux-x86_64.tar.gz` from the same release, extract it and run
`./install-desktop.sh`. It installs under `~/.local` (set `PREFIX` to change that), adds a menu
entry, and trains a startup cache on first launch. The bundled libvlc is built on Arch, so video
playback is only expected to work on Arch and its derivatives.

The web player needs nothing installed: your Kobbler server serves it at `/player/`.

## Platforms

| Target      | Status | Notes |
|-------------|--------|-------|
| Android     | yes    | `minSdk` 24, `targetSdk` 36 |
| Android TV  | yes    | D-pad focus navigation, TV-specific layouts |
| Desktop     | Linux  | JVM + Compose Desktop. Video plays through the libvlc [kobvlc](https://github.com/Kobbler-Media/kobvlc) builds, bundled with the app — built on Arch, so it runs on Arch and its derivatives — with optional VA-API GPU decode, MPRIS and screensaver inhibit. macOS and Windows: the app builds, but no libvlc is bundled for them and vlcj 5 needs VLC 4, not the VLC 3 that VideoLAN's installers ship, so expect no video playback |
| iOS         | builds, no playback | `iosApp/iosApp.xcodeproj` builds and runs on Apple Silicon simulators (CI smoke-tests it). No libVLC is bundled for iOS yet, so nothing plays. Intel Macs can only build for devices: Compose Multiplatform publishes no x86_64 simulator libraries |
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
- **Xcode** (and therefore macOS) to build the iOS target: open `iosApp/iosApp.xcodeproj`, or
  run `iosApp/sim-smoke.sh`. Signing for a device needs `TEAM_ID = <your team>` in a gitignored
  `iosApp/Configuration/Local.xcconfig`. The iOS targets are *only declared when the build host
  is a Mac*, so nothing iOS-related breaks the build elsewhere.
- **No system VLC** for Desktop. The build downloads kobvlc's Linux libvlc
  (`libvlc-linux` in `gradle/libs.versions.toml`) from its GitHub releases and bundles it; the
  distribution's VLC 3 is never used.

No Android SDK or Xcode is needed to build and test the Desktop target, which is where most
of the test suite runs.

## Run from source

```bash
./gradlew :composeApp:run                # Desktop
./gradlew :composeApp:hotRunDesktop      # Desktop with Compose hot reload
./gradlew :composeApp:wasmJsBrowserRun   # Web
./gradlew :androidApp:installDebug       # Android (USB-connected device or emulator)
```

## Using a locally built kobvlc

To try a change to [kobvlc](https://github.com/Kobbler-Media/kobvlc) before it is released,
point the build at its `work/` output (absolute paths):

```bash
./gradlew :composeApp:run -Pkobvlc.linux=<kobvlc>/work/linux/pkg   # Desktop: linux/build.sh output
./gradlew :androidApp:installDebug -Pkobvlc.maven=<kobvlc>/work/maven   # Android: android/build.sh output
```

For Android, also set `libvlc-android` in `gradle/libs.versions.toml` to the `VERSION` in
kobvlc's `versions.env` if you bumped it.

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
| `kobplayer`      | multiplatform video/audio player abstraction (libVLC 4 on Desktop and Android through one shared `VlcPlayer`, HTML video on Web) |
| `kobpalette`     | colour extraction for artwork-derived theming |
| `kobfocus`       | declarative D-pad focus graph used by the TV layouts |
| `baselineprofile`| generates the Android baseline profile |

## Baseline profile

The release ships a baseline and startup profile from
`androidApp/src/main/generated/baselineProfiles/`, so ART compiles the hot paths at install
time instead of leaving them on the JIT. It is captured on real devices, not on every build —
a TV run takes about half an hour — so regenerate it after a batch of UI work:

```bash
./baseline-profile.sh                  # every connected device, one after another
./baseline-profile.sh <serial>...      # just these
```

Each device needs the debug build (`dev.kobbler.app.dbg`) installed and paired, and a phone
needs its screen awake. If the app opens on a PIN-locked profile, pass `KOB_PIN=####`. The
script fails rather than merge a run whose browse journey fell back to cold start only.
Commit the two changed files.

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

The Linux packages carry kobvlc's libvlc in the app's resources rather than depending on a
system VLC. That libvlc is built against Arch's libraries, so the packages target Arch and its
derivatives.

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

Release builds are split per ABI (plus a universal APK); all of them go up as one release and
kobup hands each device the split for its CPU.

Add a `github` object to `.kobup.json` to attach the same signed APKs to a GitHub release too,
tagged at the commit they were built from (needs the `gh` CLI, and HEAD pushed):

```json
"github": { "repo": "you/kobapp", "user": "<gh account>", "tagPrefix": "v" }
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Engineering conventions live in
[CLAUDE.md](CLAUDE.md).

## Licence

AGPL-3.0. See [LICENSE](LICENSE).
