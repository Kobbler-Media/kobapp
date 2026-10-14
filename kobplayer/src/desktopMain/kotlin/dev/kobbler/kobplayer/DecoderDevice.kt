package dev.kobbler.kobplayer

import com.sun.jna.Native
import com.sun.jna.Pointer
import uk.co.caprica.vlcj.player.base.MediaPlayer

internal data class DecoderDevice(val name: String, val path: String?)

// VLC blends subpictures onto the decoder's own picture, and a hardware decoder's picture
// is an opaque GPU surface: the blend fails and the subtitles silently vanish. So the GPU
// only decodes when VLC has no subtitles to draw — the app's own overlay is unaffected.
internal fun decoderDevice(
    source: MediaSource.Url,
    decoding: VideoDecoding,
    vendorOf: (String) -> String? = ::pciVendor,
): DecoderDevice = when {
    decoding == VideoDecoding.Software || source.subtitleOrdinal != null -> DecoderDevice("none", null)
    // VLC turns NVIDIA's VA-API driver away and decodes there through CUDA instead.
    decoding is VideoDecoding.Gpu && vendorOf(decoding.device) == NvidiaPciVendor -> DecoderDevice("nvdec", null)
    decoding is VideoDecoding.Gpu -> DecoderDevice("vaapi_drm", decoding.device)
    else -> DecoderDevice("any", null)
}

internal fun MediaPlayer.setDecoderDevice(device: DecoderDevice) =
    KobLibVlc.libvlc_video_set_decoder_device(mediaPlayerInstance().pointer, device.name, device.path)

// libvlc_video_set_callbacks() pins video callbacks to software decoding; kobvlc's libvlc
// adds this to choose again (patches/vlc in github.com/Kobbler-Media/kobvlc).
@Suppress("FunctionName")
private object KobLibVlc {
    init {
        Native.register("vlc")
    }

    external fun libvlc_video_set_decoder_device(mp: Pointer, name: String?, path: String?)
}
