package ru.cherdakmd.chrdkstreams;

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
import java.util.logging.Logger;

/**
 * Twitch Helix: «идёт ли эфир у канала».
 *
 * <p>Запрос — {@code GET /helix/streams?user_login=<канал>} с заголовками
 * {@code Client-Id} и {@code Authorization: Bearer <токен>}. Пустой массив
 * {@code data} значит «эфира нет» — это не ошибка.</p>
 *
 * <p>Токен: если задан {@code client-secret}, плагин получает токен приложения
 * ({@code client_credentials}) и обновляет его сам; иначе используется готовый
 * {@code token} из конфига. Разбор ответов вынесен в статические методы, чтобы
 * проверяться тестами без сети.</p>
 */
final class TwitchClient implements StreamClient {

    static final String STREAMS_URL = "https://api.twitch.tv/helix/streams?user_login=";
    static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    /** Обновляем токен заранее, чтобы не попасть на истёкший в середине опроса. */
    private static final long TOKEN_MARGIN_MILLIS = 5 * 60_000L;

    private final HttpClient http;
    private final Logger logger;
    private final String clientId;
    private final String clientSecret;
    private final String staticToken;

    private String token;
    private long tokenExpiresAt;

    TwitchClient(HttpClient http, Logger logger, StreamConfig.Twitch config) {
        this(http, logger, config.clientId(), config.clientSecret(), config.token());
    }

    TwitchClient(HttpClient http, Logger logger, String clientId, String clientSecret, String staticToken) {
        this.http = http;
        this.logger = logger;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.staticToken = staticToken == null ? "" : staticToken.trim();
        this.token = staticToken;
    }

    @Override
    public StreamPlatform platform() {
        return StreamPlatform.TWITCH;
    }

    @Override
    public LiveStream fetch(String channel) throws IOException, InterruptedException {
        String accessToken = accessToken();
        if (accessToken.isEmpty()) {
            throw new IOException("не задан токен Twitch (client-secret или token)");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(STREAMS_URL + encode(channel)))
                .timeout(Duration.ofSeconds(8))
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            // Токен мог протухнуть раньше расчётного срока — сбрасываем и пробуем ещё раз.
            tokenExpiresAt = 0L;
            token = "";
            accessToken = accessToken();
            HttpRequest retry = HttpRequest.newBuilder(URI.create(STREAMS_URL + encode(channel)))
                    .timeout(Duration.ofSeconds(8))
                    .header("Client-Id", clientId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            response = http.send(retry, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            throw new IOException("Twitch ответил HTTP " + response.statusCode());
        }
        return parseStreams(response.body(), channel);
    }

    /** Разбор ответа Helix. Битый JSON и пустой {@code data} — «эфира нет». */
    static LiveStream parseStreams(String body, String channel) {
        if (body == null || body.isBlank()) {
            return LiveStream.offline();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return LiveStream.offline();
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("data") || !object.get("data").isJsonArray()) {
                return LiveStream.offline();
            }
            JsonArray data = object.getAsJsonArray("data");
            String wanted = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
            for (JsonElement element : data) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject stream = element.getAsJsonObject();
                String login = string(stream, "user_login").toLowerCase(java.util.Locale.ROOT);
                if (!wanted.isEmpty() && !login.isEmpty() && !login.equals(wanted)) {
                    continue;
                }
                // type == "live" у Helix; если поля нет, считаем эфиром (данные пришли).
                String type = string(stream, "type");
                if (!type.isEmpty() && !"live".equalsIgnoreCase(type)) {
                    return LiveStream.offline();
                }
                return LiveStream.live(string(stream, "title"), integer(stream, "viewer_count"));
            }
            return LiveStream.offline();
        } catch (RuntimeException exception) {
            return LiveStream.offline();
        }
    }

    /** Разбор ответа OAuth: токен и срок жизни в секундах. */
    static TokenData parseToken(String body) {
        if (body == null || body.isBlank()) {
            return TokenData.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return TokenData.empty();
            }
            JsonObject object = root.getAsJsonObject();
            String value = string(object, "access_token");
            int expiresIn = integer(object, "expires_in");
            return value.isEmpty() ? TokenData.empty() : new TokenData(value, Math.max(0, expiresIn));
        } catch (RuntimeException exception) {
            return TokenData.empty();
        }
    }

    /** Токен приложения и его срок жизни. */
    record TokenData(String token, int expiresInSeconds) {
        static TokenData empty() {
            return new TokenData("", 0);
        }

        boolean present() {
            return !token.isEmpty();
        }
    }

    /** Текущий токен; при необходимости запрашивает новый (синхронно, вне главного потока). */
    private synchronized String accessToken() throws IOException, InterruptedException {
        if (clientSecret.isEmpty()) {
            return staticToken;
        }
        long now = System.currentTimeMillis();
        if (!token.isEmpty() && now < tokenExpiresAt - TOKEN_MARGIN_MILLIS) {
            return token;
        }
        String url = TOKEN_URL
                + "?client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Twitch OAuth ответил HTTP " + response.statusCode());
        }
        TokenData data = parseToken(response.body());
        if (!data.present()) {
            throw new IOException("Twitch OAuth не вернул access_token");
        }
        this.token = data.token();
        this.tokenExpiresAt = now + data.expiresInSeconds() * 1000L;
        logger.fine("[Streams] Получен новый токен Twitch, действует "
                + data.expiresInSeconds() + " с.");
        return token;
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString().trim() : "";
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
