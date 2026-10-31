package ru.cherdakmd.chrdkvk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Хранилище привязок {@code CHRDK_VK} ({@code vk-links.json}):
 * связи игрок ↔ {@code vk_user_id}, статусы подписки и VK Donut,
 * личные настройки моста чата, список блокировок моста и реестры одноразовых XP-наград.
 */
public final class VkStore {

    public static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VK_ID_TOKEN = Pattern.compile(
            "^(?:https?://(?:www\\.|m\\.)?vk\\.(?:com|ru)/)?(?:@?id)?(\\d{1,18})/?$",
            Pattern.CASE_INSENSITIVE);

    public enum LinkStatus {
        LINKED,
        ALREADY_LINKED_PLAYER,
        VK_ACCOUNT_LIMIT_REACHED,
        INVALID_ARGUMENT
    }

    public record LinkSnapshot(
            String canonicalName,
            String playerName,
            long vkUserId,
            long linkedAt,
            boolean member,
            boolean donut,
            long donutVerifiedAt,
            boolean notifyLogins,
            boolean allowVkChatMentions
    ) {
    }

    private static final class StoredLink {
        String playerName = "";
        long vkUserId;
        long linkedAt;
        boolean member;
        boolean donut;
        long donutVerifiedAt;
        boolean notifyLogins = true;
        Boolean allowVkChatMentions = Boolean.TRUE;
    }

    private static final class StoreDocument {
        int version = SCHEMA_VERSION;
        long lastAnnouncedPostId;
        long groupChatPeerId;
        Map<String, StoredLink> links = new LinkedHashMap<>();
        Set<String> hiddenGroupChatPlayers = new LinkedHashSet<>();
        Set<Long> bannedChatVkIds = new LinkedHashSet<>();
        Set<String> linkBoostClaimedPlayers = new LinkedHashSet<>();
        Set<Long> linkBoostClaimedVkIds = new LinkedHashSet<>();
        Set<String> memberBoostClaimedPlayers = new LinkedHashSet<>();
        Set<Long> memberBoostClaimedVkIds = new LinkedHashSet<>();
    }

    private final Path file;
    private final Logger logger;
    private StoreDocument doc = new StoreDocument();
    private boolean dirty;

