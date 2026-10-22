package me.cinematic.media;

/** RFC 9110 single byte ranges. Unsatisfiable or unsupported multipart ranges produce 416. */
public record ByteRange(long start, long end) {
    public long length() { return end - start + 1; }

    public static ByteRange parse(String header, long size) {
        if (size <= 0) { throw new IllegalArgumentException("Empty resource"); }
        if (header == null) { return new ByteRange(0, size - 1); }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || header.length() > 100) {
            throw new IllegalArgumentException("Only one byte range is supported");
        }
        String value = header.substring(6).trim();
        int dash = value.indexOf('-');
        if (dash < 0 || dash != value.lastIndexOf('-')) { throw new IllegalArgumentException("Malformed range"); }
        try {
            if (dash == 0) {
                long suffix = unsigned(value.substring(1));
                if (suffix == 0) { throw new IllegalArgumentException("Empty suffix"); }
                return new ByteRange(Math.max(0, size - suffix), size - 1);
            }
            long start = unsigned(value.substring(0, dash));
            long end = dash == value.length() - 1 ? size - 1 : unsigned(value.substring(dash + 1));
            if (start >= size || end < start) { throw new IllegalArgumentException("Unsatisfiable range"); }
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid range number", e); }
    }

    private static long unsigned(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new NumberFormatException("Unsigned integer required");
        }
        return Long.parseLong(value);
    }
}
