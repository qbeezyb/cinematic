package me.cinematic.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ShortBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaDecoderTest {
    @TempDir Path temporary;

    @Test void actualMkvWithAnOffsetStartDecodesAndDisplaysAPausedPreview() throws Exception {
        Path file = temporary.resolve("offset.mkv");
        try (var recorder = new FFmpegFrameRecorder(file.toFile(), 320, 180);
                var frame = new Frame(320, 180, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("matroska");
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(25);
            recorder.start();
            for (int i = 0; i < 50; i++) {
                recorder.setTimestamp(1_000_000 + i * 40_000L);
                ByteBuffer pixels = (ByteBuffer) frame.image[0];
                for (int p = 0; p < pixels.capacity(); p++) { pixels.put(p, (byte) (i + 20)); }
                recorder.record(frame);
            }
            recorder.stop();
        }
        for (boolean playing : new boolean[] {false, true}) {
            try (var decoder = new MediaDecoder(() -> {
                var grabber = MediaDecoder.configuredGrabber(file.toString(), "", 320, 180);
                // The sandbox forbids a loopback HTTP listener; only the transport differs.
                grabber.setOption("protocol_whitelist", "file,http,https,tcp,tls,crypto");
                return grabber;
            }, 320, () -> 0, playing)) {
                awaitReady(decoder);
                var frame = decoder.frame(0);
                assertNotNull(frame); assertTrue(frame.rgba().length == 320 * 180 * 4);
                decoder.seek(1000, false);
                awaitReady(decoder);
                assertNotNull(decoder.frame(1000), "Seeking an offset MKV must use the same timeline origin");
            }
        }
    }
    /** MKV tracks can start after zero, with audio preceding the first video frame. */
    @Test void pausedPreviewDoesNotBufferForeverWhenAudioLeadsVideo() throws Exception {
        try (var decoder = new MediaDecoder(() -> new OffsetGrabber(), 2, () -> 0, false)) {
            awaitReady(decoder);
            assertNotNull(decoder.frame(0), "Paused playback must display its first image");
        }
    }

    @Test void seekingBackFromAFutureFrameDoesNotLeaveTheDecoderThrottled() throws Exception {
        AtomicLong timeline = new AtomicLong(5000);
        try (var decoder = new MediaDecoder(() -> new OffsetGrabber(), 2, timeline::get, false)) {
            awaitReady(decoder);
            timeline.set(0);
            decoder.seek(0, false);
            awaitReady(decoder);
            assertNotNull(decoder.frame(0));
        }
    }

    private static void awaitReady(MediaDecoder decoder) throws Exception {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (!decoder.ready() && decoder.error().isEmpty() && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertTrue(decoder.error().isEmpty(), decoder.error());
        assertTrue(decoder.ready(), "Playback remained in Buffering instead of decoding the first video frame");
    }

    private static final class OffsetGrabber extends FFmpegFrameGrabber {
        private int index;
        private long target;
        OffsetGrabber() { super("unused.mkv"); }
        @Override public void start() {}
        @Override public void setTimestamp(long timestamp) { target = timestamp; index = 0; }
        @Override public Frame grab() {
            Frame frame;
            if (index++ == 0) {
                frame = new Frame();
                frame.samples = new java.nio.Buffer[] {ShortBuffer.allocate(960)};
                frame.timestamp = target + 200_000;
            } else {
                frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 4);
                frame.timestamp = target + 240_000 + (index - 2) * 40_000L;
            }
            return frame;
        }
        @Override public void close() {}
    }
}
