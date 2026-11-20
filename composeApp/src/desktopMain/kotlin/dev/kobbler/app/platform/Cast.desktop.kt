package dev.kobbler.app.platform

private val router by lazy { CastRouter(emptyList()) }

actual fun castManager(): CastRouter = router

actual fun saveCastAppId(appId: String?) {}
