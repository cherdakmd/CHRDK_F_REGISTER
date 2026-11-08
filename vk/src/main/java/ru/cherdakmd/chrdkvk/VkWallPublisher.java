package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Публикация дайджеста на стену сообщества: photos → wall.post.
 *
 * <p>Цепочка целиком выполняется плагином на сервере владельца:
 * {@code photos.getWallUploadServer} → multipart-загрузка PNG →
 * {@code photos.saveWallPhoto} → {@code wall.post} с {@code from_group=1}
 * и отрицательным {@code owner_id}. Ни GitHub Actions, ни служебный чат,
 * ни внешний ledger в этой цепочке не участвуют.</p>
 *
 * <p>Адрес загрузки проверяется по allowlist доменов VK (как в Python-контуре
 * соцсетей): ответ API не может заставить плагин отправить файл куда-то ещё.</p>
 */
public final class VkWallPublisher {

    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PNG_BYTES = 5 * 1024 * 1024;

    private final VkApiClient api;
    private final long groupId;

    public VkWallPublisher(VkApiClient api, long groupId) {
        this.api = api;
        this.groupId = groupId;
    }

    /** Возвращает {@code post_id} созданной записи. */
    public long publish(byte[] png, String text, String guid) throws IOException, InterruptedException {
        if (groupId <= 0L) {
            throw new IOException("Не задан community.group-id сообщества");
        }
        if (png == null || png.length < 8 || png.length > MAX_PNG_BYTES) {
            throw new IOException("Некорректная PNG-карточка дайджеста");
        }
        if (text == null || text.isBlank()) {
            throw new IOException("Пустой текст записи на стене");
        }
        String safeGuid = guid == null || guid.isBlank() ? UUID.randomUUID().toString() : guid;

        JsonObject uploadServer = api.call("photos.getWallUploadServer",
                Map.of("group_id", Long.toString(groupId))).getAsJsonObject();
        URI uploadUri = validateUploadUrl(required(uploadServer, "upload_url"));

        String boundary = "chrdk" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = encodeMultipart(boundary, png);
        String raw = api.postMultipart(uploadUri, multipart,
                "multipart/form-data; boundary=" + boundary, UPLOAD_TIMEOUT);
        JsonObject uploaded;
        try {
            uploaded = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException badJson) {
            throw new IOException("VK вернул некорректный ответ загрузки фото", badJson);
        }

        JsonElement saved = api.call("photos.saveWallPhoto", Map.of(
                "group_id", Long.toString(groupId),
                "photo", required(uploaded, "photo"),
                "hash", required(uploaded, "hash"),
                "server", required(uploaded, "server")));
        if (!saved.isJsonArray() || saved.getAsJsonArray().isEmpty()) {
            throw new IOException("VK не сохранил фото для стены");
        }
        JsonObject photo = saved.getAsJsonArray().get(0).getAsJsonObject();
        String attachment = "photo" + required(photo, "owner_id") + "_" + required(photo, "id");
        if (photo.has("access_key") && !photo.get("access_key").isJsonNull()) {
            attachment += "_" + photo.get("access_key").getAsString();
        }

        JsonObject posted = api.call("wall.post", Map.of(
                "owner_id", "-" + groupId,
                "from_group", "1",
                "message", text,
                "attachments", attachment,
                "guid", safeGuid)).getAsJsonObject();
        if (!posted.has("post_id") || posted.get("post_id").isJsonNull()) {
            throw new IOException("VK не подтвердил публикацию записи на стене");
        }
        return posted.get("post_id").getAsLong();
    }

    /** Разрешаем только HTTPS-хосты доменов загрузки VK. */
    static URI validateUploadUrl(String raw) throws IOException {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (RuntimeException badUri) {
            throw new IOException("VK вернул некорректный адрес загрузки фото", badUri);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = false;
        for (String domain : new String[]{"vk.com", "userapi.com", "vkuserphoto.ru"}) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                allowedHost = true;
                break;
            }
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null
                || !allowedHost) {
            throw new IOException("VK вернул недопустимый адрес загрузки фото");
        }
        return uri;
    }

    private static byte[] encodeMultipart(String boundary, byte[] png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"photo\"; filename=\"chrdk-digest.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String required(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw new IOException("VK не вернул поле " + key);
        }
        return value.getAsString();
    }
}
