package me.cinematic.media;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dedicated, bounded HTTP byte service. It exposes no filesystem paths or directory listings. */
public final class MediaHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor executor;

    public MediaHttpServer(InetSocketAddress address, MediaLibrary library, Credentials credentials) throws IOException {
        server = HttpServer.create(address, 32);
        executor = new ThreadPoolExecutor(4, 12, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().daemon().name("Cinematic HTTP-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/", new MediaHttpHandler(library, credentials));
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
