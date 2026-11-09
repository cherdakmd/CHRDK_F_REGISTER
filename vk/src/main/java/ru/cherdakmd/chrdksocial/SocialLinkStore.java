package ru.cherdakmd.chrdksocial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.PlayerDataFiles;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Единый реестр привязок Minecraft ↔ ВК/MAX.
 *
 * <p>Персональные данные лежат в отдельном редактируемом
 * {@code players/social.yml}: связи, первый вход, донатный доступ и награда.
 * Глобальные настройки (rollout, аварийный режим, чаты, новости) остаются в
 * {@code social-links.json} схемы 2 с SHA-256 по каноническому JSON. При первом
 * запуске этой версии старые поля выносятся в YAML, исходный JSON уходит в
 * backup с меткой времени, а затем перезаписывается уже без данных игроков.</p>
 *
 * <p>Повреждение любого из двух файлов не молча обходит обязательную привязку:
 * копия уходит в {@code .broken}, включается аварийный режим. Один игровой
 * аккаунт = одна привязка; один профиль мессенджера = не более
 * {@code max-accounts-per-profile} игроков.</p>
 */
public final class SocialLinkStore {

    public static final int SCHEMA = 2;
    /** Схема отдельного YAML с данными игроков; маркер записан и в global JSON. */
    public static final int PLAYER_DATA_SCHEMA = 1;
    private static final String TAG = "[CHRDK_SOCIAL]";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().create();

    public enum LinkStatus { LINKED, PLAYER_ALREADY_LINKED, PROFILE_LIMIT_REACHED, INVALID_ARGUMENT }

    public record Binding(String provider, long externalId, String externalName, long linkedAt) { }

    /** Глобальная часть состояния: без связей, firstSeen, donors и rewarded. */
    private static final class GlobalDocument {
        int schema = SCHEMA;
        int playerDataSchema;
        long rolloutStartedAt;
        long emergencyUntil;
        String lastNewsTag = "";
        Map<String, Long> discoveredChats = new LinkedHashMap<>();
        String maxChatId = "0";
        String maxNewsChatId = "0";
        String maxBridgeChatId = "0";
    }

    /** Поля старого social-links.json: нужны только для одноразовой миграции. */
    private static final class LegacyBinding {
        String provider = "";
        long externalId;
        String externalName = "";
        long linkedAt;
    }

    private static final class LegacyPlayers {
        Map<String, LegacyBinding> links = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, String> rewarded = new LinkedHashMap<>();
        Map<String, Long> firstSeen = new LinkedHashMap<>();
        Map<String, String> donors = new LinkedHashMap<>();
    }

    private record GlobalLoad(GlobalDocument document, JsonObject root, LegacyPlayers legacy,
                              boolean checksumPresent, boolean hasLegacyFields, boolean dirty,
                              boolean failed) { }

    private final Path dataFolder;
    private final Path file;
    private final SocialPlayerStore playerStore;
    private final Logger logger;
    private GlobalDocument global = new GlobalDocument();
    private SocialPlayerStore.Document players = new SocialPlayerStore.Document();
    private boolean globalDirty;
    private boolean playersDirty;
    private boolean loadFailed;
    /** Backup не создался: не перезаписываем legacy JSON до следующего старта. */
    private boolean globalFrozen;
    /** Битый players/social.yml не затираем даже после подтверждения аварийного режима. */
    private boolean playersFrozen;

