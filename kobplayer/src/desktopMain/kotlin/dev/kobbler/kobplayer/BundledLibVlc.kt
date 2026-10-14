package dev.kobbler.kobplayer

import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.MacOsNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy
import java.io.File

// The libvlc kobvlc builds (github.com/Kobbler-Media/kobvlc), ahead of any installed VLC:
// distributions ship VLC 3, and vlcj 5 only speaks VLC 4.
private object BundledLibVlc : BaseNativeDiscoveryStrategy(
    arrayOf("libvlc\\.so(?:\\.\\d+)*", "libvlccore\\.so(?:\\.\\d+)*"),
    arrayOf("%s/vlc/plugins"),
) {
    override fun supported() = bundledLibVlcDir() != null

    override fun discoveryDirectories() = listOfNotNull(bundledLibVlcDir()?.path)

    override fun setPluginPath(pluginPath: String) = LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", pluginPath, 1) == 0
}

private fun bundledLibVlcDir(): File? =
    listOfNotNull(
        System.getProperty("kobplayer.libvlc.dir"),
        System.getProperty("compose.application.resources.dir")?.let { "$it/libvlc/lib" },
    )
        .map(::File)
        .firstOrNull { it.isDirectory }

internal val libVlcDiscovery = NativeDiscovery(
    BundledLibVlc,
    LinuxNativeDiscoveryStrategy(),
    MacOsNativeDiscoveryStrategy(),
    WindowsNativeDiscoveryStrategy(),
)
