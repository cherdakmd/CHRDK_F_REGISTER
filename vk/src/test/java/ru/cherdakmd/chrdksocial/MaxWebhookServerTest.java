package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MaxWebhookServerTest {

    private final Logger logger = Logger.getLogger("max-webhook-test");

    private static int post(int port, String path, String secret, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (secret != null) connection.setRequestProperty("X-Max-Bot-Api-Secret-Token", secret);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
    }

    @Test
    void validSecretDeliversUpdateAndReturns200Immediately() throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        MaxWebhookServer server = new MaxWebhookServer("127.0.0.1", 0, "/max/webhook",
                "s3cret", null, "", false, update -> {
            received.set(update);
            latch.countDown();
        }, logger);
        try {
            assertTrue(server.start());
            assertTrue(server.port() > 0);
            assertFalse(server.isTls());
            int status = post(server.port(), "/max/webhook", "s3cret",
                    "{\"update_type\":\"bot_added\",\"chat_id\":72000001}");
            assertEquals(200, status);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(72000001L, MaxBotRouter.chatId(received.get()));
        } finally {
            server.stop();
        }
    }

    @Test
    void wrongSecretWrongPathAndGetAreRejected() throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        MaxWebhookServer server = new MaxWebhookServer("127.0.0.1", 0, "/max/webhook",
                "s3cret", null, "", false, received::set, logger);
        try {
            assertTrue(server.start());
            assertEquals(401, post(server.port(), "/max/webhook", "wrong",
                    "{\"update_type\":\"bot_added\"}"));
            assertEquals(401, post(server.port(), "/max/webhook", null,
                    "{\"update_type\":\"bot_added\"}"));
            assertEquals(404, post(server.port(), "/other", "s3cret",
                    "{\"update_type\":\"bot_added\"}"));
            assertNull(received.get(), "отклонённые запросы не попадают в роутер");
        } finally {
            server.stop();
        }
    }

    @Test
    void tlsRequiredWithoutKeystoreRefusesToStart() {
        MaxWebhookServer server = new MaxWebhookServer("127.0.0.1", 0, "/max/webhook",
                "s3cret", java.nio.file.Path.of("no-such-keystore.p12"), "", true,
                update -> { }, logger);
        assertFalse(server.start());
        assertFalse(server.isRunning());
    }

    @Test
    void malformedJsonDoesNotKillAcceptor() throws Exception {
        MaxWebhookServer server = new MaxWebhookServer("127.0.0.1", 0, "/max/webhook",
                "s3cret", null, "", false, update -> { }, logger);
        try {
            assertTrue(server.start());
            assertEquals(200, post(server.port(), "/max/webhook", "s3cret", "not-json"));
            assertEquals(200, post(server.port(), "/max/webhook", "s3cret",
                    "{\"update_type\":\"bot_added\",\"chat_id\":1}"));
        } finally {
            server.stop();
        }
    }
}