    public SocialLinkStore(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.file = dataFolder.resolve("social-links.json");
        this.playerStore = new SocialPlayerStore(dataFolder, logger);
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public Path playersFile() {
        return playerStore.file();
    }

    public static String canonical(String name) {
        return SocialLinkCodeManager.canonical(name);
    }

    public static String ownerKey(String provider, long externalId) {
        return provider.trim().toLowerCase(java.util.Locale.ROOT) + ":" + externalId;
    }

    private static GlobalDocument newGlobal() {
        GlobalDocument document = new GlobalDocument();
        document.playerDataSchema = PLAYER_DATA_SCHEMA;
        document.rolloutStartedAt = System.currentTimeMillis();
        return document;
    }

    public synchronized void load() {
        loadFailed = false;
        globalFrozen = false;
        playersFrozen = false;
        globalDirty = false;
        playersDirty = false;
        global = newGlobal();
        players = new SocialPlayerStore.Document();

        // Читаем оба файла даже при ошибке global JSON: валидный players/social.yml
        // не должен исчезнуть из-за автоматического подтверждения аварийного режима.
        GlobalLoad loaded = readGlobal();
        SocialPlayerStore.Result playerResult = playerStore.load();
        if (loaded.failed() || playerResult.failed()) {
            loadFailed = true;
            playersFrozen = playerResult.failed();
            if (!loaded.failed()) global = loaded.document();
            if (!playerResult.failed()) players = playerResult.document();
            playersDirty = playerResult.missing() && !playersFrozen;
            return;
        }
        global = loaded.document();
        players = playerResult.document();
        globalDirty = loaded.dirty();
        playersDirty = playerResult.missing();

        boolean needsMigration = loaded.hasLegacyFields()
                || global.playerDataSchema < PLAYER_DATA_SCHEMA;
        if (needsMigration) {
            if (playerResult.missing()) {
                SocialPlayerStore.Document migrated = fromLegacy(loaded.legacy());
                if (!playerStore.save(migrated)) {
                    loadFailed = true;
                    globalFrozen = true;
                    logger.severe(TAG + " Не удалось создать players/social.yml. "
                            + "Оригинальный social-links.json оставлен без изменений; включён аварийный режим.");
                    return;
                }
                players = migrated;
                playersDirty = false;
            }
            pruneLegacyGlobal(loaded.root());
        }

        PlayerDataFiles.writeReadme(dataFolder, SocialPlayerStore.README, logger, TAG);
        saveIfDirty();
    }

    private GlobalLoad readGlobal() {
        if (!Files.isRegularFile(file)) {
            GlobalDocument document = newGlobal();
            JsonObject root = GSON.toJsonTree(document).getAsJsonObject();
            return new GlobalLoad(document, root, new LegacyPlayers(), false, false, true, false);
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
                logger.severe(TAG + " social-links.json не прошёл проверку SHA-256. "
                        + "Оригинал сохранён в .broken, включён аварийный режим (см. /social emergency).");
                return new GlobalLoad(null, null, null, false, false, false, true);
            }
            GlobalDocument document = CANONICAL.fromJson(root, GlobalDocument.class);
            if (document == null) document = newGlobal();
            LegacyPlayers legacy = CANONICAL.fromJson(root, LegacyPlayers.class);
            if (legacy == null) legacy = new LegacyPlayers();
            boolean normalized = normalizeGlobal(document);
            boolean dirty = checksum.isEmpty() || normalized;
            boolean hasLegacyFields = root.has("links") || root.has("owners")
                    || root.has("rewarded") || root.has("firstSeen") || root.has("donors");
            return new GlobalLoad(document, root, legacy, !checksum.isEmpty(),
                    hasLegacyFields, dirty, false);
        } catch (IOException | RuntimeException exception) {
            preserveBroken();
            logger.severe(TAG + " Не удалось прочитать social-links.json: "
                    + exception.getClass().getSimpleName() + ". Включён аварийный режим.");
            return new GlobalLoad(null, null, null, false, false, false, true);
        }
    }

    /** Дополняет отсутствующие поля global JSON; true — нужна перезапись. */
    private static boolean normalizeGlobal(GlobalDocument document) {
        boolean changed = false;
        if (document.schema != SCHEMA) {
            document.schema = SCHEMA;
            changed = true;
        }
        if (document.rolloutStartedAt <= 0L) {
            document.rolloutStartedAt = System.currentTimeMillis();
            changed = true;
        }
        if (document.lastNewsTag == null) {
            document.lastNewsTag = "";
            changed = true;
        }
        if (document.discoveredChats == null) {
            document.discoveredChats = new LinkedHashMap<>();
            changed = true;
        }
        if (document.maxChatId == null) {
            document.maxChatId = "0";
            changed = true;
        }
        if (document.maxNewsChatId == null) {
            document.maxNewsChatId = "0";
            changed = true;
        }
        if (document.maxBridgeChatId == null) {
            document.maxBridgeChatId = "0";
            changed = true;
        }
        return changed;
    }

