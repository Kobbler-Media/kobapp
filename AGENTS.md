# Kobapp engineering conventions

KMP/CMP native client for Kobbler (self-hosted media library). Targets Android, Android TV,
iOS, Desktop and Web. A single `composeApp` module holds the app; `kobplayer`, `kobpalette`
and `kobfocus` are standalone support libraries.

Machine-specific notes — your build box, your device list, your deployment steps — belong in
`CLAUDE.local.md` / `AGENTS.local.md`, which are gitignored. Do not add them here.

## Style

- Modern, concise, idiomatic KMP/CMP. No over-engineering.
- **Zero comments by default.** Names and types are the documentation. Comment only
  non-obvious *why*: a constraint, a workaround, an invariant, or a coupling that is not
  visible from the code in front of you.
- No backwards-compatibility shims and no commented-out code. Delete what you replace.
- Keep files small and single-purpose. Check for an existing component or helper before
  adding a new one; consolidate duplicated logic rather than forking it.
- Always use latest stable dependency versions — check the current release, not memory.
  Versions live in `gradle/libs.versions.toml`, and pins carrying a comment are deliberate.

## Testing

Behaviour changes are test-first: write the failing test, watch it fail for the right reason,
fix, watch it pass. Prefer integration-level tests, and cover every entry point into the
logic being changed, not just the one that surfaced the bug.

- `composeApp/src/commonTest` — logic that runs on every target.
- `composeApp/src/desktopTest` — Compose UI tests via `runComposeUiTest`, plus anything that
  needs a real HTTP server (`StubServer`).

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:desktopTest --tests "dev.kobbler.app.ui.reader.*"
```

## Build

```bash
./gradlew :composeApp:hotRunDesktop      # Desktop (hot reload — code edits land without restart)
./gradlew :composeApp:run                # Desktop (cold run; only if hot reload misbehaves)
./gradlew :composeApp:wasmJsBrowserRun   # Web
./gradlew :androidApp:installDebug       # Android
```

`hotRunDesktop` launches a GUI window and will not exit on its own — run it in the background
and do not poll it. Resource changes and data-class signature changes need a restart.

**Desktop packaging:** `nativeDistributions` in `composeApp/build.gradle.kts`; user-facing
install via `./install-desktop.sh`. `:composeApp:createReleaseDistributable` runs ProGuard
(pinned to 7.10 — the plugin's default 7.7.0 can't read Kotlin's JVM 25 bytecode) shrink-only
via `composeApp/proguard-desktop.pro`; a class reached only by reflection needs a keep rule
there.

iOS targets are only declared when the build host is a Mac. Declaring them elsewhere makes
`gradle build` fail on `:compileIosMainKotlinMetadata`.

## mediamp workarounds (Desktop VLC)

- `enableRendering`: the player ties it to `PLAYING`, but VLC's `PLAYING` is transient (it
  becomes `READY`). Override with a custom collector covering all active states.
- `pause()` / `resume()`: guards reject the `READY` state. Bypass via vlcj
  `player.controls()` directly.
- Bitmap: read `player.surface.bitmap` inside the Canvas draw lambda, not the composable body.
- Video: VLC decodes into our own buffers via raw libvlc callbacks (`VlcVideoSink`); YUV→RGB
  and scaling run in a Skia shader (`FramePainter`). GPU decode goes through the bundled
  `kobplayer/native/kobvaapi` plugin (VLC 3.0.23's own VA-API DRM decoder never opens); the
  "Video decoding" setting (`Player.setVideoDecoding`) picks the GPU. `kobvaapi` is adapted
  from VLC's own LGPL-2.1+ VA-API helper — see the file headers under
  `kobplayer/native/kobvaapi/`.
- Audio: use the `AudioLevelController` feature to unmute and set volume.
