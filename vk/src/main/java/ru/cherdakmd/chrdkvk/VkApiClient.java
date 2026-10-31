package ru.cherdakmd.chrdkvk;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Клиент VK Community API v5.199 (без сторонних SDK, чистый {@link HttpClient}).
 *
 * <p>Ключ доступа сообщества передаётся исключительно в теле POST-запроса
 * ({@code application/x-www-form-urlencoded}), чтобы он никогда не попадал
 * в URI или диагностические сообщения.</p>
 */
public final class VkApiClient {

    /** Разметка ссылок ВКонтакте вида {@code [id123|Имя]} или {@code [club123|Группа]}. */
    private static final Pattern VK_WIKI_LINK = Pattern.compile("\\[(?:id|club|public)\\d+\\|([^\\]]+)]");

    public interface Transport {
        String postForm(URI uri, String formBody, Duration timeout) throws IOException, InterruptedException;

        String get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    public static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String postForm(URI uri, String formBody, Duration timeout)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("User-Agent", "CHRDK_VK/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("VK API HTTP " + response.statusCode());
            }
            return response.body();
        }

        @Override
        public String get(URI uri, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", "CHRDK_VK/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("VK Long Poll HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    public record LongPollEndpoint(String server, String key, String ts) {
    }

    public record LongPollBatch(String ts, int failed, List<JsonObject> updates) {
    }

    public record WallPost(
            long id,
            long dateSeconds,
            String text,
            boolean pinned,
            boolean markedAsAds,
            boolean repost
    ) {
    }

    private final Transport transport;
    private final String apiBaseUrl;
    private final String accessToken;
    private final String apiVersion;

    public VkApiClient(VkConfig config) {
        this(config.apiBaseUrl(), config.accessToken(), config.apiVersion(), new HttpTransport());
    }

    public VkApiClient(String apiBaseUrl, String accessToken, String apiVersion, Transport transport) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? VkConfig.DEFAULT_API_VERSION : apiVersion.trim();
        this.transport = transport;
    }

    /** Получает сервер Bots Long Poll ({@code groups.getLongPollServer}). */
    public LongPollEndpoint getLongPollServer(long groupId) throws IOException, InterruptedException {
        JsonElement response = callMethod("groups.getLongPollServer", Map.of(
                "group_id", Long.toString(groupId)
        ));
        JsonObject obj = response.getAsJsonObject();
        String server = stringOrEmpty(obj, "server");
        String key = stringOrEmpty(obj, "key");
        String ts = stringOrEmpty(obj, "ts");
        if (server.isEmpty() || key.isEmpty() || ts.isEmpty()) {
            throw new IOException("Incomplete groups.getLongPollServer response");
        }
        return new LongPollEndpoint(server, key, ts);
    }

