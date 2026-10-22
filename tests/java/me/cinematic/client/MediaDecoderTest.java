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
                if (playing) {
                    long deadline = System.nanoTime() + 1_000_000_000L;
                    while (!decoder.buffered() && System.nanoTime() < deadline) { Thread.sleep(5); }
                    assertTrue(decoder.buffered(), "Video-only media must not wait for nonexistent PCM");
                }
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

    @Test void audioAheadMustNotThrottleVideoOrRequestCatchupSeeks() throws Exception {
        AtomicLong images = new AtomicLong();
        try (var decoder = new MediaDecoder(() -> new FFmpegFrameGrabber("trace.mkv") {
            private int packet;
            @Override public void start() {}
            @Override public void setTimestamp(long timestamp) { packet = 0; }
            @Override public Frame grab() {
                int index = packet++;
                Frame frame;
                if (index % 2 == 0) {
                    frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 4);
                    frame.timestamp = index / 2 * 33_334L;
                    images.incrementAndGet();
                } else {
                    frame = new Frame();
                    frame.samples = new java.nio.Buffer[] {ShortBuffer.allocate(1600)};
                    frame.timestamp = index / 2 * 33_334L + 500_000;
                }
                return frame;
            }
            @Override public void close() {}
        }, 2, () -> 0, true)) {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (images.get() < 15 && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(images.get() >= 15, "Audio at +500ms blocked video prebuffer: " + images.get() + " frames");
            assertTrue(!decoder.takeResync(), "Audio timestamps must not trigger a whole-movie seek");
        }
    }

    @Test void native1080p30DeliversNinetyPercentOfFramesForThirtySecondsWithoutRecovery() throws Exception {
        Path file = temporary.resolve("1080p30.mkv");
        try (var recorder = new FFmpegFrameRecorder(file.toFile(), 1920, 1080, 1);
                var image = new Frame(1920, 1080, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("matroska"); recorder.setFrameRate(30);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(48_000);
            recorder.start();
            var pixels = (ByteBuffer) image.image[0];
            while (pixels.hasRemaining()) { pixels.put((byte) 80); }
            pixels.clear();
            ShortBuffer samples = ShortBuffer.allocate(2048);
            long audioSamples = 0;
            for (int i = 0; i < 34 * 30; i++) {
                recorder.record(image);
                while (audioSamples < (i + 1L) * 1600) {
                    samples.rewind(); recorder.recordSamples(48_000, 1, samples); audioSamples += 2048;
                }
            }
            recorder.stop();
        }
        AtomicLong timeline = new AtomicLong();
        try (var decoder = localDecoder(file, 1920, 1080, -1, timeline, true)) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!decoder.buffered() && decoder.error().isEmpty() && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(decoder.buffered(), decoder.error());
            int frames = 0; int recoveries = 0; int shortAudio = 0;
            long anchor = System.nanoTime(); long nextRender = 0; long audioCursor = 0;
            ByteBuffer pcm = ByteBuffer.allocate(9600);
            while (timeline.get() < 32_000 && decoder.error().isEmpty()) {
                long target = (System.nanoTime() - anchor) / 1_000_000;
                timeline.set(target);
                if (target >= nextRender) {
                    nextRender = target + 16;
                    var frame = decoder.frame(target);
                    if (frame != null && target >= 2000) { frames++; }
                }
                while (audioCursor < target + 400) {
                    pcm.clear(); int count = decoder.pcm().readAt(pcm, audioCursor, 0);
                    if (target >= 2000 && count < pcm.capacity()) { shortAudio++; }
                    audioCursor += 100;
                }
                if (decoder.takeResync()) { recoveries++; decoder.seek(target, true); }
                assertTrue(decoder.queuedFrames() <= 16);
                assertTrue(decoder.pcm().size() <= 192_000);
                Thread.sleep(1);
            }
            System.out.printf("1080p30: %d/900 frames after warmup (%.1f%%), recoveries=%d, short PCM reads=%d%n",
                    frames, frames / 9.0, recoveries, shortAudio);
            assertTrue(decoder.error().isEmpty(), decoder.error());
            assertTrue(frames >= 810, "Expected at least 90% of source frames: " + frames);
            org.junit.jupiter.api.Assertions.assertEquals(0, recoveries);
            org.junit.jupiter.api.Assertions.assertEquals(0, shortAudio);
        }
    }

    @Test void eofShortTailsRapidSeeksAndDecoderReopeningKeepOnlyCurrentFrames() throws Exception {
        Path fixture = Path.of("tests/fixtures/languages.mkv");
        AtomicLong timeline = new AtomicLong(1800);
        try (var decoder = localDecoder(fixture, 16, 16, 1, timeline, true)) {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!decoder.buffered() && decoder.error().isEmpty() && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(decoder.buffered(), "A short remaining duration must not wait for 500ms");
            timeline.set(2000);
            deadline = System.nanoTime() + 3_000_000_000L;
            while (!decoder.ended() && System.nanoTime() < deadline) { decoder.frame(2000); Thread.sleep(5); }
            assertTrue(decoder.ended());
            for (int target : new int[] {500, 1200, 300, 900}) { timeline.set(target); decoder.seek(target, false); }
            awaitReady(decoder);
            var frame = decoder.frame(900); assertNotNull(frame);
            assertTrue(frame.timestampMs() >= 850 && frame.timestampMs() <= 925);
            assertTrue(decoder.pcm().size() == 0, "Paused seek must invalidate old PCM");
            timeline.set(0); decoder.seek(0, true); awaitReady(decoder);
            assertNotNull(decoder.frame(0), "Loop wrapping reopens EOF without a scheduled pause");
        }
        // Track switches replace the owner and configure the new global stream before start.
        for (int stream : new int[] {1, 2}) {
            timeline.set(0);
            try (var decoder = localDecoder(fixture, 16, 16, stream, timeline, true)) {
                awaitReady(decoder);
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (!decoder.buffered() && System.nanoTime() < deadline) { Thread.sleep(5); }
                assertTrue(decoder.buffered());
                var data = ByteBuffer.allocate(38_400).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                decoder.pcm().readAt(data, 0, 0); data.flip();
                int crossings = 0; short previous = 0;
                while (data.remaining() >= 2) {
                    short value = data.getShort(); if (previous <= 0 && value > 0) { crossings++; } previous = value;
                }
                org.junit.jupiter.api.Assertions.assertEquals(stream == 1 ? 176 : 352, crossings, 2);
            }
        }
    }

    @Test void audioOnlyPrebufferIsBoundedAndPausedPreviewDoesNotRequireVideo() throws Exception {
        Path fixture = temporary.resolve("audio.mkv");
        try (var recorder = new FFmpegFrameRecorder(fixture.toFile(), 1)) {
            recorder.setFormat("matroska"); recorder.setSampleRate(48_000);
            recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE); recorder.start();
            for (int i = 0; i < 48; i++) { recorder.recordSamples(48_000, 1, ShortBuffer.allocate(2048)); }
            recorder.stop();
        }
        AtomicLong timeline = new AtomicLong();
        try (var decoder = localDecoder(fixture, 0, 0, 0, timeline, true)) {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!decoder.buffered() && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(decoder.buffered(), decoder.error());
            assertTrue(decoder.pcm().size() >= 38_400 && decoder.pcm().size() <= 192_000);
            decoder.seek(700, false); awaitReady(decoder);
            assertTrue(decoder.pcm().size() == 0); assertTrue(decoder.queuedFrames() == 0);
        }
    }

    @Test void briefDecoderStallCatchesUpWithoutWholeMovieSeeks() throws Exception {
        AtomicLong timeline = new AtomicLong();
        AtomicLong stall = new AtomicLong();
        try (var decoder = new MediaDecoder(() -> new FFmpegFrameGrabber("stall.mkv") {
            private long timestamp;
            @Override public void start() {}
            @Override public void setTimestamp(long value) { timestamp = value; }
            @Override public Frame grab() {
                long delay = stall.getAndSet(0);
                if (delay > 0) {
                    try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                var frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 4);
                frame.timestamp = timestamp; timestamp += 33_334; return frame;
            }
            @Override public void close() {}
        }, 2, timeline::get, true)) {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (!decoder.buffered() && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(decoder.buffered());
            long anchor = System.nanoTime(); int frames = 0;
            stall.set(150);
            while (timeline.get() < 1200) {
                timeline.set((System.nanoTime() - anchor) / 1_000_000);
                if (decoder.frame(timeline.get()) != null) { frames++; }
                Thread.sleep(10);
            }
            assertTrue(frames >= 32, "A 150ms stall must fit within the prebuffer: " + frames);
            assertTrue(!decoder.takeResync());
        }
    }

    private static MediaDecoder localDecoder(Path file, int width, int height, int stream, AtomicLong timeline, boolean playing) {
        return new MediaDecoder(() -> {
            var grabber = MediaDecoder.configuredGrabber(file.toString(), "", width, height, stream);
            grabber.setOption("protocol_whitelist", "file,http,https,tcp,tls,crypto");
            return grabber;
        }, width, timeline::get, playing);
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
