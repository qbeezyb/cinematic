package me.cinematic.media;

import java.nio.file.Path;
import java.util.ArrayList;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/** Headless FFmpeg only: safe on dedicated servers, with no AWT, Java2D, OpenGL, or audio device. */
public final class NativeMediaProbe implements MediaLibrary.Probe {
    @Override public MediaLibrary.ProbeResult read(Path path) throws Exception {
        try (var grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.setOption("rw_timeout", "10000000");
            grabber.setOption("protocol_whitelist", "file");
            grabber.setOption("format_whitelist", "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,mp3,wav,ogg,flac");
            grabber.setOption("probesize", "8388608");
            grabber.setOption("analyzeduration", "5000000");
            grabber.start();
            long durationMs = grabber.getLengthInTime() / 1000;
            if (durationMs <= 0 || (grabber.getImageWidth() <= 0 && grabber.getAudioChannels() <= 0)) {
                throw new IllegalArgumentException("No playable audio/video streams or duration; re-encode the file");
            }
            var tracks = new ArrayList<AudioTrack>();
            var context = grabber.getFormatContext();
            for (int i = 0; i < context.nb_streams(); i++) {
                var stream = context.streams(i);
                var params = stream.codecpar();
                if (params.codec_type() != AVMEDIA_TYPE_AUDIO) { continue; }
                if (tracks.size() == 64) { throw new IllegalArgumentException("More than 64 audio tracks"); }
                tracks.add(new AudioTrack(stream.index(), tag(stream, "language", 32), tag(stream, "title", 128),
                        avcodec_get_name(params.codec_id()).getString(), params.ch_layout().nb_channels(),
                        (stream.disposition() & AV_DISPOSITION_DEFAULT) != 0));
            }
            return new MediaLibrary.ProbeResult(durationMs, Math.max(0, grabber.getImageWidth()),
                    Math.max(0, grabber.getImageHeight()), !tracks.isEmpty(), tracks);
        }
    }
    private static String tag(org.bytedeco.ffmpeg.avformat.AVStream stream, String key, int bound) {
        var entry = av_dict_get(stream.metadata(), key, null, 0);
        if (entry == null || entry.isNull()) { return ""; }
        var value = entry.value();
        // Bound native reads before creating Java strings.
        int length = 0;
        while (length < bound && value.get(length) != 0) { length++; }
        byte[] bytes = new byte[length];
        value.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
