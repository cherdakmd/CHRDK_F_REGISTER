package ru.cherdakmd.chrdkguilds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Хранилище гильдий в {@code guilds.json} (1.4.163).
 *
 * <p>Переживают перезапуск состав, казна, уровни, слава, приглашения и войны.
 * Здесь же лежит индекс «игрок → гильдия»: без него каждый вход в чат
 * перебирал бы все гильдии сервера.</p>
 *
 * <p>Запись общая для репозитория: снимок на серверном потоке, файл — во
 * временный и атомарное переименование. Класс чистый (Gson + NIO) и
 * проверяется тестами без Bukkit.</p>
 */
final class GuildStore {

    /** Версия формата файла. */
    static final int VERSION = 1;
    /** Сколько последних войн хранится в истории. */
    static final int MAX_HISTORY = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Запись истории войн: кто, с кем, когда и чем кончилось. */
    record WarRecord(String warId, String challenger, String target, String winner, String result,
                     long wager, long endedAt) {

        String time() {
            return endedAt <= 0 ? "—" : STAMP.format(Instant.ofEpochMilli(endedAt));
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> byMember = new HashMap<>();
    private final List<GuildInvite> invites = new ArrayList<>();
    private final List<GuildWar> wars = new ArrayList<>();
    private final List<WarRecord> history = new ArrayList<>();
    private boolean dirty;

    GuildStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("guilds.json");
        this.logger = logger;
    }

    Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые записи пропускаются с предупреждением. */
    synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            int skipped = 0;
            if (data.get("guilds") instanceof List<?> rows) {
                for (Object element : rows) {
                    if (!(element instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    Guild guild = readGuild(row);
                    if (guild == null || guilds.size() >= GuildConfig.MAX_GUILDS_LIMIT) {
                        skipped++;
                        continue;
                    }
                    if (guilds.containsKey(guild.id())) {
                        skipped++;
                        continue;
                    }
                    for (UUID member : guild.memberIds()) {
                        if (byMember.containsKey(member)) {
                            // Один игрок — одна гильдия: вторая запись считается мусором.
                            guild.removeMember(member);
                        }
                    }
                    if (guild.abandoned()) {
                        skipped++;
                        continue;
                    }
                    guilds.put(guild.id(), guild);
                    for (UUID member : guild.memberIds()) {
                        byMember.put(member, guild.id());
                    }
                }
            }
            if (data.get("invites") instanceof List<?> rows) {
                for (Object element : rows) {
                    if (!(element instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    GuildInvite invite = readInvite(row);
                    if (invite == null || guilds.get(invite.guildId()) == null
                            || byMember.containsKey(invite.player())) {
                        skipped++;
                        continue;
                    }
                    invites.add(invite);
                }
            }
            if (data.get("wars") instanceof List<?> rows) {
                for (Object element : rows) {
                    if (!(element instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    GuildWar war = readWar(row);
                    if (war == null || !guilds.containsKey(war.challengerId())
                            || !guilds.containsKey(war.targetId())) {
                        skipped++;
                        continue;
                    }
                    wars.add(war);
                }
            }
            if (data.get("history") instanceof List<?> rows) {
                for (Object element : rows) {
                    if (!(element instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    if (history.size() >= MAX_HISTORY) {
                        break;
                    }
                    String challenger = string(row.get("challenger"));
                    String target = string(row.get("target"));
                    if (challenger.isEmpty() || target.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    history.add(new WarRecord(string(row.get("id")), challenger, target,
                            string(row.get("winner")), string(row.get("result")),
                            number(row.get("wager")), Math.max(0L, number(row.get("ended-at")))));
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Guilds] Пропущено записей guilds.json: " + skipped + ".");
            }
            if (logger != null) {
                logger.info("[CHRDK_Guilds] Загружено гильдий: " + guilds.size()
                        + ", войн: " + wars.size() + ".");
            }
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Guilds] Не удалось прочитать " + file + ": "
                        + error.getMessage() + " — начну с чистого листа.");
            }
        }
    }

    private static Guild readGuild(Map<?, ?> row) {
        String id = string(row.get("id"));
        String name = string(row.get("name"));
        if (id.isEmpty() || name.isEmpty()) {
            return null;
        }
        UUID leader = uuid(row.get("leader"));
        Guild guild = new Guild(id, name, string(row.get("tag")), leader,
                Math.max(0L, number(row.get("created-at"))));
        if (row.get("members") instanceof Map<?, ?> members) {
            for (Map.Entry<?, ?> entry : members.entrySet()) {
                UUID member = uuid(entry.getKey());
                if (member == null) {
                    continue;
                }
                guild.addMember(member, GuildRank.parse(string(entry.getValue())));
            }
        }
        guild.restore(number(row.get("treasury")), (int) number(row.get("level")),
                (int) number(row.get("glory")), (int) number(row.get("wars-won")),
                (int) number(row.get("wars-lost")));
        guild.setWarCooldownUntil(number(row.get("war-cooldown-until")));
        return guild;
    }

    private static GuildInvite readInvite(Map<?, ?> row) {
        String guildId = string(row.get("guild"));
        UUID player = uuid(row.get("player"));
        UUID inviter = uuid(row.get("inviter"));
        if (guildId.isEmpty() || player == null) {
            return null;
        }
        return new GuildInvite(guildId, string(row.get("guild-name")), player, inviter,
                Math.max(0L, number(row.get("created-at"))),
                Math.max(0L, number(row.get("expires-at"))));
    }

    private static GuildWar readWar(Map<?, ?> row) {
        String challenger = string(row.get("challenger"));
        String target = string(row.get("target"));
        if (challenger.isEmpty() || target.isEmpty() || challenger.equals(target)) {
            return null;
        }
        GuildWar war = new GuildWar(string(row.get("id")), challenger, target,
                number(row.get("wager")), Math.max(0L, number(row.get("created-at"))));
        Map<String, Long> kills = new LinkedHashMap<>();
        if (row.get("last-kills") instanceof Map<?, ?> rows) {
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof Number value) {
                    kills.put(entry.getKey().toString(), value.longValue());
                }
            }
        }
        GuildWar.Status status = Status.parse(string(row.get("status")));
        GuildWar.Result result = ResultValue.parse(string(row.get("result")));
        war.restore(status, result, number(row.get("starts-at")), number(row.get("ends-at")),
                (int) number(row.get("challenger-points")), (int) number(row.get("target-points")),
                kills);
        return war;
    }

    /* --------------------------------- доступ --------------------------------- */

    synchronized List<Guild> guilds() {
        return List.copyOf(guilds.values());
    }

    synchronized int count() {
        return guilds.size();
    }

    synchronized Guild guild(String id) {
        return id == null ? null : guilds.get(id.trim());
    }

    synchronized Guild of(UUID player) {
        if (player == null) {
            return null;
        }
        String id = byMember.get(player);
        return id == null ? null : guilds.get(id);
    }

    synchronized Guild byName(String name) {
        String key = GuildMath.key(name);
        if (key.isEmpty()) {
            return null;
        }
        for (Guild guild : guilds.values()) {
            if (GuildMath.key(guild.name()).equals(key)) {
                return guild;
            }
        }
        return null;
    }

    synchronized Guild byTag(String tag) {
        String key = GuildMath.key(tag);
        if (key.isEmpty()) {
            return null;
        }
        for (Guild guild : guilds.values()) {
            if (GuildMath.key(guild.tag()).equals(key)) {
                return guild;
            }
        }
        return null;
    }

    /** Поиск по названию или тегу — для команд и войн. */
    synchronized Guild find(String query) {
        Guild byName = byName(query);
        return byName != null ? byName : byTag(query);
    }

    synchronized List<Guild> top(int limit) {
        List<Guild> list = new ArrayList<>(guilds.values());
        list.sort((left, right) -> {
            int byGlory = Integer.compare(right.glory(), left.glory());
            if (byGlory != 0) {
                return byGlory;
            }
            int byLevel = Integer.compare(right.level(), left.level());
            return byLevel != 0 ? byLevel : left.name().compareToIgnoreCase(right.name());
        });
        return list.subList(0, Math.clamp(limit, 0, list.size()));
    }

    /* -------------------------------- изменения ------------------------------- */

    synchronized void add(Guild guild) {
        if (guild == null || guilds.containsKey(guild.id())) {
            return;
        }
        guilds.put(guild.id(), guild);
        for (UUID member : guild.memberIds()) {
            byMember.put(member, guild.id());
        }
        dirty = true;
    }

    synchronized void remove(Guild guild) {
        if (guild == null || guilds.remove(guild.id()) == null) {
            return;
        }
        for (UUID member : guild.memberIds()) {
            byMember.remove(member);
        }
        invites.removeIf(invite -> invite.guildId().equals(guild.id()));
        wars.removeIf(war -> war.involves(guild.id()) && !war.finished());
        dirty = true;
    }

    /** Пересчитывает индекс «игрок → гильдия» после изменений состава. */
    synchronized void reindex(Guild guild) {
        if (guild == null) {
            return;
        }
        for (Map.Entry<UUID, String> entry : new ArrayList<>(byMember.entrySet())) {
            if (entry.getValue().equals(guild.id()) && !guild.isMember(entry.getKey())) {
                byMember.remove(entry.getKey());
            }
        }
        for (UUID member : guild.memberIds()) {
            byMember.put(member, guild.id());
        }
        dirty = true;
    }

    /* ------------------------------- приглашения ------------------------------ */

    synchronized void addInvite(GuildInvite invite) {
        if (invite == null) {
            return;
        }
        invites.removeIf(existing -> existing.player().equals(invite.player()));
        invites.add(invite);
        dirty = true;
    }

    synchronized GuildInvite inviteOf(UUID player) {
        if (player == null) {
            return null;
        }
        for (GuildInvite invite : invites) {
            if (invite.player().equals(player)) {
                return invite;
            }
        }
        return null;
    }

    synchronized boolean removeInvite(UUID player) {
        boolean removed = invites.removeIf(invite -> invite.player().equals(player));
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Убирает просроченные приглашения; возвращает число удалённых. */
    synchronized int pruneInvites(long now) {
        int before = invites.size();
        invites.removeIf(invite -> invite.expired(now));
        int removed = before - invites.size();
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    /* ---------------------------------- войны --------------------------------- */

    synchronized void addWar(GuildWar war) {
        if (war == null) {
            return;
        }
        wars.removeIf(existing -> existing.id().equals(war.id()));
        wars.add(war);
        dirty = true;
    }

    synchronized List<GuildWar> wars() {
        return List.copyOf(wars);
    }

    /** Незакрытая война гильдии или {@code null}. */
    synchronized GuildWar warOf(String guildId) {
        if (guildId == null) {
            return null;
        }
        for (GuildWar war : wars) {
            if (!war.finished() && war.involves(guildId)) {
                return war;
            }
        }
        return null;
    }

    /** Война между двумя гильдиями, если она ещё не закрыта. */
    synchronized GuildWar warBetween(String first, String second) {
        if (first == null || second == null) {
            return null;
        }
        for (GuildWar war : wars) {
            if (!war.finished() && war.involves(first) && war.involves(second)) {
                return war;
            }
        }
        return null;
    }

    synchronized int activeWars() {
        int count = 0;
        for (GuildWar war : wars) {
            if (war.active()) {
                count++;
            }
        }
        return count;
    }

    synchronized int pendingWars() {
        int count = 0;
        for (GuildWar war : wars) {
            if (war.pending()) {
                count++;
            }
        }
        return count;
    }

    /** Закрывает войну и убирает её из активных, оставляя в истории. */
    synchronized void closeWar(GuildWar war, String winnerName) {
        if (war == null) {
            return;
        }
        wars.remove(war);
        history.add(new WarRecord(war.id(), war.challengerId(), war.targetId(),
                winnerName == null ? "" : winnerName,
                war.result() == null ? "" : war.result().name(),
                war.wager(), System.currentTimeMillis()));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        dirty = true;
    }

    synchronized List<WarRecord> history() {
        return List.copyOf(history);
    }

    synchronized void clearHistory() {
        if (history.isEmpty()) {
            return;
        }
        history.clear();
        dirty = true;
    }

    /* --------------------------------- запись --------------------------------- */

    synchronized boolean isDirty() {
        return dirty;
    }

    synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        List<Map<String, Object>> guildRows = new ArrayList<>();
        for (Guild guild : guilds.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", guild.id());
            row.put("name", guild.name());
            if (!guild.tag().isEmpty()) {
                row.put("tag", guild.tag());
            }
            row.put("leader", String.valueOf(guild.leader()));
            row.put("created-at", guild.createdAt());
            Map<String, Object> members = new LinkedHashMap<>();
            for (Map.Entry<UUID, GuildRank> entry : guild.members().entrySet()) {
                members.put(entry.getKey().toString(), entry.getValue().id());
            }
            row.put("members", members);
            row.put("treasury", guild.treasury());
            row.put("level", guild.level());
            row.put("glory", guild.glory());
            row.put("wars-won", guild.warsWon());
            row.put("wars-lost", guild.warsLost());
            if (guild.warCooldownUntil() > 0) {
                row.put("war-cooldown-until", guild.warCooldownUntil());
            }
            guildRows.add(row);
        }
        data.put("guilds", guildRows);

        List<Map<String, Object>> inviteRows = new ArrayList<>();
        for (GuildInvite invite : invites) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("guild", invite.guildId());
            row.put("guild-name", invite.guildName());
            row.put("player", invite.player().toString());
            if (invite.inviter() != null) {
                row.put("inviter", invite.inviter().toString());
            }
            row.put("created-at", invite.createdAt());
            row.put("expires-at", invite.expiresAt());
            inviteRows.add(row);
        }
        if (!inviteRows.isEmpty()) {
            data.put("invites", inviteRows);
        }

        List<Map<String, Object>> warRows = new ArrayList<>();
        for (GuildWar war : wars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", war.id());
            row.put("challenger", war.challengerId());
            row.put("target", war.targetId());
            row.put("wager", war.wager());
            row.put("created-at", war.createdAt());
            row.put("starts-at", war.startsAt());
            row.put("ends-at", war.endsAt());
            row.put("status", war.status().name());
            if (war.result() != null) {
                row.put("result", war.result().name());
            }
            row.put("challenger-points", war.challengerPoints());
            row.put("target-points", war.targetPoints());
            if (!war.lastKills().isEmpty()) {
                row.put("last-kills", new LinkedHashMap<>(war.lastKills()));
            }
            warRows.add(row);
        }
        if (!warRows.isEmpty()) {
            data.put("wars", warRows);
        }

        if (!history.isEmpty()) {
            List<Map<String, Object>> historyRows = new ArrayList<>();
            for (WarRecord record : history) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", record.warId());
                row.put("challenger", record.challenger());
                row.put("target", record.target());
                if (!record.winner().isEmpty()) {
                    row.put("winner", record.winner());
                }
                if (!record.result().isEmpty()) {
                    row.put("result", record.result());
                }
                row.put("wager", record.wager());
                row.put("ended-at", record.endedAt());
                historyRows.add(row);
            }
            data.put("history", historyRows);
        }
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            json = snapshotJson();
        }
        write(json);
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Guilds] Не удалось записать " + file + ": "
                        + error.getMessage());
            }
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String string(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }

    private static long number(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static UUID uuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    /** Обёртки разбора перечислений: неизвестное значение не роняет загрузку. */
    private static final class Status {
        static GuildWar.Status parse(String raw) {
            return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
                case "ACTIVE" -> GuildWar.Status.ACTIVE;
                case "FINISHED" -> GuildWar.Status.FINISHED;
                default -> GuildWar.Status.PENDING;
            };
        }
    }

    private static final class ResultValue {
        static GuildWar.Result parse(String raw) {
            return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
                case "CHALLENGER_WIN" -> GuildWar.Result.CHALLENGER_WIN;
                case "TARGET_WIN" -> GuildWar.Result.TARGET_WIN;
                case "DRAW" -> GuildWar.Result.DRAW;
                case "CANCELLED" -> GuildWar.Result.CANCELLED;
                default -> null;
            };
        }
    }
}