    private static SocialPlayerStore.Document fromLegacy(LegacyPlayers legacy) {
        SocialPlayerStore.Document document = new SocialPlayerStore.Document();
        if (legacy == null) return document;
        if (legacy.firstSeen != null) {
            for (Map.Entry<String, Long> entry : legacy.firstSeen.entrySet()) {
                SocialPlayerStore.Player player = document.player(entry.getKey(), true);
                if (player != null && entry.getValue() != null) {
                    player.firstSeenAt = Math.max(0L, entry.getValue());
                }
            }
        }
        if (legacy.rewarded != null) {
            for (Map.Entry<String, String> entry : legacy.rewarded.entrySet()) {
                SocialPlayerStore.Player player = document.player(entry.getKey(), true);
                if (player != null) player.rewardClaimedAt = positiveLong(entry.getValue());
            }
        }
        if (legacy.donors != null) {
            for (Map.Entry<String, String> entry : legacy.donors.entrySet()) {
                SocialPlayerStore.Player player = document.player(entry.getKey(), true);
                if (player == null) continue;
                String raw = entry.getValue() == null ? "" : entry.getValue();
                int split = raw.indexOf('|');
                player.donor = new SocialPlayerStore.Donor();
                player.donor.grantedAt = split > 0 ? positiveLong(raw.substring(0, split)) : 0L;
                player.donor.source = split >= 0 ? raw.substring(split + 1) : raw;
            }
        }
        if (legacy.links != null) {
            for (Map.Entry<String, LegacyBinding> entry : legacy.links.entrySet()) {
                LegacyBinding source = entry.getValue();
                String provider = SocialPlayerStore.normalizeProvider(source == null ? "" : source.provider);
                long externalId = source == null ? 0L : source.externalId;
                SocialPlayerStore.Player player = document.player(entry.getKey(), true);
                if (player == null || provider.isEmpty() || externalId <= 0L) continue;
                player.link = new SocialPlayerStore.Link();
                player.link.provider = provider;
                player.link.externalId = externalId;
                player.link.externalName = source.externalName == null ? "" : source.externalName;
                player.link.linkedAt = Math.max(0L, source.linkedAt);
                // owners из старого файла намеренно не восстанавливаем: индекс
                // строится из фактических связей, без «висячих» профилей.
                document.profiles.put(SocialPlayerStore.profileKey(provider, externalId),
                        canonical(entry.getKey()));
            }
        }
        return document;
    }

