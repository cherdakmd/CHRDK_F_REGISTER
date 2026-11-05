package ru.cherdakmd.chrdksocial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент MAX Bot API ({@code https://platform-api2.max.ru}), без сторонних SDK.
 *
 * <p>Токен передаётся только заголовком {@code Authorization} и никогда не
 * попадает в URI, тела ошибок или логи. Редиректы запрещены. Домен по умолчанию
 * актуален с 19 июля 2026 года; {@code GET /chats} не поддерживается — chat_id
 * приходит из событий {@code bot_added}/{@code bot_started}.</p>
 */
public final class MaxApiClient {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public interface Transport {
        String request(String method, URI uri, String token, String jsonBody, Duration timeout)
                throws IOException, InterruptedException;
    }

    public static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public String request(String method, URI uri, String token, String jsonBody, Duration timeout)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", token)
                    .header("User-Agent", "CHRDK_SOCIAL/1.0")
                    .header("Accept", "application/json");
            if (jsonBody == null) {
                builder = builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder = builder.header("Content-Type", "application/json; charset=UTF-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("MAX API HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    public record Updates(List<JsonObject> updates, String marker) { }
    public record SentMessage(String id, boolean success) { }

    private final Transport transport;
    private final String base;
    private final String token;

    public MaxApiClient(String apiBaseUrl, String accessToken, Transport transport) {
        String safeBase = apiBaseUrl == null || apiBaseUrl.isBlank() ? SocialConfig.DEFAULT_MAX_API : apiBaseUrl;
        this.base = safeBase.endsWith("/") ? safeBase : safeBase + "/";
        this.token = accessToken == null ? "" : accessToken.trim();
        this.transport = transport == null ? new HttpTransport() : transport;
    }

    public boolean configured() {
        return !token.isEmpty();
    }

    private String call(String method, String path, String jsonBody, Duration timeout)
            throws IOException, InterruptedException {
        return transport.request(method, URI.create(base + path), token, jsonBody, timeout);
    }

    public JsonObject me() throws IOException, InterruptedException {
        return JsonParser.parseString(call("GET", "me", null, Duration.ofSeconds(10))).getAsJsonObject();
    }

    /** Long Polling для разработки и fallback'а: {@code GET /updates}. */
    public Updates updates(String marker, int timeoutSeconds, int limit)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("updates?limit=").append(Math.clamp(limit, 1, 1000))
                .append("&timeout=").append(Math.clamp(timeoutSeconds, 1, 60));
        if (marker != null && !marker.isBlank()) {
            path.append("&marker=").append(URLEncoder.encode(marker, StandardCharsets.UTF_8));
        }
        JsonObject root = JsonParser.parseString(call("GET", path.toString(), null,
                Duration.ofSeconds(Math.clamp(timeoutSeconds, 1, 60) + 10L))).getAsJsonObject();
        List<JsonObject> list = new ArrayList<>();
        if (root.has("updates") && root.get("updates").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("updates")) {
                if (element.isJsonObject()) list.add(element.getAsJsonObject());
            }
        }
        String next = text(root, "marker");
        return new Updates(List.copyOf(list), next.isEmpty() && marker != null ? marker : next);
    }

    /** Production-подписка: webhook только по HTTPS, с секретом и явным набором событий. */
    public boolean setSubscription(String publicUrl, String secret, List<String> updateTypes)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("url", publicUrl);
        body.addProperty("secret", secret);
        JsonArray types = new JsonArray();
        for (String type : updateTypes == null ? List.<String>of() : updateTypes) types.add(type);
        body.add("update_types", types);
        call("POST", "subscriptions", GSON.toJson(body), Duration.ofSeconds(15));
        return true;
    }

    public JsonObject subscriptions() throws IOException, InterruptedException {
        return JsonParser.parseString(call("GET", "subscriptions", null, Duration.ofSeconds(10)))
                .getAsJsonObject();
    }

    /** {@code POST /messages} в чат/канал. chat_id берётся из событий бота, не из списка чатов. */
    public SentMessage sendToChat(long chatId, String text) throws IOException, InterruptedException {
        if (chatId <= 0L) return new SentMessage("", false);
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text == null ? "" : text);
        return send(body);
    }

    /** Личное сообщение пользователю бота (коды подтверждения и уведомления). */
    public SentMessage sendToUser(long userId, String text) throws IOException, InterruptedException {
        if (userId <= 0L) return new SentMessage("", false);
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        body.addProperty("text", text == null ? "" : text);
        return send(body);
    }

    private SentMessage send(JsonObject body) throws IOException, InterruptedException {
        String raw = call("POST", "messages", GSON.toJson(body), Duration.ofSeconds(15));
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        String id = text(root, "message_id");
        if (id.isEmpty() && root.has("message") && root.get("message").isJsonObject()) {
            JsonObject message = root.getAsJsonObject("message");
            if (message.has("body") && message.get("body").isJsonObject()) {
                id = text(message.getAsJsonObject("body"), "mid");
            }
            if (id.isEmpty()) id = text(message, "mid");
        }
        if (id.isEmpty()) id = Long.toString(System.currentTimeMillis());
        return new SentMessage(id, true);
    }

    private static String text(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return "";
        try {
            return json.get(key).getAsString().trim();
        } catch (RuntimeException bad) {
            return "";
        }
    }
}
