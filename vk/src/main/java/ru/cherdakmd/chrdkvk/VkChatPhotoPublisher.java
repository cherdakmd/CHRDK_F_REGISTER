package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Отправка PNG-карточки в беседу сообщества: {@code photos.getMessagesUploadServer}
 * → multipart → {@code photos.saveMessagesPhoto} → {@code messages.send} с
 * вложением. В отличие от загрузки на стену, этот контур работает с ключом
 * сообщества и не требует {@code community.photo-user-token}.
 *
 * <p>Схема повторяет уже отработанный в {@code tools/social/publisher.py}
 * контур новостей: адрес загрузки проверяется тем же allowlist доменов VK,
 * а {@code random_id} стабилен для guid поста, чтобы ретрай не создал дубль.</p>
 */
public final class VkChatPhotoPublisher {

    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final VkApiClient api;
    private final long groupId;
    private final long peerId;

    public VkChatPhotoPublisher(VkApiClient api, long groupId, long peerId) {
        this.api = api;
        this.groupId = groupId;
        this.peerId = peerId;
    }

    /** Возвращает true, если {@code messages.send} принял сообщение с фото. */
    public boolean send(byte[] png, String text, String guid)
            throws IOException, InterruptedException {
        if (peerId < VkConfig.MIN_CONVERSATION_PEER_ID) {
            throw new IOException("Не задан peer_id беседы ВК для карточки");
        }
        validatePng(png);

        JsonObject server = api.call("photos.getMessagesUploadServer",
                Map.of("peer_id", Long.toString(peerId))).getAsJsonObject();
        URI uploadUri = VkWallPublisher.validateUploadUrl(required(server, "upload_url"));

        String boundary = "chrdk" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = VkWallPublisher.encodeMultipart(boundary, png);
        String raw = api.postMultipart(uploadUri, multipart,
                "multipart/form-data; boundary=" + boundary, UPLOAD_TIMEOUT);

        JsonObject uploaded;
        try {
            uploaded = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException bad) {
            throw new IOException("VK вернул некорректный ответ загрузчика фото беседы", bad);
        }
        String photo = required(uploaded, "photo");
        String hash = required(uploaded, "hash");
        String serverId = required(uploaded, "server");

        JsonElement saved = api.call("photos.saveMessagesPhoto", Map.of(
                "photo", photo,
                "hash", hash,
                "server", serverId));
        if (!saved.isJsonArray() || saved.getAsJsonArray().isEmpty()) {
            throw new IOException("VK не подтвердил сохранение фото для беседы");
        }
        JsonObject item = saved.getAsJsonArray().get(0).getAsJsonObject();
        if (!item.has("owner_id") || !item.has("id")) {
            throw new IOException("VK не вернул owner_id/id фото для беседы");
        }
        long ownerId = item.get("owner_id").getAsLong();
        long photoId = item.get("id").getAsLong();
        if (ownerId == 0L || photoId <= 0L) {
            throw new IOException("VK вернул пустой идентификатор фото для беседы");
        }
        StringBuilder attachment = new StringBuilder("photo")
                .append(ownerId).append('_').append(photoId);
        if (item.has("access_key") && !item.get("access_key").isJsonNull()) {
            String key = item.get("access_key").getAsString();
            if (!key.isBlank() && key.matches("[A-Za-z0-9_-]{1,256}")) {
                attachment.append('_').append(key);
            }
        }

        String body = text == null || text.isBlank() ? "CHRDK_VK" : text;
        String seed = groupId + ":" + peerId + ":"
                + (guid == null || guid.isBlank() ? UUID.randomUUID().toString() : guid);
        return api.sendMessage(peerId, groupId, body, attachment.toString(), null,
                stableRandomId(seed));
    }

    private static void validatePng(byte[] png) throws IOException {
        if (png == null || png.length < 8 || png.length > VkWallPublisher.MAX_PNG_BYTES) {
            throw new IOException("Некорректный размер PNG-карточки для беседы");
        }
        if ((png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G'
                || png[4] != 13 || png[5] != 10 || png[6] != 26 || png[7] != 10) {
            throw new IOException("Карточка беседы должна быть PNG");
        }
    }

    private static String required(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || value.getAsString().isBlank()) {
            throw new IOException("VK не вернул поле " + key + " для загрузки фото беседы");
        }
        return value.getAsString();
    }

    /** Стабильный положительный random_id, привязанный к guid поста. */
    static int stableRandomId(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            long value = ((long) (digest[0] & 0xFF) << 24)
                    | ((long) (digest[1] & 0xFF) << 16)
                    | ((long) (digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFFL);
            return (int) (Math.floorMod(value, 2_147_483_646L) + 1);
        } catch (NoSuchAlgorithmException impossible) {
            return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        }
    }
}
