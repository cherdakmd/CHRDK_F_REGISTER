package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaxApiClientTest {

    private static final class Fake implements MaxApiClient.Transport {
        final List<URI> uris = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        String response = "{}";

        @Override
        public String request(String method, URI uri, String token, String jsonBody, Duration timeout) {
            methods.add(method);
            uris.add(uri);
            tokens.add(token);
            bodies.add(jsonBody);
            return response;
        }
    }

    @Test
    void tokenNeverAppearsInUriOnlyInHeaderSlot() throws Exception {
        Fake transport = new Fake();
        transport.response = "{\"user_id\":1,\"name\":\"bot\"}";
        MaxApiClient client = new MaxApiClient("https://platform-api2.max.ru", "SECRET-TOKEN", transport);
        assertTrue(client.configured());
        assertNotNull(client.me());
        assertFalse(transport.uris.get(0).toString().contains("SECRET-TOKEN"));
        assertEquals("SECRET-TOKEN", transport.tokens.get(0));
        assertEquals("https://platform-api2.max.ru/me", transport.uris.get(0).toString());
    }

    @Test
    void updatesEncodeMarkerAndReturnNextMarker() throws Exception {
        Fake transport = new Fake();
        transport.response = "{\"updates\":[{\"update_type\":\"bot_added\"}],\"marker\":\"42\"}";
        MaxApiClient client = new MaxApiClient("https://platform-api2.max.ru/", "token", transport);
        MaxApiClient.Updates batch = client.updates("41", 25, 100);
        assertEquals(1, batch.updates().size());
        assertEquals("42", batch.marker());
        String uri = transport.uris.get(0).toString();
        assertTrue(uri.contains("/updates?limit=100&timeout=25&marker="));
        assertFalse(uri.contains("token"));
    }

    @Test
    void messagesUseChatIdInBodyAndParseMid() throws Exception {
        Fake transport = new Fake();
        transport.response = "{\"message\":{\"body\":{\"mid\":\"mid-777\"}}}";
        MaxApiClient client = new MaxApiClient("https://platform-api2.max.ru/", "token", transport);
        MaxApiClient.SentMessage sent = client.sendToChat(72000001L, "Новость");
        assertTrue(sent.success());
        assertEquals("mid-777", sent.id());
        assertEquals("POST", transport.methods.get(0));
        assertTrue(transport.uris.get(0).toString().endsWith("/messages"));
        assertTrue(transport.bodies.get(0).contains("\"chat_id\":72000001"));
        assertTrue(transport.bodies.get(0).contains("Новость"));
    }

    @Test
    void subscriptionUsesHttpsUrlSecretAndUpdateTypes() throws Exception {
        Fake transport = new Fake();
        transport.response = "{\"success\":true}";
        MaxApiClient client = new MaxApiClient("https://platform-api2.max.ru/", "token", transport);
        assertTrue(client.setSubscription("https://bot.example.ru/max/webhook", "s3cret",
                List.of("message_created", "bot_added")));
        String body = transport.bodies.get(0);
        assertTrue(body.contains("https://bot.example.ru/max/webhook"));
        assertTrue(body.contains("s3cret"));
        assertTrue(body.contains("message_created"));
    }

    @Test
    void invalidIdsAreNotSent() throws Exception {
        Fake transport = new Fake();
        MaxApiClient client = new MaxApiClient("https://platform-api2.max.ru/", "token", transport);
        assertFalse(client.sendToChat(0L, "x").success());
        assertFalse(client.sendToUser(-1L, "x").success());
        assertTrue(transport.uris.isEmpty());
    }
}
