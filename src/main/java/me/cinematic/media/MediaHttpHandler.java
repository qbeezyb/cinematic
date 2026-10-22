package me.cinematic.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

/** Stateless HTTP protocol adapter, usable with an in-memory exchange in tests. */
public final class MediaHttpHandler implements HttpHandler {
    private final MediaLibrary library;
    private final Credentials credentials;
    private final Semaphore transfers = new Semaphore(8);

    public MediaHttpHandler(MediaLibrary library, Credentials credentials) {
        this.library = library;
        this.credentials = credentials;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        boolean acquired = false;
        try {
            exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (!credentials.authenticate(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                empty(exchange, 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                empty(exchange, 405);
                return;
            }
            String raw = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null || !raw.matches("/media/[a-f0-9]{32}")) {
                empty(exchange, 404);
                return;
            }
            MediaEntry entry = library.get(raw.substring(7));
            if (entry == null) { empty(exchange, 404); return; }
            acquired = transfers.tryAcquire();
            if (!acquired) { exchange.getResponseHeaders().set("Retry-After", "2"); empty(exchange, 503); return; }
            try (var input = library.open(entry)) {
                String header = exchange.getRequestHeaders().getFirst("Range");
                ByteRange range;
                try { range = ByteRange.parse(header, entry.size()); }
                catch (IllegalArgumentException e) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + entry.size());
                    empty(exchange, 416);
                    return;
                }
                var headers = exchange.getResponseHeaders();
                headers.set("Accept-Ranges", "bytes");
                headers.set("Content-Type", "application/octet-stream");
                headers.set("Content-Length", Long.toString(range.length()));
                if (header != null) {
                    headers.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + entry.size());
                }
                if (method.equals("HEAD")) { empty(exchange, header == null ? 200 : 206); return; }
                input.position(range.start());
                exchange.sendResponseHeaders(header == null ? 200 : 206, range.length());
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long remaining = range.length();
                while (remaining > 0) {
                    buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                    int count = input.read(buffer);
                    if (count < 0) { break; }
                    exchange.getResponseBody().write(buffer.array(), 0, count);
                    remaining -= count;
                }
            } catch (IOException e) {
                if (exchange.getResponseCode() < 0) { empty(exchange, 410); }
                // Client disconnects and seek cancellations are normal; do not log credentials or URLs.
            }
        } finally {
            if (acquired) { transfers.release(); }
            exchange.close();
        }
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

}
