package dev.kobbler.kobplayer

import java.io.File

actual fun videoDecoderChoices(): List<VideoDecoderChoice> =
    if (System.getProperty("os.name").startsWith("Linux")) {
        linuxDecoderChoices(renderNodes())
    } else {
        listOf(VideoDecoderChoice(VideoDecoding.Auto, "Auto"), VideoDecoderChoice(VideoDecoding.Software, "Software (CPU)"))
    }

private fun renderNodes(): List<Pair<String, String?>> =
    File("/sys/class/drm").listFiles { f -> f.name.startsWith("renderD") }.orEmpty()
        .sortedBy { it.name }
        .map { node -> "/dev/dri/${node.name}" to runCatching { File(node, "device/vendor").readText().trim() }.getOrNull() }

internal fun linuxDecoderChoices(nodes: List<Pair<String, String?>>): List<VideoDecoderChoice> {
    val vendors = nodes.map { (_, id) -> vendorName(id) }
    val gpus = nodes.mapIndexed { i, (device, _) ->
        val vendor = vendors[i]
        val label = if (vendor != null && vendors.count { it == vendor } == 1) {
            "$vendor GPU"
        } else {
            "${vendor?.let { "$it " } ?: ""}GPU (${device.substringAfterLast('/')})"
        }
        VideoDecoderChoice(VideoDecoding.Gpu(device), label)
    }
    return listOf(VideoDecoderChoice(VideoDecoding.Auto, "Auto")) + gpus +
        VideoDecoderChoice(VideoDecoding.Software, "Software (CPU)")
}

private fun vendorName(pciVendor: String?): String? = when (pciVendor?.lowercase()) {
    "0x8086" -> "Intel"
    "0x10de" -> "NVIDIA"
    "0x1002" -> "AMD"
    else -> null
}
