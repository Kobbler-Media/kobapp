package dev.kobbler.app.data

/** The kobbler instance serving this build, and how to send the browser to sign in to it. */
class HostedServer(val apiUrl: String, val signIn: (apiUrl: String) -> Unit)
