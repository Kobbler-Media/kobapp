@file:JsModule("hls.js")
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.kobbler.kobplayer

import org.w3c.dom.HTMLMediaElement

@JsName("default")
internal external class Hls : JsAny {
    fun loadSource(url: String)
    fun attachMedia(media: HTMLMediaElement)
    fun startLoad()
    fun recoverMediaError()
    fun destroy()
    fun on(event: String, listener: (JsString, HlsErrorData) -> Unit)

    companion object {
        fun isSupported(): Boolean
    }
}

internal external interface HlsErrorData : JsAny {
    val fatal: Boolean
    val type: String
    val details: String
}
