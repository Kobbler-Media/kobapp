package dev.kobbler.app.platform

// The browser casts the page itself, and a page has no UDP to discover anything else with.
private val router by lazy { CastRouter(emptyList()) }

actual fun castManager(): CastRouter = router

actual fun saveCastAppId(appId: String?) {}
