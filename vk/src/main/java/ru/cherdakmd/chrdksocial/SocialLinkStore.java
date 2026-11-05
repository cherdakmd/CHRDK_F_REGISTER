package ru.cherdakmd.chrdksocial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Единый реестр привязок Minecraft ↔ ВК/MAX ({@code social-links.json}).
 *
 * <p>Один игровой аккаунт = одна привязка к одному из мессенджеров; один профиль
 * мессенджера = не более {@code max-accounts-per-profile} игроков. Файл защищён
 * контрольной суммой SHA-256 по каноническому JSON и пишется атомарно. При
 * расхождении копия уходит в {@code .broken}, оригинал не затирается.</p>
 */
public final class SocialLinkStore {

    public static final int SCHEMA = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().create();

    public enum LinkStatus { LINKED, PLAYER_ALREADY_LINKED, PROFILE_LIMIT_REACHED, INVALID_ARGUMENT }

    public record Binding(String provider, long externalId, String externalName, long linkedAt) { }

    private static final class StoredBinding {
        String provider = "";
        long externalId;
        String externalName = "";
        long linkedAt;
    }

    private static final class Document {
        int schema = SCHEMA;
        long rolloutStartedAt;
        long emergencyUntil;
        String lastNewsTag = "";
        Map<String, StoredBinding> links = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, String> rewarded = new LinkedHashMap<>();
        Map<String, Long> discoveredChats = new LinkedHashMap<>();
        /** Первый вход после установки плагина: нужен для личного переходного срока. */
        Map<String, Long> firstSeen = new LinkedHashMap<>();
        /** Подтверждённый донат с бессрочным доступом: ник → «момент|источник». */
        Map<String, String> donors = new LinkedHashMap<>();
        String maxChatId = "0";
        String maxNewsChatId = "0";
        String maxBridgeChatId = "0";
    }

    private final Path file;
    private final Logger logger;
    private Document doc = new Document();
    private boolean dirty;
    private boolean loadFailed;

