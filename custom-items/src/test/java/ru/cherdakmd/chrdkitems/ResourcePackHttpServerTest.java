package ru.cherdakmd.chrdkitems;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackHttpServerTest {
    @Test void servesOnlySnapshotAndReleasesPort() throws Exception {
        byte[] content = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int port;
        try (var server = new ResourcePackHttpServer("127.0.0.1", 0, content);
             var client = HttpClient.newHttpClient()) {
            port = server.port();
            var root = "http://127.0.0.1:" + port;
            var get = HttpRequest.newBuilder(URI.create(root + ResourcePackSettings.PACK_PATH)).timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(get, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(content, response.body());
            assertEquals("application/zip", response.headers().firstValue("Content-Type").orElseThrow());
            var head = HttpRequest.newBuilder(get.uri()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals("8", client.send(head, HttpResponse.BodyHandlers.discarding()).headers().firstValue("Content-Length").orElseThrow());
            for (String path : new String[]{"/", "/custom-items-pack.zip/anything", "/custom-items-pack.zip?x=1", "/../catalog.yml"}) {
                var req = HttpRequest.newBuilder(URI.create(root + path)).GET().build();
                assertEquals(404, client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            var post = HttpRequest.newBuilder(get.uri()).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, client.send(post, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        try (var server = new ResourcePackHttpServer("127.0.0.1", port, content)) { assertEquals(port, server.port()); }
    }
}
