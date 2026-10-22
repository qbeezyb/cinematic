import java.io.File;

/** Deliberately uses only JDK APIs at compile time; libraries must come from the finished mod. */
public final class PackagedNativeSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
        type.getMethod("tryLoad").invoke(null);
        Object grabber = type.getConstructor(File.class).newInstance(new File(args[0]));
        try {
            type.getMethod("start").invoke(grabber);
            long duration = (long) type.getMethod("getLengthInTime").invoke(grabber);
            if (duration < 900_000 || duration > 1_100_000) { throw new AssertionError("Unexpected WAV duration: " + duration); }
            type.getMethod("setTimestamp", long.class).invoke(grabber, 500_000L);
            if (type.getMethod("grabSamples").invoke(grabber) == null) { throw new AssertionError("No audio samples after seeking"); }
            System.out.println("Packaged FFmpeg loaded, decoded PCM, and sought successfully on " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        } finally { ((AutoCloseable) grabber).close(); }
    }
}
