package ru.cherdakmd.chrdkvk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Секретные ответы, сетевые ошибки и редиректы не должны раскрывать ключ. */
class VkApiSafety276Test {
    private VkApiClient client(String response, boolean networkFailure) {
        return new VkApiClient("https://api.vk.com/method/", "SECRET", "5.199", new VkApiClient.Transport() {
            @Override public String postForm(URI uri, String body, Duration timeout) throws IOException {
                if (networkFailure) throw new IOException("secret URL?access_token=SECRET");
                assertTrue(body.matches("(?s).*random_id=[0-9]+.*"));
                String id = body.split("random_id=")[1].split("&")[0];
                assertTrue(Long.parseLong(id) > 0 && Long.parseLong(id) <= Integer.MAX_VALUE);
                return response;
            }
            @Override public String get(URI uri, Duration timeout) throws IOException {
                throw new IOException("secret Long Poll key=SECRET");
            }
        });
    }

    @Test void errorsHideRawResponseAndNetworkDetailsWithoutAttachingSecretCause() {
        for (String response : new String[]{
                "{\"error\":{\"error_code\":27,\"error_msg\":\"SECRET\"}}",
                "{\"error\":{\"error_code\":\"SECRET\"}}", "malformed SECRET", "null"}) {
            IOException error = assertThrows(IOException.class, () -> client(response, false).sendMessage(42, "test"));
            assertFalse(error.getMessage().contains("SECRET"));
            assertNull(error.getCause());
        }
        IOException network = assertThrows(IOException.class, () -> client("", true).sendMessage(42, "test"));
        assertFalse(network.getMessage().contains("SECRET"));
        IOException poll = assertThrows(IOException.class, () -> client("", false).checkLongPoll(
                new VkApiClient.LongPollEndpoint("https://lp.vk.com/", "SECRET", "1"), 5));
        assertFalse(poll.getMessage().contains("SECRET"));
    }

    @Test void postRedirectNeverForwardsTokenAndLongPollRedirectNeverForwardsKey() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/capture");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/capture", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            VkApiClient.HttpTransport transport = new VkApiClient.HttpTransport();
            assertThrows(IOException.class, () -> transport.postForm(uri, "access_token=SECRET", Duration.ofSeconds(3)));
            assertThrows(IOException.class, () -> transport.get(URI.create(uri + "?key=SECRET"), Duration.ofSeconds(3)));
            assertEquals(0, forwarded.get());
        } finally {
            server.stop(0);
        }
    }
}
