package ru.cherdakmd.chrdkitems;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Minimal public HTTP endpoint: no filesystem access, immutable bytes, bounded workers. */
final class ResourcePackHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;

    ResourcePackHttpServer(String bind, int port, byte[] bytes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 32);
        workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            var thread = new Thread(r, "chrdk-resource-pack-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/", exchange -> serve(exchange, bytes));
        server.start();
    }
    int port() { return server.getAddress().getPort(); }
    private static void serve(HttpExchange exchange, byte[] bytes) throws IOException {
        try (exchange) {
            if (!ResourcePackSettings.PACK_PATH.equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
