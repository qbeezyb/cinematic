package me.cinematic.media;

import java.nio.file.Path;
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
            return new MediaLibrary.ProbeResult(durationMs, Math.max(0, grabber.getImageWidth()),
                    Math.max(0, grabber.getImageHeight()), grabber.getAudioChannels() > 0);
        }
    }
}
