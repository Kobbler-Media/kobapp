package dev.kobbler.kobplayer

import java.io.File

internal fun resourceUrl(name: String): String =
    File(object {}.javaClass.getResource("/$name")!!.toURI()).toPath().toUri().toString()