    public VkStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("vk-links.json");
        this.logger = logger;
    }

    public static String canonical(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            doc = new StoreDocument();
            dirty = false;
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoreDocument loaded = GSON.fromJson(reader, StoreDocument.class);
            if (loaded == null) {
                loaded = new StoreDocument();
            }
            if (loaded.links == null) loaded.links = new LinkedHashMap<>();
            if (loaded.hiddenGroupChatPlayers == null) loaded.hiddenGroupChatPlayers = new LinkedHashSet<>();
            if (loaded.bannedChatVkIds == null) loaded.bannedChatVkIds = new LinkedHashSet<>();
            if (loaded.linkBoostClaimedPlayers == null) loaded.linkBoostClaimedPlayers = new LinkedHashSet<>();
            if (loaded.linkBoostClaimedVkIds == null) loaded.linkBoostClaimedVkIds = new LinkedHashSet<>();
            if (loaded.memberBoostClaimedPlayers == null) loaded.memberBoostClaimedPlayers = new LinkedHashSet<>();
            if (loaded.memberBoostClaimedVkIds == null) loaded.memberBoostClaimedVkIds = new LinkedHashSet<>();
            loaded.version = SCHEMA_VERSION;
            this.doc = loaded;
            this.dirty = false;
        } catch (Exception exception) {
            logger.severe("[CHRDK_VK] Не удалось прочитать " + file + ": " + exception.getMessage());
        }
    }

    public boolean saveIfDirty() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return true;
            }
            json = GSON.toJson(doc);
            dirty = false;
        }
        boolean written = SafeJsonWriter.write(file, json, logger, "[CHRDK_VK]");
        if (!written) {
            synchronized (this) {
                dirty = true;
            }
        }
        return written;
    }

    public boolean saveNow() {
        String json;
        synchronized (this) {
            json = GSON.toJson(doc);
            dirty = false;
        }
        boolean written = SafeJsonWriter.write(file, json, logger, "[CHRDK_VK]");
        if (!written) {
            synchronized (this) {
                dirty = true;
            }
        }
        return written;
    }

    public synchronized LinkStatus link(String playerName, long vkUserId, int maxAccountsPerVk, long nowMillis) {
        String key = canonical(playerName);
        if (key.isEmpty() || vkUserId <= 0L) {
            return LinkStatus.INVALID_ARGUMENT;
        }
        if (doc.links.containsKey(key)) {
            return LinkStatus.ALREADY_LINKED_PLAYER;
        }
        int boundToVk = 0;
        for (StoredLink existing : doc.links.values()) {
            if (existing.vkUserId == vkUserId) {
                boundToVk++;
            }
        }
        if (boundToVk >= Math.max(1, maxAccountsPerVk)) {
            return LinkStatus.VK_ACCOUNT_LIMIT_REACHED;
        }
        StoredLink created = new StoredLink();
        created.playerName = playerName.trim();
        created.vkUserId = vkUserId;
        created.linkedAt = nowMillis;
        created.notifyLogins = true;
        doc.links.put(key, created);
        dirty = true;
        return LinkStatus.LINKED;
    }

    public synchronized Optional<LinkSnapshot> unlink(String playerName) {
        String key = canonical(playerName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        StoredLink removed = doc.links.remove(key);
        if (removed == null) {
            return Optional.empty();
        }
        dirty = true;
        return Optional.of(toSnapshot(key, removed));
    }

    public synchronized List<LinkSnapshot> unlinkByVkUser(long vkUserId) {
        if (vkUserId <= 0L) {
            return List.of();
        }
        List<LinkSnapshot> removed = new ArrayList<>();
        var iterator = doc.links.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().vkUserId == vkUserId) {
                removed.add(toSnapshot(entry.getKey(), entry.getValue()));
                iterator.remove();
            }
        }
        if (!removed.isEmpty()) {
            dirty = true;
        }
        return removed;
    }

    public synchronized Optional<LinkSnapshot> byPlayer(String playerName) {
        String key = canonical(playerName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        StoredLink stored = doc.links.get(key);
        return stored == null ? Optional.empty() : Optional.of(toSnapshot(key, stored));
    }

    public synchronized List<LinkSnapshot> byVkUser(long vkUserId) {
        if (vkUserId <= 0L) {
            return List.of();
        }
        List<LinkSnapshot> result = new ArrayList<>();
        for (var entry : doc.links.entrySet()) {
            if (entry.getValue().vkUserId == vkUserId) {
                result.add(toSnapshot(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    public synchronized List<LinkSnapshot> allLinks() {
        List<LinkSnapshot> result = new ArrayList<>(doc.links.size());
        for (var entry : doc.links.entrySet()) {
            result.add(toSnapshot(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public synchronized int linkCount() {
        return doc.links.size();
    }

    public synchronized int activeDonutCount(long nowMillis, int graceHours) {
        long windowMillis = TimeUnit.HOURS.toMillis(Math.max(1, graceHours));
        int count = 0;
        for (StoredLink link : doc.links.values()) {
            if (link.donut && link.donutVerifiedAt > 0L && nowMillis - link.donutVerifiedAt <= windowMillis) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean donutPerkActive(String playerName, long nowMillis, int graceHours) {
        StoredLink link = doc.links.get(canonical(playerName));
        if (link == null || !link.donut || link.donutVerifiedAt <= 0L) {
            return false;
        }
        return nowMillis - link.donutVerifiedAt <= TimeUnit.HOURS.toMillis(Math.max(1, graceHours));
    }

    public synchronized Optional<Boolean> toggleNotifyLogins(String playerName) {
        StoredLink link = doc.links.get(canonical(playerName));
        if (link == null) {
            return Optional.empty();
        }
        link.notifyLogins = !link.notifyLogins;
        dirty = true;
        return Optional.of(link.notifyLogins);
    }

    public synchronized Optional<Boolean> toggleVkChatMentions(String playerName) {
        StoredLink link = doc.links.get(canonical(playerName));
        if (link == null) {
            return Optional.empty();
        }
        boolean current = link.allowVkChatMentions == null || link.allowVkChatMentions;
        link.allowVkChatMentions = !current;
        dirty = true;
        return Optional.of(link.allowVkChatMentions);
    }

    public synchronized boolean receivesGroupChat(String playerName) {
        String key = canonical(playerName);
        return key.isEmpty() || !doc.hiddenGroupChatPlayers.contains(key);
    }

    public synchronized boolean toggleReceiveGroupChat(String playerName) {
        String key = canonical(playerName);
        if (key.isEmpty()) {
            return true;
        }
        boolean nowReceiving;
        if (doc.hiddenGroupChatPlayers.remove(key)) {
            nowReceiving = true;
        } else {
            doc.hiddenGroupChatPlayers.add(key);
            nowReceiving = false;
        }
        dirty = true;
        return nowReceiving;
    }

    public synchronized boolean isVkUserChatBanned(long vkUserId) {
        return vkUserId > 0L && doc.bannedChatVkIds.contains(vkUserId);
    }

    public synchronized boolean setVkUserChatBanned(long vkUserId, boolean banned) {
        if (vkUserId <= 0L) {
            return false;
        }
        boolean changed = banned ? doc.bannedChatVkIds.add(vkUserId) : doc.bannedChatVkIds.remove(vkUserId);
        if (changed) {
            dirty = true;
        }
        return changed;
    }

    public synchronized int bannedChatVkCount() {
        return doc.bannedChatVkIds.size();
    }

    /**
     * Разбирает токен профиля ВК вида {@code 12345}, {@code id12345}, {@code @id12345}
     * или {@code https://vk.com/id12345} (без обращения к сети).
     */
    public static OptionalLong parseVkUserIdToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        Matcher m = VK_ID_TOKEN.matcher(raw.trim());
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(m.group(1));
            return value > 0L ? OptionalLong.of(value) : OptionalLong.empty();
        } catch (NumberFormatException overflow) {
            return OptionalLong.empty();
        }
    }

    public synchronized boolean updateMemberStatus(long vkUserId, boolean member) {
        boolean changed = false;
        for (StoredLink link : doc.links.values()) {
            if (link.vkUserId == vkUserId && link.member != member) {
                link.member = member;
                changed = true;
            }
        }
        if (changed) {
            dirty = true;
        }
        return changed;
    }

    public synchronized boolean updateDonutStatus(long vkUserId, boolean donut, long nowMillis) {
        boolean changed = false;
        for (StoredLink link : doc.links.values()) {
            if (link.vkUserId == vkUserId) {
                if (link.donut != donut || (donut && link.donutVerifiedAt != nowMillis)) {
                    link.donut = donut;
                    if (donut) {
                        link.donutVerifiedAt = nowMillis;
                    }
                    changed = true;
                }
            }
        }
        if (changed) {
            dirty = true;
        }
        return changed;
    }

    /* ---------------- анти-абуз одноразовых XP-наград ---------------- */

    public synchronized boolean canClaimLinkBoost(String playerName, long vkUserId) {
        String key = canonical(playerName);
        return !key.isEmpty() && vkUserId > 0L
                && !doc.linkBoostClaimedPlayers.contains(key)
                && !doc.linkBoostClaimedVkIds.contains(vkUserId);
    }

    public synchronized void markLinkBoostClaimed(String playerName, long vkUserId) {
        String key = canonical(playerName);
        if (!key.isEmpty()) doc.linkBoostClaimedPlayers.add(key);
        if (vkUserId > 0L) doc.linkBoostClaimedVkIds.add(vkUserId);
        dirty = true;
    }

    public synchronized boolean canClaimMemberBoost(String playerName, long vkUserId) {
        String key = canonical(playerName);
        return !key.isEmpty() && vkUserId > 0L
                && !doc.memberBoostClaimedPlayers.contains(key)
                && !doc.memberBoostClaimedVkIds.contains(vkUserId);
    }

    public synchronized void markMemberBoostClaimed(String playerName, long vkUserId) {
        String key = canonical(playerName);
        if (!key.isEmpty()) doc.memberBoostClaimedPlayers.add(key);
        if (vkUserId > 0L) doc.memberBoostClaimedVkIds.add(vkUserId);
        dirty = true;
    }

    /* ---------------- курсор стены и привязка беседы ---------------- */

    public synchronized long groupChatPeerId() {
        return doc.groupChatPeerId;
    }

    public synchronized void setGroupChatPeerId(long peerId) {
        long normalized = peerId >= VkConfig.MIN_CONVERSATION_PEER_ID ? peerId : 0L;
        if (doc.groupChatPeerId != normalized) {
            doc.groupChatPeerId = normalized;
            dirty = true;
        }
    }

    public synchronized long lastAnnouncedPostId() {
        return doc.lastAnnouncedPostId;
    }

    public synchronized boolean advanceLastAnnouncedPostId(long postId) {
        if (postId <= doc.lastAnnouncedPostId) {
            return false;
        }
        doc.lastAnnouncedPostId = postId;
        dirty = true;
        return true;
    }

    private static LinkSnapshot toSnapshot(String canonical, StoredLink stored) {
        return new LinkSnapshot(
                canonical,
                stored.playerName == null || stored.playerName.isBlank() ? canonical : stored.playerName,
                stored.vkUserId,
                stored.linkedAt,
                stored.member,
                stored.donut,
                stored.donutVerifiedAt,
                stored.notifyLogins,
                stored.allowVkChatMentions == null || stored.allowVkChatMentions
        );
    }
}
