package me.cinematic.client;

import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.cinematic.core.PcmQueue;
import me.cinematic.core.SeekMailbox;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/** One owner thread per native decoder; render/sound threads only see bounded, copied buffers. */
public final class MediaDecoder implements AutoCloseable {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon().name("Cinematic decoder-", 0).factory());
    public record VideoFrame(long timestampMs, int width, int height, byte[] rgba) {}
    private final ArrayBlockingQueue<VideoFrame> video = new ArrayBlockingQueue<>(5);
    private final PcmQueue pcm = new PcmQueue();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SeekMailbox seeks = new SeekMailbox();
    private final AtomicBoolean resync = new AtomicBoolean();
    private final LongSupplier timeline;
    private Future<?> worker;
    private volatile boolean playing;
    private volatile boolean ready;
    private volatile boolean ended;
    private volatile String error = "";
    private volatile int epoch;
    private volatile long lastDecodedMs;
    private volatile String stage = "Opening media…";
    private volatile long progressAt = System.nanoTime();

    public MediaDecoder(String url, String token, int sourceWidth, int sourceHeight, LongSupplier timeline, boolean playing) {
        this(() -> configuredGrabber(url, token, sourceWidth, sourceHeight), sourceWidth, timeline, playing);
    }

    MediaDecoder(Supplier<FFmpegFrameGrabber> factory, int sourceWidth, LongSupplier timeline, boolean playing) {
        this.timeline = timeline;
        this.playing = playing;
        try { worker = WORKERS.submit(() -> decode(factory, sourceWidth)); }
        catch (RejectedExecutionException e) { error = "Decoders are closing. Wait a few seconds and click playback status to retry."; }
    }

    static FFmpegFrameGrabber configuredGrabber(String url, String token, int sourceWidth, int sourceHeight) {
        var grabber = new FFmpegFrameGrabber(url);
        grabber.setOption("headers", "Authorization: Bearer " + token + "\r\n");
        grabber.setOption("rw_timeout", "10000000");
        grabber.setOption("protocol_whitelist", "http,https,tcp,tls,crypto");
        grabber.setOption("format_whitelist", "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,mp3,wav,ogg,flac");
        grabber.setOption("threads", "2");
        grabber.setPixelFormat(AV_PIX_FMT_RGBA);
        grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
        grabber.setSampleRate(48_000);
        grabber.setAudioChannels(1);
        if (sourceWidth > 0 && sourceHeight > 0) {
            double scale = Math.min(1, Math.min(1920.0 / sourceWidth, 1080.0 / sourceHeight));
            grabber.setImageWidth(Math.max(1, (int) Math.round(sourceWidth * scale)));
            grabber.setImageHeight(Math.max(1, (int) Math.round(sourceHeight * scale)));
        }
        return grabber;
    }

    private void decode(Supplier<FFmpegFrameGrabber> factory, int sourceWidth) {
        if (closed.get()) { return; }
        try {
            long generation = 0;
            while (!closed.get()) {
                try (var grabber = factory.get()) {
                    stage = "Opening media…";
                    progressAt = System.nanoTime();
                    grabber.start();
                    var context = grabber.getFormatContext();
                    long timestampOriginUs = context != null && context.start_time() != AV_NOPTS_VALUE ? context.start_time() : 0;
                    long start = Math.max(0, timeline.getAsLong());
                    stage = "Seeking media…";
                    grabber.setTimestamp(start * 1000);
                    lastDecodedMs = start;
                    stage = "Buffering first frame…";
                    progressAt = System.nanoTime();
                    long lastVideoUs = Long.MIN_VALUE / 2;
                    long lastSeek = System.nanoTime();
                    while (!closed.get()) {
                        var requested = seeks.take();
                        if (requested != null) {
                            generation = requested.generation();
                            stage = "Seeking media…";
                            grabber.setTimestamp(requested.targetMs() * 1000);
                            seeks.publish(generation, () -> lastDecodedMs = requested.targetMs());
                            stage = "Buffering first frame…";
                            progressAt = System.nanoTime();
                            lastVideoUs = Long.MIN_VALUE / 2;
                            lastSeek = System.nanoTime();
                        }
                        // Audio may precede video by more than the normal decode-ahead window.
                        // Throttling before the first image deadlocks a paused MKV preview.
                        if (ready && (!playing || ended || lastDecodedMs > timeline.getAsLong() + 150 || video.remainingCapacity() == 0)) {
                            Thread.sleep(5);
                            continue;
                        }
                        Frame frame = grabber.grab();
                        if (frame == null) {
                            if (seeks.publish(generation, () -> { ended = true; ready = true; })) { break; }
                            continue;
                        }
                        progressAt = System.nanoTime();
                        long timestamp = Math.max(0, (frame.timestamp - timestampOriginUs) / 1000);
                        long target = timeline.getAsLong();
                        if (timestamp < target - 250 && playing && ready) {
                            if (System.nanoTime() - lastSeek > 1_000_000_000L) { seeks.publish(generation, () -> resync.set(true)); }
                            continue;
                        }
                        VideoFrame image = frame.image != null && frame.timestamp - lastVideoUs >= 33_333 ? copyVideo(frame, timestamp) : null;
                        // A preview belongs at the requested position even when the first video
                        // packet has a later timestamp than the start of the audio track.
                        if (image != null && !playing && !ready) {
                            image = new VideoFrame(Math.min(image.timestampMs(), target), image.width(), image.height(), image.rgba());
                        }
                        VideoFrame publishedImage = image;
                        byte[] audio = frame.samples != null ? copyAudio(frame) : null;
                        boolean published = seeks.publish(generation, () -> {
                            lastDecodedMs = timestamp;
                            if (publishedImage != null) { video.offer(publishedImage); ready = true; }
                            if (audio != null) {
                                if (playing) { pcm.offer(audio); }
                                if (sourceWidth <= 0) { ready = true; }
                            }
                        });
                        if (published && image != null) {
                            lastVideoUs = frame.timestamp;
                        }
                    }
                }
                // Release FFmpeg at EOF. A later seek reopens it on this same owner thread.
                while (!closed.get() && ended) { Thread.sleep(5); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception | LinkageError e) {
            // FFmpeg exceptions can include transport strings. Never expose the Authorization option.
            error = "Playback failed while " + stage.replace("…", "").toLowerCase(java.util.Locale.ROOT)
                    + ". Check the media port and file codecs; click status to retry (" + e.getClass().getSimpleName() + ")";
        } finally { pcm.close(); }
    }

    private static VideoFrame copyVideo(Frame frame, long timestampMs) {
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        if (width < 1 || height < 1 || width > 1920 || height > 1080 || !(frame.image[0] instanceof ByteBuffer source)) {
            throw new IllegalArgumentException("Unsupported decoded video frame");
        }
        byte[] copy = new byte[width * height * 4];
        ByteBuffer pixels = source.duplicate();
        int initial = pixels.position();
        for (int y = 0; y < height; y++) {
            pixels.position(initial + y * frame.imageStride);
            pixels.get(copy, y * width * 4, width * 4);
        }
        return new VideoFrame(timestampMs, width, height, copy);
    }

    private static byte[] copyAudio(Frame frame) {
        if (!(frame.samples[0] instanceof ShortBuffer samples)) { throw new IllegalArgumentException("Expected signed 16-bit PCM"); }
        ShortBuffer source = samples.duplicate();
        byte[] bytes = new byte[source.remaining() * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(source);
        return bytes;
    }

    public VideoFrame frame(long targetMs) {
        VideoFrame latest = null;
        for (VideoFrame next; (next = video.peek()) != null && next.timestampMs() <= targetMs + 25;) {
            VideoFrame polled = video.poll();
            if (polled != null) { latest = polled; }
        }
        return latest;
    }

    public void discardBefore(long targetMs) {
        for (VideoFrame next; (next = video.peek()) != null && next.timestampMs() < targetMs;) { video.poll(); }
    }
    public long lastDecodedMs() { return lastDecodedMs; }

    public void seek(long targetMs, boolean playing) {
        seeks.request(targetMs, playing, () -> {
            this.playing = playing;
            progressAt = System.nanoTime();
            stage = "Seeking media…";
            ready = false;
            ended = false;
            video.clear(); pcm.clear();
            epoch++;
        });
    }
    public PcmQueue pcm() { return pcm; }
    public boolean ready() { return ready; }
    public boolean ended() { return ended; }
    public String error() {
        if (error.isEmpty() && !ready && !closed.get() && System.nanoTime() - progressAt > 30_000_000_000L) {
            error = "Media timed out while " + stage.replace("…", "").toLowerCase(java.util.Locale.ROOT)
                    + ". Check the media port/advertised URL and click status to retry.";
            close();
        }
        return error;
    }
    public String stage() { return stage; }
    public int epoch() { return epoch; }
    public boolean takeResync() { return resync.getAndSet(false); }
    public void requestResync() { resync.set(true); }
    @Override public void close() {
        closed.set(true);
        seeks.close(() -> { pcm.close(); video.clear(); ready = false; });
        if (worker != null) { worker.cancel(true); WORKERS.purge(); }
    }
}
