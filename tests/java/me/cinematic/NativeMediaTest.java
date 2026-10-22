package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import me.cinematic.media.NativeMediaProbe;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeMediaTest {
    @TempDir Path temporary;

    @Test void everyAudioStreamIsProbedAndTheGlobalSelectionChangesDecodedSound() throws Exception {
        Path fixture = Path.of("tests/fixtures/languages.mkv");
        var info = new NativeMediaProbe().read(fixture);
        assertEquals(2, info.audioTracks().size());
        var german = info.audioTracks().get(0); var english = info.audioTracks().get(1);
        assertEquals(1, german.streamIndex()); assertEquals(2, english.streamIndex());
        assertEquals("deu", german.language()); assertEquals("eng", english.language());
        assertEquals("German", german.title()); assertEquals("flac", german.codec());
        assertEquals(1, german.channels()); assertTrue(english.defaultTrack());
        assertEquals(2, me.cinematic.media.AudioTrack.defaultStream(info.audioTracks()));
        for (var track : info.audioTracks()) {
            try (var grabber = new FFmpegFrameGrabber(fixture.toFile())) {
                grabber.setAudioStream(track.streamIndex());
                grabber.setSampleFormat(org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16);
                grabber.start();
                int crossings = 0; int samples = 0; short previous = 0;
                while (samples < 48_000) {
                    var frame = grabber.grabSamples(); assertNotNull(frame);
                    var pcm = ((java.nio.ShortBuffer) frame.samples[0]).duplicate();
                    while (pcm.hasRemaining() && samples < 48_000) {
                        short value = pcm.get();
                        if (previous <= 0 && value > 0) { crossings++; }
                        previous = value; samples++;
                    }
                }
                assertEquals(track.streamIndex() == 1 ? 440 : 880, crossings, 2);
            }
        }
    }

    @Test void headlessNativesProbeAndDecodePcmWithoutAnyInstalledPlayer() throws Exception {
        FFmpegFrameGrabber.tryLoad();
        Path path = temporary.resolve("tone.wav");
        ByteBuffer wav = ByteBuffer.allocate(44 + 96_000).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + 96_000);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(48_000).putInt(96_000).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(96_000);
        while (wav.hasRemaining()) { wav.putShort((short) 0); }
        Files.write(path, wav.array());
        var info = new NativeMediaProbe().read(path);
        assertEquals(1000, info.durationMs(), 10);
        assertTrue(info.audio());
        assertEquals(1, info.audioTracks().size());
        try (var grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.start();
            grabber.setTimestamp(500_000);
            var frame = grabber.grabSamples();
            assertNotNull(frame);
            assertNotNull(frame.samples);
            assertTrue(frame.timestamp >= 450_000);
        }
    }

    @Test void packagedFfmpegCanDecodeAndSeekAnOrdinaryVideoContainer() throws Exception {
        Path path = temporary.resolve("video.mp4");
        try (var recorder = new FFmpegFrameRecorder(path.toFile(), 320, 180);
                var frame = new Frame(320, 180, Frame.DEPTH_UBYTE, 3)) {
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(30);
            recorder.start();
            for (int i = 0; i < 60; i++) {
                ByteBuffer image = (ByteBuffer) frame.image[0];
                for (int p = 0; p < image.capacity(); p++) { image.put(p, (byte) (i * 3)); }
                recorder.record(frame);
            }
            recorder.stop();
        }
        var info = new NativeMediaProbe().read(path);
        assertTrue(info.audioTracks().isEmpty());
        assertEquals(320, info.width()); assertEquals(180, info.height());
        assertTrue(info.durationMs() >= 1900);
        try (var grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.start(); grabber.setTimestamp(1_000_000);
            Frame decoded = grabber.grabImage();
            assertNotNull(decoded); assertNotNull(decoded.image);
            assertTrue(decoded.timestamp >= 900_000);
        }
    }
}