    /** Выполняет один цикл {@code a_check} Bots Long Poll. */
    public LongPollBatch checkLongPoll(LongPollEndpoint endpoint, int waitSeconds)
            throws IOException, InterruptedException {
        int safeWait = Math.clamp(waitSeconds, 5, 25);
        String separator = endpoint.server().contains("?") ? "&" : "?";
        URI uri = URI.create(endpoint.server() + separator
                + "act=a_check&key=" + encode(endpoint.key())
                + "&ts=" + encode(endpoint.ts())
                + "&wait=" + safeWait);
        String raw = transport.get(uri, Duration.ofSeconds(safeWait + 12L));
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        int failed = root.has("failed") && !root.get("failed").isJsonNull()
                ? root.get("failed").getAsInt() : 0;
        String nextTs = stringOrEmpty(root, "ts");
        if (nextTs.isEmpty()) {
            nextTs = endpoint.ts();
        }
        List<JsonObject> updates = new ArrayList<>();
        if (root.has("updates") && root.get("updates").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("updates")) {
                if (element.isJsonObject()) {
                    updates.add(element.getAsJsonObject());
                }
            }
        }
        return new LongPollBatch(nextTs, failed, updates);
    }

    /** Отправляет текстовое сообщение пользователю или в беседу ({@code messages.send}). */
    public boolean sendMessage(long peerId, String text) throws IOException, InterruptedException {
        if (peerId <= 0L || text == null || text.isBlank()) {
            return false;
        }
        String cropped = text.length() > 3_800 ? text.substring(0, 3_800) + "…" : text;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("peer_id", Long.toString(peerId));
        params.put("random_id", Long.toString(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE)));
        params.put("message", cropped);
        params.put("dont_parse_links", "1");
        callMethod("messages.send", params);
        return true;
    }

    /** Проверяет подписку пользователя на сообщество ({@code groups.isMember}). */
    public boolean isGroupMember(long groupId, long userId) throws IOException, InterruptedException {
        JsonElement response = callMethod("groups.isMember", Map.of(
                "group_id", Long.toString(groupId),
                "user_id", Long.toString(userId)
        ));
        if (response.isJsonPrimitive()) {
            return response.getAsInt() == 1;
        }
        if (response.isJsonObject() && response.getAsJsonObject().has("member")) {
            return response.getAsJsonObject().get("member").getAsInt() == 1;
        }
        return false;
    }

    /** Возвращает множество активных подписчиков VK Donut сообщества ({@code groups.getMembers?filter=donut}). */
    public Set<Long> fetchDonutMemberIds(long groupId) throws IOException, InterruptedException {
        Set<Long> ids = new LinkedHashSet<>();
        int offset = 0;
        int pageSize = 1_000;
        for (int page = 0; page < 20; page++) {
            JsonElement response = callMethod("groups.getMembers", Map.of(
                    "group_id", Long.toString(groupId),
                    "filter", "donut",
                    "count", Integer.toString(pageSize),
                    "offset", Integer.toString(offset)
            ));
            JsonObject obj = response.getAsJsonObject();
            int total = obj.has("count") ? obj.get("count").getAsInt() : 0;
            JsonArray items = obj.has("items") && obj.get("items").isJsonArray()
                    ? obj.getAsJsonArray("items") : new JsonArray();
            if (items.isEmpty()) {
                break;
            }
            for (JsonElement item : items) {
                if (item.isJsonPrimitive()) {
                    ids.add(item.getAsLong());
                } else if (item.isJsonObject() && item.getAsJsonObject().has("id")) {
                    ids.add(item.getAsJsonObject().get("id").getAsLong());
                }
            }
            offset += items.size();
            if (offset >= total) {
                break;
            }
        }
        return ids;
    }

    /** Читает последние записи стены сообщества ({@code wall.get}). */
    public List<WallPost> fetchWallPosts(long groupId, int count) throws IOException, InterruptedException {
        int safeCount = Math.clamp(count, 1, 20);
        JsonElement response = callMethod("wall.get", Map.of(
                "owner_id", Long.toString(-Math.abs(groupId)),
                "count", Integer.toString(safeCount),
                "filter", "owner"
        ));
        JsonObject obj = response.getAsJsonObject();
        List<WallPost> posts = new ArrayList<>();
        if (obj.has("items") && obj.get("items").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("items")) {
                if (element.isJsonObject()) {
                    WallPost parsed = parseWallPost(element.getAsJsonObject());
                    if (parsed.id() > 0L) {
                        posts.add(parsed);
                    }
                }
            }
        }
        return posts;
    }

    public static WallPost parseWallPost(JsonObject obj) {
        long id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsLong() : 0L;
        long date = obj.has("date") && !obj.get("date").isJsonNull() ? obj.get("date").getAsLong() : 0L;
        String text = stringOrEmpty(obj, "text");
        boolean pinned = obj.has("is_pinned") && !obj.get("is_pinned").isJsonNull()
                && obj.get("is_pinned").getAsInt() == 1;
        boolean ads = obj.has("marked_as_ads") && !obj.get("marked_as_ads").isJsonNull()
                && obj.get("marked_as_ads").getAsInt() == 1;
        boolean repost = obj.has("copy_history") && obj.get("copy_history").isJsonArray()
                && !obj.getAsJsonArray("copy_history").isEmpty();
        return new WallPost(id, date, text, pinned, ads, repost);
    }

    /**
     * Получает имя и фамилию пользователя ВКонтакте ({@code users.get})
     * для отображения непривязанных участников беседы, когда {@code require-linked=false}.
     */
    public String fetchUserDisplayName(long userId) throws IOException, InterruptedException {
        if (userId <= 0L) {
            return "ВК";
        }
        JsonElement response = callMethod("users.get", Map.of("user_ids", Long.toString(userId)));
        if (response.isJsonArray() && !response.getAsJsonArray().isEmpty()) {
            JsonElement firstEl = response.getAsJsonArray().get(0);
            if (firstEl.isJsonObject()) {
                JsonObject u = firstEl.getAsJsonObject();
                String first = stringOrEmpty(u, "first_name");
                String last = stringOrEmpty(u, "last_name");
                String full = (first + " " + last).trim();
                if (!full.isEmpty()) {
                    return full;
                }
            }
        }
        return "id" + userId;
    }

    /**
     * Обезвреживает массовые пуш-упоминания беседы ВКонтакте ({@code @all}, {@code @online},
     * {@code @everyone}, {@code @тут}, {@code @все}, {@code *all}, {@code [club...|...]}),
     * чтобы игрок из Minecraft не мог вызвать массовый пинг участников чата ВК.
     */
    public static String sanitizeForVkChat(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String cleaned = VK_WIKI_LINK.matcher(rawText).replaceAll("$1")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\u00a7', ' ')
                .replaceAll("(?iuU)([@*])(all|online|everyone|here|тут|все|всё)(?![\\p{L}\\p{N}_])", "$1\u200B$2")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 350 ? cleaned.substring(0, 350).trim() + "…" : cleaned;
    }

    /**
     * Извлекает безопасный однострочный текст сообщения из беседы ВК для отправки в игровой чат,
     * раскрывая {@code [id123|Имя]} и добавляя краткие пометки вложений ({@code [фото]}, {@code [стикер]} и т.д.).
     */
    public static String extractGroupChatText(JsonObject message, int maxLength) {
        if (message == null) {
            return "";
        }
        String rawText = stringOrEmpty(message, "text");
        String cleaned = VK_WIKI_LINK.matcher(rawText).replaceAll("$1")
                .replace('<', '‹')
                .replace('>', '›')
                .replace('\u00a7', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        List<String> markers = new ArrayList<>(3);
        if (message.has("attachments") && message.get("attachments").isJsonArray()) {
            for (JsonElement attEl : message.getAsJsonArray("attachments")) {
                if (!attEl.isJsonObject()) continue;
                String type = stringOrEmpty(attEl.getAsJsonObject(), "type");
                String label = switch (type) {
                    case "photo" -> "[фото]";
                    case "sticker" -> "[стикер]";
                    case "audio_message" -> "[голосовое]";
                    case "video" -> "[видео]";
                    case "doc" -> "[файл]";
                    case "wall" -> "[запись]";
                    case "poll" -> "[опрос]";
                    default -> "";
                };
                if (!label.isEmpty() && !markers.contains(label) && markers.size() < 3) {
                    markers.add(label);
                }
            }
        }
        if (message.has("reply_message") && message.get("reply_message").isJsonObject() && markers.size() < 3) {
            markers.add("[ответ]");
        }

        String combined;
        if (cleaned.isEmpty()) {
            combined = String.join(" ", markers);
        } else if (markers.isEmpty()) {
            combined = cleaned;
        } else {
            combined = cleaned + " " + String.join(" ", markers);
        }
        combined = combined.trim();
        if (combined.isEmpty()) {
            return "";
        }
        int limit = Math.clamp(maxLength, 40, 400);
        return combined.length() <= limit ? combined : combined.substring(0, limit - 1).trim() + "…";
    }

    /**
     * Готовит безопасный однострочный анонс записи стены без сырой MiniMessage-разметки:
     * раскрывает {@code [id123|Имя]} и экранирует угловые скобки.
     */
    public static String formatWallPreview(String rawText, int maxLength) {
        if (rawText == null || rawText.isBlank()) {
            return "Откройте запись по ссылке";
        }
        String cleaned = VK_WIKI_LINK.matcher(rawText).replaceAll("$1")
                .replace('<', '‹')
                .replace('>', '›')
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "Откройте запись по ссылке";
        }
        int limit = Math.clamp(maxLength, 20, 300);
        if (cleaned.length() <= limit) {
            return cleaned;
        }
        return cleaned.substring(0, limit - 1).trim() + "…";
    }

    private JsonElement callMethod(String method, Map<String, String> params)
            throws IOException, InterruptedException {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!form.isEmpty()) form.append('&');
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        if (!form.isEmpty()) form.append('&');
        form.append("access_token=").append(encode(accessToken));
        form.append("&v=").append(encode(apiVersion));

        URI uri = URI.create(apiBaseUrl + method);
        String body = transport.postForm(uri, form.toString(), Duration.ofSeconds(15));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject err = root.getAsJsonObject("error");
            int code = err.has("error_code") ? err.get("error_code").getAsInt() : -1;
            String msg = stringOrEmpty(err, "error_msg");
            throw new IOException("VK API error " + code + " in " + method + ": " + msg);
        }
        if (!root.has("response")) {
            throw new IOException("VK API missing response field in " + method);
        }
        return root.get("response");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String stringOrEmpty(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(field);
        return el.isJsonPrimitive() ? el.getAsString().trim() : "";
    }
}
