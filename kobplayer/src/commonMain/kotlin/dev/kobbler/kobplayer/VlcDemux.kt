package dev.kobbler.kobplayer

// libVLC 4 seeks a Matroska stream only to cue points it has verified, and it verifies
// none over a network: every seek decoded the title from its first frame. libavformat
// seeks MPEG audio by its frames, where libVLC's own demuxer lands on an estimate.
internal fun vlcDemuxOption(source: MediaSource.Url): String =
    if (source.mpegAudio) "demux=avformat,any" else "demux=mkv_trusted,any"