    public SocialLinkStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("social-links.json");
        this.logger = logger;
    }

    public static String canonical(String name) {
        return SocialLinkCodeManager.canonical(name);
    }

    public static String ownerKey(String provider, long externalId) {
        return provider.trim().toLowerCase(java.util.Locale.ROOT) + ":" + externalId;
    }

    public synchronized void load() {
        loadFailed = false;
        if (!Files.isRegularFile(file)) {
            doc = new Document();
            doc.rolloutStartedAt = System.currentTimeMillis();
            dirty = true;
            saveNow();
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String checksum = root.has("checksum") && !root.get("checksum").isJsonNull()
                    ? root.get("checksum").getAsString() : "";
            String expected = checksumOf(root);
            if (!checksum.isEmpty() && !MessageDigest.isEqual(
                    checksum.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                preserveBroken();
                loadFailed = true;
                logger.severe("[CHRDK_SOCIAL] social-links.json не прошёл проверку SHA-256. "
                        + "Оригинал сохранён в .broken, включён аварийный режим (см. /social emergency).");
                doc = new Document();
                doc.rolloutStartedAt = System.currentTimeMillis();
                return;
            }
            Document loaded = GSON.fromJson(raw, Document.class);
            if (loaded == null) loaded = new Document();
            if (loaded.links == null) loaded.links = new LinkedHashMap<>();
            if (loaded.owners == null) loaded.owners = new LinkedHashMap<>();
            if (loaded.rewarded == null) loaded.rewarded = new LinkedHashMap<>();
            if (loaded.discoveredChats == null) loaded.discoveredChats = new LinkedHashMap<>();
            if (loaded.firstSeen == null) loaded.firstSeen = new LinkedHashMap<>();
            if (loaded.donors == null) loaded.donors = new LinkedHashMap<>();
            if (loaded.rolloutStartedAt <= 0L) loaded.rolloutStartedAt = System.currentTimeMillis();
            if (loaded.lastNewsTag == null) loaded.lastNewsTag = "";
            loaded.schema = SCHEMA;
            doc = loaded;
            dirty = checksum.isEmpty();
            if (dirty) saveNow();
        } catch (Exception exception) {
            preserveBroken();
            loadFailed = true;
            doc = new Document();
            doc.rolloutStartedAt = System.currentTimeMillis();
            logger.severe("[CHRDK_SOCIAL] Не удалось прочитать social-links.json: "
                    + exception.getClass().getSimpleName() + ". Включён аварийный режим.");
        }
    }

    /** Повреждение storage не молча обходит обязательную привязку: режим виден сервису. */
    public synchronized boolean loadFailed() {
        return loadFailed;
    }

    public synchronized void acknowledgeCorruption(long emergencyUntil) {
        doc.emergencyUntil = emergencyUntil;
        loadFailed = false;
        dirty = true;
        saveNow();
    }

    private void preserveBroken() {
        try {
            Files.copy(file, file.resolveSibling("social-links.json.broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // Оригинал остаётся на месте; перезапись всё равно запрещена до acknowledgeCorruption().
        }
    }

    private static String checksumOf(JsonObject root) {
        JsonObject copy = root.deepCopy();
        copy.remove("checksum");
        String canonical = CANONICAL.toJson(copy);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public synchronized void saveNow() {
        if (loadFailed) return;
        JsonObject root = JsonParser.parseString(GSON.toJson(doc)).getAsJsonObject();
        root.addProperty("checksum", checksumOf(root));
        if (SafeJsonWriter.write(file, GSON.toJson(root), logger, "[CHRDK_SOCIAL]")) dirty = false;
    }

    public synchronized void saveIfDirty() {
        if (dirty) saveNow();
    }

    public synchronized long rolloutStartedAt() {
        return doc.rolloutStartedAt;
    }

    public synchronized long emergencyUntil() {
        return doc.emergencyUntil;
    }

    public synchronized void setEmergencyUntil(long until) {
        doc.emergencyUntil = Math.max(0L, until);
        dirty = true;
        saveNow();
    }

    public synchronized String lastNewsTag() {
        return doc.lastNewsTag;
    }

    public synchronized void setLastNewsTag(String tag) {
        doc.lastNewsTag = tag == null ? "" : tag.trim();
        dirty = true;
        saveNow();
    }

    public synchronized boolean isLinked(String playerName) {
        return doc.links.containsKey(canonical(playerName));
    }

    public synchronized Optional<Binding> binding(String playerName) {
        StoredBinding stored = doc.links.get(canonical(playerName));
        if (stored == null) return Optional.empty();
        return Optional.of(new Binding(stored.provider, stored.externalId, stored.externalName, stored.linkedAt));
    }

    public synchronized Optional<String> ownerOf(String provider, long externalId) {
        return Optional.ofNullable(doc.owners.get(ownerKey(provider, externalId)));
    }

    public synchronized LinkStatus link(String playerName, String provider, long externalId,
                                        String externalName, int maxAccounts, long now) {
        String key = canonical(playerName);
        String owner = ownerKey(provider, externalId);
        if (key.isEmpty() || externalId <= 0L || !(provider.equals("vk") || provider.equals("max"))) {
            return LinkStatus.INVALID_ARGUMENT;
        }
        if (doc.links.containsKey(key)) return LinkStatus.PLAYER_ALREADY_LINKED;
        int used = 0;
        for (StoredBinding existing : doc.links.values()) {
            if (ownerKey(existing.provider, existing.externalId).equals(owner)) used++;
        }
        if (used >= Math.max(1, maxAccounts)) return LinkStatus.PROFILE_LIMIT_REACHED;
        StoredBinding created = new StoredBinding();
        created.provider = provider;
        created.externalId = externalId;
        created.externalName = externalName == null ? "" : externalName.trim();
        created.linkedAt = now;
        doc.links.put(key, created);
        doc.owners.put(owner, key);
        dirty = true;
        saveNow();
        return LinkStatus.LINKED;
    }

    public synchronized Optional<Binding> unlink(String playerName) {
        String key = canonical(playerName);
        StoredBinding removed = doc.links.remove(key);
        if (removed == null) return Optional.empty();
        doc.owners.remove(ownerKey(removed.provider, removed.externalId), key);
        dirty = true;
        saveNow();
        return Optional.of(new Binding(removed.provider, removed.externalId, removed.externalName, removed.linkedAt));
    }

    public synchronized boolean rewardClaimed(String playerName) {
        return doc.rewarded.containsKey(canonical(playerName));
    }

    public synchronized void markRewarded(String playerName, long now) {
        doc.rewarded.put(canonical(playerName), Long.toString(now));
        dirty = true;
        saveNow();
    }

    public synchronized void recordChat(String chatId, long now) {
        if (chatId == null || chatId.isBlank()) return;
        doc.discoveredChats.put(chatId.trim(), now);
        dirty = true;
        saveNow();
    }

    public synchronized List<String> discoveredChats() {
        return List.copyOf(doc.discoveredChats.keySet());
    }

    public synchronized long chatId(String kind) {
        String raw = switch (kind) {
            case "news" -> doc.maxNewsChatId;
            case "bridge" -> doc.maxBridgeChatId;
            default -> doc.maxChatId;
        };
        try {
            return Long.parseLong(raw == null ? "0" : raw.trim());
        } catch (NumberFormatException bad) {
            return 0L;
        }
    }

    public synchronized void setChatId(String kind, long chatId) {
        String value = Long.toString(Math.max(0L, chatId));
        switch (kind) {
            case "news" -> doc.maxNewsChatId = value;
            case "bridge" -> doc.maxBridgeChatId = value;
            default -> doc.maxChatId = value;
        }
        recordChat(value, System.currentTimeMillis());
        dirty = true;
        saveNow();
    }

    /* --------------- гибридный grace и доступ через подтверждённый донат --------------- */

    /** Момент первого входа после установки; {@code 0} — игрок ещё не заходил. */
    public synchronized long firstSeenAt(String playerName) {
        Long value = doc.firstSeen.get(canonical(playerName));
        return value == null ? 0L : value;
    }

    /** Фиксируется один раз и не переписывается при следующих входах. */
    public synchronized void recordFirstSeen(String playerName, long now) {
        String key = canonical(playerName);
        if (key.isEmpty()) return;
        if (doc.firstSeen.putIfAbsent(key, Math.max(1L, now)) == null) {
            dirty = true;
            saveNow();
        }
    }

    public synchronized boolean donorGranted(String playerName) {
        return doc.donors.containsKey(canonical(playerName));
    }

    public synchronized String donorSource(String playerName) {
        return doc.donors.getOrDefault(canonical(playerName), "");
    }

    public synchronized void grantDonor(String playerName, String source, long now) {
        String key = canonical(playerName);
        if (key.isEmpty()) return;
        doc.donors.put(key, Math.max(1L, now) + "|" + (source == null ? "" : source.trim()));
        dirty = true;
        saveNow();
    }

    public synchronized boolean revokeDonor(String playerName) {
        if (doc.donors.remove(canonical(playerName)) == null) return false;
        dirty = true;
        saveNow();
        return true;
    }

    public synchronized List<String> donors() {
        return List.copyOf(doc.donors.keySet());
    }

    public synchronized int donorCount() {
        return doc.donors.size();
    }

    public synchronized int linkCount() {
        return doc.links.size();
    }
}
