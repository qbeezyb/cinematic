package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.cinematic.media.Credentials;
import me.cinematic.media.MediaHttpHandler;
import me.cinematic.media.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpHandlerTest {
    @TempDir Path temporary;

    @Test void handlerAuthenticatesServesRangesAndRejectsMissingOrUnsafeFiles() throws Exception {
        Path root = temporary.resolve("media"); Files.createDirectories(root);
        Path file = Files.writeString(root.resolve("film.mp4"), "0123456789");
        var credentials = new Credentials(System::currentTimeMillis);
        UUID player = UUID.randomUUID();
        String token = credentials.issue(player).token();
        try (var library = new MediaLibrary(root, path -> new MediaLibrary.ProbeResult(1000, 16, 9, true), message -> {})) {
            library.reload().get();
            var handler = new MediaHttpHandler(library, credentials);
            String uri = "/media/" + library.entries().getFirst().id();
            Exchange full = new Exchange(uri, "GET", token, null); handler.handle(full);
            assertEquals(200, full.code); assertEquals("0123456789", full.body.toString());
            Exchange range = new Exchange(uri, "GET", token, "bytes=3-6"); handler.handle(range);
            assertEquals(206, range.code); assertEquals("3456", range.body.toString());
            assertEquals("bytes 3-6/10", range.response.getFirst("Content-Range"));
            Exchange suffix = new Exchange(uri, "GET", token, "bytes=-2"); handler.handle(suffix);
            assertEquals("89", suffix.body.toString());
            Exchange head = new Exchange(uri, "HEAD", token, "bytes=2-"); handler.handle(head);
            assertEquals(206, head.code); assertEquals(0, head.body.size());
            assertEquals("8", head.response.getFirst("Content-Length"));
            Exchange invalid = new Exchange(uri, "GET", token, "bytes=20-"); handler.handle(invalid);
            assertEquals(416, invalid.code); assertEquals("bytes */10", invalid.response.getFirst("Content-Range"));
            Exchange unsafe = new Exchange("/media/%2e%2e/private", "GET", token, null); handler.handle(unsafe); assertEquals(404, unsafe.code);
            Exchange method = new Exchange(uri, "POST", token, null); handler.handle(method); assertEquals(405, method.code);
            Exchange unauthorized = new Exchange(uri, "GET", "invalid", null); handler.handle(unauthorized); assertEquals(401, unauthorized.code);
            Files.delete(file);
            Exchange missing = new Exchange(uri, "GET", token, null); handler.handle(missing); assertEquals(410, missing.code);
            credentials.revoke(player);
            Exchange revoked = new Exchange(uri, "GET", token, null); handler.handle(revoked); assertEquals(401, revoked.code);
        }
    }

    private static final class Exchange extends HttpExchange {
        private final URI uri;
        private final String method;
        private final Headers request = new Headers();
        private final Headers response = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, Object> attributes = new HashMap<>();
        private int code = -1;
        Exchange(String uri, String method, String token, String range) {
            this.uri = URI.create(uri); this.method = method;
            request.set("Authorization", "Bearer " + token);
            if (range != null) { request.set("Range", range); }
        }
        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int status, long length) { code = status; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream in, OutputStream out) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
