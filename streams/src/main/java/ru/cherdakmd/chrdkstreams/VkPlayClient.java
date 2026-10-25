package ru.cherdakmd.chrdkstreams;

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

/**
 * VK Play Live (сейчас VK Видео Live): «идёт ли эфир у канала».
 *
 * <p>Используется публичный эндпоинт канала, который отдаёт данные без токена:
 * {@code https://api.vkplay.live/v1/blog/<канал>/public_video_stream}. В ответе
 * есть {@code isOnline}, {@code isEnded}, {@code title} и {@code count.viewers}.
 * Неизвестный канал отвечает {@code {"error":"blog_not_found", ...}} — это не
 * ошибка сети, а «канала нет», поэтому возвращаем «эфира нет».</p>
 *
 * <p>Адрес вынесен в конфиг: если VK сменит путь, администратор поправит строку
 * без обновления плагина. Разбор ответа — статический, проверяется тестами.</p>
 */
final class VkPlayClient implements StreamClient {

    static final String DEFAULT_API_URL =
            "https://api.vkplay.live/v1/blog/%channel%/public_video_stream";

    private final HttpClient http;
    private final String apiUrl;

    VkPlayClient(HttpClient http, String apiUrl) {
        this.http = http;
        this.apiUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim();
    }

    @Override
    public StreamPlatform platform() {
        return StreamPlatform.VKPLAY;
    }

    /** Адрес запроса для канала: подстановка {@code %channel%} и URL-кодирование. */
    String urlFor(String channel) {
        String template = apiUrl.contains("%channel%")
                ? apiUrl
                : apiUrl + (apiUrl.endsWith("/") ? "" : "/") + "%channel%";
        return template.replace("%channel%", URLEncoder.encode(channel, StandardCharsets.UTF_8));
    }

    @Override
    public LiveStream fetch(String channel) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(urlFor(channel)))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Referer", "https://vkplay.live/")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // 404 — канала нет; это штатный ответ для опечатки в нике.
        if (response.statusCode() == 404) {
            return LiveStream.offline();
        }
        if (response.statusCode() != 200) {
            throw new IOException("VK Play Live ответил HTTP " + response.statusCode());
        }
        return parseStream(response.body());
    }

    /**
     * Разбор ответа {@code public_video_stream}.
     *
     * <p>Эфир идёт, если {@code isOnline: true} и {@code isEnded != true}:
     * запись завершённого эфира может оставаться доступной, но наградой не считается.</p>
     */
    static LiveStream parseStream(String body) {
        if (body == null || body.isBlank()) {
            return LiveStream.offline();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return LiveStream.offline();
            }
            JsonObject object = root.getAsJsonObject();
            if (object.has("error")) {
                return LiveStream.offline();
            }
            boolean online = bool(object, "isOnline");
            boolean ended = bool(object, "isEnded");
            if (!online || ended) {
                return LiveStream.offline();
            }
            int viewers = 0;
            if (object.has("count") && object.get("count").isJsonObject()) {
                JsonObject count = object.getAsJsonObject("count");
                viewers = integer(count, "viewers");
            }
            return LiveStream.live(string(object, "title"), viewers);
        } catch (RuntimeException exception) {
            return LiveStream.offline();
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    && object.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString().trim() : "";
    }
}
