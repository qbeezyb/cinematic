package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import me.cinematic.media.ByteRange;
import me.cinematic.media.Credentials;
import me.cinematic.media.MediaHttpServer;
import me.cinematic.media.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaServiceTest {
    @TempDir Path temporary;

    @Test void rangesSupportSuffixOpenEndedAndClippedBounds() {
        assertEquals(new ByteRange(2, 5), ByteRange.parse("bytes=2-5", 10));
        assertEquals(new ByteRange(2, 9), ByteRange.parse("bytes=2-", 10));
        assertEquals(new ByteRange(7, 9), ByteRange.parse("bytes=-3", 10));
        assertEquals(new ByteRange(0, 9), ByteRange.parse("bytes=-20", 10));
        assertEquals(new ByteRange(5, 9), ByteRange.parse("bytes=5-999", 10));
        for (String bad : new String[] {"bytes=10-", "bytes=5-2", "bytes=-0", "bytes=1-2,4-5", "items=0-2", "bytes=+2-3", "bytes=999999999999999999999999-"}) {
            assertThrows(IllegalArgumentException.class, () -> ByteRange.parse(bad, 10), bad);
        }
    }

    @Test void credentialsRenewExpireAndRevokeWithoutRetainingUnboundedTokens() {
        AtomicLong now = new AtomicLong(100);
        Credentials credentials = new Credentials(now::get);
        UUID player = UUID.randomUUID();
        var first = credentials.issue(player);
        assertTrue(credentials.authenticate("Bearer " + first.token()));
        assertFalse(credentials.authenticate("Bearer invalid"));
        now.addAndGet(1000);
        var second = credentials.issue(player);
        assertTrue(credentials.authenticate("Bearer " + first.token()));
        credentials.issue(player);
        assertFalse(credentials.authenticate("Bearer " + first.token()));
        now.set(second.expiresMs());
        assertFalse(credentials.authenticate("Bearer " + second.token()));
        var renewed = credentials.issue(player);
        credentials.revoke(player);
        assertFalse(credentials.authenticate("Bearer " + renewed.token()));
    }

    @Test void indexRejectsSymlinksAndCorruptMediaAndServesOnlyCurrentEntries() throws Exception {
        Path root = temporary.resolve("media");
        Files.createDirectories(root);
        Path good = Files.writeString(root.resolve("movie.mp4"), "0123456789");
        Files.writeString(root.resolve("bad.mkv"), "corrupt");
        Files.writeString(root.resolve("notes.txt"), "private");
        Path external = Files.writeString(temporary.resolve("outside.mp4"), "secret");
        Files.createSymbolicLink(root.resolve("escape.mp4"), external);
        try (var library = new MediaLibrary(root, path -> {
            if (path.getFileName().toString().equals("bad.mkv")) { throw new java.io.IOException("No streams"); }
            return new MediaLibrary.ProbeResult(10_000, 1920, 1080, true);
        }, message -> {})) {
            assertEquals(1, library.reload().get());
            var entry = library.entries().getFirst();
            assertEquals("movie.mp4", entry.name());
            assertFalse(entry.id().contains("movie"));
            try (var channel = library.open(entry)) { assertEquals(10, channel.size()); }
            Files.delete(good);
            Files.createSymbolicLink(good, external);
            assertThrows(java.io.IOException.class, () -> library.open(entry));
            assertEquals(0, library.reload().get());
        }
    }

    @Test void authenticatedHttpSupportsRealSeekingHeadAndFailurePaths() throws Exception {
        Path root = temporary.resolve("media");
        Files.createDirectories(root);
        Files.writeString(root.resolve("movie.mp4"), "0123456789", StandardCharsets.UTF_8);
        try (var library = new MediaLibrary(root, path -> new MediaLibrary.ProbeResult(1000, 1, 1, true), message -> {})) {
            library.reload().get();
            Credentials credentials = new Credentials(System::currentTimeMillis);
            var token = credentials.issue(UUID.randomUUID());
            MediaHttpServer bound;
            try { bound = new MediaHttpServer(new InetSocketAddress("127.0.0.1", 0), library, credentials); }
            catch (java.net.SocketException e) {
                if (e.getMessage().contains("Operation not permitted")) {
                    org.junit.jupiter.api.Assumptions.abort("Sandbox forbids listening sockets; handler protocol is tested separately");
                }
                throw e;
            }
            try (var service = bound;
                    var client = HttpClient.newHttpClient()) {
                URI uri = URI.create("http://127.0.0.1:" + service.port() + "/media/" + library.entries().getFirst().id());
                var response = client.send(request(uri, token.token(), "GET", "bytes=3-6"), HttpResponse.BodyHandlers.ofString());
                assertEquals(206, response.statusCode());
                assertEquals("3456", response.body());
                assertEquals("bytes 3-6/10", response.headers().firstValue("Content-Range").orElseThrow());
                var head = client.send(request(uri, token.token(), "HEAD", null), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, head.statusCode());
                assertEquals("10", head.headers().firstValue("Content-Length").orElseThrow());
                assertEquals("", head.body());
                assertEquals(416, client.send(request(uri, token.token(), "GET", "bytes=99-"), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(405, client.send(request(uri, token.token(), "POST", null), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(404, client.send(request(uri.resolve("/media/%2e%2e/private"), token.token(), "GET", null), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(401, client.send(request(uri, "wrong", "GET", null), HttpResponse.BodyHandlers.discarding()).statusCode());
                Files.delete(root.resolve("movie.mp4"));
                assertEquals(410, client.send(request(uri, token.token(), "GET", null), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }

    private HttpRequest request(URI uri, String token, String method, String range) {
        var builder = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                .timeout(java.time.Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
        if (range != null) { builder.header("Range", range); }
        return builder.build();
    }
}
