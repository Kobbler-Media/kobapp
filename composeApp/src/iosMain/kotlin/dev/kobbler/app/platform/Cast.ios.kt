package dev.kobbler.app.platform

// DLNA needs Apple's multicast entitlement, and the Google Cast iOS SDK is not wired up.
private val router by lazy { CastRouter(emptyList()) }

actual fun castManager(): CastRouter = router

actual fun saveCastAppId(appId: String?) {}