    private static long positiveLong(String raw) {
        if (raw == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Backup → удаление player-полей из global JSON → новая контрольная сумма. */
    private void pruneLegacyGlobal(JsonObject root) {
        if (root == null) return;
        try {
            PlayerDataFiles.backup(file, "players-move", logger, TAG);
        } catch (IOException | RuntimeException failure) {
            globalFrozen = true;
            globalDirty = true;
            logger.severe(TAG + " Backup social-links.json не создан (" + failure.getClass().getSimpleName()
                    + "). Оригинал не перезаписывается; устраните права на папку и перезапустите сервер.");
            return;
        }
        for (String legacy : List.of("links", "owners", "rewarded", "firstSeen", "donors")) {
            root.remove(legacy);
        }
        // Переносим нормализованные global-поля поверх исходного корня: schema 2,
        // rollout, чаты и MAX-id не остаются в старом виде после миграции.
        global.schema = SCHEMA;
        global.playerDataSchema = PLAYER_DATA_SCHEMA;
        for (Map.Entry<String, com.google.gson.JsonElement> field
                : CANONICAL.toJsonTree(global).getAsJsonObject().entrySet()) {
            root.add(field.getKey(), field.getValue());
        }
        root.addProperty("checksum", checksumOf(root));
        if (SafeJsonWriter.write(file, GSON.toJson(root), logger, TAG)) {
            globalDirty = false;
        } else {
            globalDirty = true;
        }
    }

    /** Повреждение storage не молча обходит обязательную привязку: режим виден сервису. */
    public synchronized boolean loadFailed() {
        return loadFailed;
    }

    public synchronized void acknowledgeCorruption(long emergencyUntil) {
        global.emergencyUntil = emergencyUntil;
        loadFailed = false;
        // Перед перезаписью global JSON сохраняем исходник: он может быть и
        // подделанным, и всё ещё содержать legacy-поля неудавшейся миграции.
        if (Files.isRegularFile(file)) {
            try {
                PlayerDataFiles.backup(file, "corruption", logger, TAG);
            } catch (IOException | RuntimeException failure) {
                globalFrozen = true;
                logger.severe(TAG + " Backup social-links.json перед аварийным режимом не создан ("
                        + failure.getClass().getSimpleName() + "). Оригинал не перезаписывается.");
                return;
            }
        }
        globalFrozen = false;
        globalDirty = true;
        saveGlobalNow();
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
        String canonicalJson = CANONICAL.toJson(copy);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Пишет оба файла (используется после подтверждения аварийного режима). */
    public synchronized void saveNow() {
        saveGlobalNow();
        savePlayersNow();
    }

    public synchronized void saveIfDirty() {
        if (loadFailed) return;
        if (globalDirty) saveGlobalNow();
        if (playersDirty && !playersFrozen) savePlayersNow();
    }

    /** True, если players/social.yml повреждён и не перезаписывается до ручной правки. */
    public synchronized boolean playersFrozen() {
        return playersFrozen;
    }

    private void saveGlobalNow() {
        if (loadFailed || globalFrozen) return;
        global.schema = SCHEMA;
        global.playerDataSchema = PLAYER_DATA_SCHEMA;
        JsonObject root = GSON.toJsonTree(global).getAsJsonObject();
        root.addProperty("checksum", checksumOf(root));
        if (SafeJsonWriter.write(file, GSON.toJson(root), logger, TAG)) globalDirty = false;
    }

    private void savePlayersNow() {
        if (loadFailed || playersFrozen) return;
        if (playerStore.save(players)) playersDirty = false;
    }

    public synchronized long rolloutStartedAt() {
        return global.rolloutStartedAt;
    }

    public synchronized long emergencyUntil() {
        return global.emergencyUntil;
    }

    public synchronized void setEmergencyUntil(long until) {
        global.emergencyUntil = Math.max(0L, until);
        globalDirty = true;
        saveGlobalNow();
    }

    public synchronized String lastNewsTag() {
        return global.lastNewsTag;
    }

    public synchronized void setLastNewsTag(String tag) {
        global.lastNewsTag = tag == null ? "" : tag.trim();
        globalDirty = true;
        saveGlobalNow();
    }

    public synchronized boolean isLinked(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        return player != null && player.link != null;
    }

    public synchronized Optional<Binding> binding(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        if (player == null || player.link == null) return Optional.empty();
        SocialPlayerStore.Link link = player.link;
        return Optional.of(new Binding(link.provider, link.externalId, link.externalName, link.linkedAt));
    }

    public synchronized Optional<String> ownerOf(String provider, long externalId) {
        return Optional.ofNullable(players.profiles.get(
                SocialPlayerStore.profileKey(provider, externalId)));
    }

    public synchronized LinkStatus link(String playerName, String provider, long externalId,
                                        String externalName, int maxAccounts, long now) {
        String key = canonical(playerName);
        String safeProvider = SocialPlayerStore.normalizeProvider(provider);
        String profile = SocialPlayerStore.profileKey(safeProvider, externalId);
        if (key.isEmpty() || externalId <= 0L || safeProvider.isEmpty()) {
            return LinkStatus.INVALID_ARGUMENT;
        }
        SocialPlayerStore.Player player = players.player(key, false);
        if (player != null && player.link != null) return LinkStatus.PLAYER_ALREADY_LINKED;
        int used = 0;
        for (Map.Entry<String, SocialPlayerStore.Player> entry : players.players.entrySet()) {
            SocialPlayerStore.Link existing = entry.getValue().link;
            if (existing != null && SocialPlayerStore.profileKey(existing.provider, existing.externalId)
                    .equals(profile)) {
                used++;
            }
        }
        if (used >= Math.max(1, maxAccounts)) return LinkStatus.PROFILE_LIMIT_REACHED;
        if (player == null) player = players.player(key, true);
        player.link = new SocialPlayerStore.Link();
        player.link.provider = safeProvider;
        player.link.externalId = externalId;
        player.link.externalName = externalName == null ? "" : externalName.trim();
        player.link.linkedAt = now;
        players.profiles.put(profile, key);
        playersDirty = true;
        savePlayersNow();
        return LinkStatus.LINKED;
    }

    public synchronized Optional<Binding> unlink(String playerName) {
        String key = canonical(playerName);
        SocialPlayerStore.Player player = players.player(key, false);
        if (player == null || player.link == null) return Optional.empty();
        SocialPlayerStore.Link removed = player.link;
        player.link = null;
        String profile = SocialPlayerStore.profileKey(removed.provider, removed.externalId);
        players.profiles.remove(profile, key);
        playersDirty = true;
        savePlayersNow();
        return Optional.of(new Binding(removed.provider, removed.externalId,
                removed.externalName, removed.linkedAt));
    }

    public synchronized boolean rewardClaimed(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        return player != null && player.rewardClaimedAt > 0L;
    }

    public synchronized void markRewarded(String playerName, long now) {
        SocialPlayerStore.Player player = players.player(playerName, true);
        if (player == null) return;
        player.rewardClaimedAt = Math.max(1L, now);
        playersDirty = true;
        savePlayersNow();
    }

    public synchronized void recordChat(String chatId, long now) {
        if (chatId == null || chatId.isBlank()) return;
        global.discoveredChats.put(chatId.trim(), now);
        globalDirty = true;
        saveGlobalNow();
    }

    public synchronized List<String> discoveredChats() {
        return List.copyOf(global.discoveredChats.keySet());
    }

    public synchronized long chatId(String kind) {
        String raw = switch (kind) {
            case "news" -> global.maxNewsChatId;
            case "bridge" -> global.maxBridgeChatId;
            default -> global.maxChatId;
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
            case "news" -> global.maxNewsChatId = value;
            case "bridge" -> global.maxBridgeChatId = value;
            default -> global.maxChatId = value;
        }
        global.discoveredChats.put(value, System.currentTimeMillis());
        globalDirty = true;
        saveGlobalNow();
    }

    /* --------------- гибридный grace и доступ через подтверждённый донат --------------- */

    /** Момент первого входа после установки; {@code 0} — игрок ещё не заходил. */
    public synchronized long firstSeenAt(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        return player == null ? 0L : player.firstSeenAt;
    }

    /** Фиксируется один раз и не переписывается при следующих входах. */
    public synchronized void recordFirstSeen(String playerName, long now) {
        SocialPlayerStore.Player player = players.player(playerName, true);
        if (player == null || player.firstSeenAt > 0L) return;
        player.firstSeenAt = Math.max(1L, now);
        playersDirty = true;
        savePlayersNow();
    }

    public synchronized boolean donorGranted(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        return player != null && player.donor != null;
    }

    public synchronized String donorSource(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        return player == null || player.donor == null ? "" : player.donor.source;
    }

    public synchronized void grantDonor(String playerName, String source, long now) {
        SocialPlayerStore.Player player = players.player(playerName, true);
        if (player == null) return;
        SocialPlayerStore.Donor donor = new SocialPlayerStore.Donor();
        donor.grantedAt = Math.max(1L, now);
        donor.source = source == null ? "" : source.trim();
        player.donor = donor;
        playersDirty = true;
        savePlayersNow();
    }

    public synchronized boolean revokeDonor(String playerName) {
        SocialPlayerStore.Player player = players.player(playerName, false);
        if (player == null || player.donor == null) return false;
        player.donor = null;
        playersDirty = true;
        savePlayersNow();
        return true;
    }

    public synchronized List<String> donors() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SocialPlayerStore.Player> entry : players.players.entrySet()) {
            if (entry.getValue().donor != null) out.add(entry.getKey());
        }
        return List.copyOf(out);
    }

    public synchronized int donorCount() {
        return donors().size();
    }

    public synchronized int linkCount() {
        int count = 0;
        for (SocialPlayerStore.Player player : players.players.values()) {
            if (player.link != null) count++;
        }
        return count;
    }
}
