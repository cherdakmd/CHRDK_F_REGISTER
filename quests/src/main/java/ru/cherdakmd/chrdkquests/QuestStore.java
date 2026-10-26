package ru.cherdakmd.chrdkquests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Прогресс квестов в {@code quests.json} (1.4.161).
 *
 * <p>Хранится только то, что нельзя пересчитать: фракция игрока, репутация по
 * фракциям, счётчики заданий, закрытые задания и суточный счёт сдач. Имена
 * заданий, цели и награды берутся из конфига — если админ поправит текст,
 * он изменится и у тех, кто уже начал.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class QuestStore {

    /** Версия формата файла. */
    public static final int VERSION = 1;
    /** Потолок числа игроков в файле: защита от мусора. */
    public static final int MAX_PLAYERS = 200_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    /** Данные одного игрока. Мутировать можно только через методы хранилища. */
    public static final class Player {
        private final UUID id;
        private String name;
        private String faction = "";
        private final Map<String, Integer> reputation = new LinkedHashMap<>();
        private final Map<String, Integer> progress = new LinkedHashMap<>();
        private final Set<String> done = new LinkedHashSet<>();
        private String claimDay = "";
        private int claimCount;

        Player(UUID id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private boolean dirty;

    public QuestStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("quests.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые записи пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            int skipped = 0;
            if (data.get("players") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row)
                            || players.size() >= MAX_PLAYERS) {
                        skipped++;
                        continue;
                    }
                    Player player = readPlayer(id, row);
                    if (player == null) {
                        skipped++;
                        continue;
                    }
                    players.put(id, player);
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Quests] Пропущено записей quests.json: " + skipped + ".");
            }
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Quests] Не удалось прочитать " + file + ": "
                        + error.getMessage() + " — начну с чистого листа.");
            }
        }
    }

    private static Player readPlayer(UUID id, Map<?, ?> row) {
        String name = row.get("name") == null ? "" : row.get("name").toString();
        Player player = new Player(id, name);
        player.faction = row.get("faction") == null ? ""
                : row.get("faction").toString().trim().toLowerCase(Locale.ROOT);
        if (row.get("reputation") instanceof Map<?, ?> reputation) {
            for (Map.Entry<?, ?> entry : reputation.entrySet()) {
                String faction = id(entry.getKey());
                if (!faction.isEmpty()) {
                    player.reputation.put(faction,
                            (int) Math.clamp(number(entry.getValue()), 0L,
                                    QuestFaction.MAX_REPUTATION));
                }
            }
        }
        if (row.get("progress") instanceof Map<?, ?> progress) {
            for (Map.Entry<?, ?> entry : progress.entrySet()) {
                String quest = id(entry.getKey());
                if (!quest.isEmpty()) {
                    player.progress.put(quest,
                            (int) Math.clamp(number(entry.getValue()), 0L, Quest.MAX_AMOUNT));
                }
            }
        }
        if (row.get("done") instanceof List<?> done) {
            for (Object element : done) {
                String quest = id(element);
                if (!quest.isEmpty()) {
                    player.done.add(quest);
                }
            }
        }
        player.claimDay = row.get("claim-day") == null ? "" : row.get("claim-day").toString();
        player.claimCount = (int) Math.clamp(number(row.get("claim-count")), 0L,
                QuestConfig.MAX_CLAIMS_PER_DAY);
        return player;
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Данные игрока; создаются при первом обращении. */
    public synchronized Player of(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id игрока не может быть null");
        }
        Player player = players.get(id);
        if (player == null) {
            if (players.size() >= MAX_PLAYERS) {
                // Защита от переполнения: лучше отказать, чем уронить сервер.
                return new Player(id, "");
            }
            player = new Player(id, "");
            players.put(id, player);
        }
        return player;
    }

    /** Запоминает актуальный ник: по нему удобно искать в админских командах. */
    public synchronized void rename(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) {
            return;
        }
        Player player = of(id);
        if (!name.equals(player.name)) {
            player.name = name;
            dirty = true;
        }
    }

    public synchronized String name(UUID id) {
        return of(id).name;
    }

    public synchronized String faction(UUID id) {
        return of(id).faction;
    }

    /** Устанавливает фракцию; {@code ""} — выйти. */
    public synchronized boolean setFaction(UUID id, String faction) {
        Player player = of(id);
        String value = faction == null ? "" : faction.trim().toLowerCase(Locale.ROOT);
        if (player.faction.equals(value)) {
            return false;
        }
        player.faction = value;
        dirty = true;
        return true;
    }

    public synchronized int reputation(UUID id, String faction) {
        String key = id(faction);
        return key.isEmpty() ? 0 : of(id).reputation.getOrDefault(key, 0);
    }

    /** Добавляет очки фракции и возвращает новое значение. */
    public synchronized int addReputation(UUID id, String faction, int amount) {
        String key = id(faction);
        if (key.isEmpty() || amount <= 0) {
            return reputation(id, key);
        }
        Player player = of(id);
        int value = (int) Math.clamp((long) player.reputation.getOrDefault(key, 0) + amount,
                0L, QuestFaction.MAX_REPUTATION);
        player.reputation.put(key, value);
        dirty = true;
        return value;
    }

    public synchronized int progress(UUID id, String questId) {
        String key = id(questId);
        return key.isEmpty() ? 0 : of(id).progress.getOrDefault(key, 0);
    }

    /** Двигает прогресс задания, не превышая цель. */
    public synchronized int addProgress(UUID id, String questId, int delta, int cap) {
        String key = id(questId);
        if (key.isEmpty() || delta <= 0) {
            return progress(id, key);
        }
        Player player = of(id);
        int limit = Math.clamp(cap, 1, Quest.MAX_AMOUNT);
        int value = Math.min(limit, player.progress.getOrDefault(key, 0) + delta);
        player.progress.put(key, value);
        dirty = true;
        return value;
    }

    public synchronized boolean done(UUID id, String questId) {
        String key = id(questId);
        return !key.isEmpty() && of(id).done.contains(key);
    }

    public synchronized void markDone(UUID id, String questId) {
        String key = id(questId);
        if (key.isEmpty()) {
            return;
        }
        Player player = of(id);
        if (player.done.add(key)) {
            player.progress.remove(key);
            dirty = true;
        }
    }

    public synchronized int doneCount(UUID id) {
        return of(id).done.size();
    }

    /** Сколько сдач уже было в этот день. */
    public synchronized int claims(UUID id, String day) {
        Player player = of(id);
        return day != null && day.equals(player.claimDay) ? player.claimCount : 0;
    }

    /**
     * Считает сдачу за день.
     *
     * @return {@code false}, если лимит на сегодня уже выбран
     */
    public synchronized boolean countClaim(UUID id, String day, int limit) {
        if (day == null || day.isBlank()) {
            return false;
        }
        Player player = of(id);
        int cap = Math.clamp(limit, 1, QuestConfig.MAX_CLAIMS_PER_DAY);
        if (!day.equals(player.claimDay)) {
            player.claimDay = day;
            player.claimCount = 0;
        }
        if (player.claimCount >= cap) {
            return false;
        }
        player.claimCount++;
        dirty = true;
        return true;
    }

    /** Полный сброс прогресса игрока (админская команда). */
    public synchronized boolean reset(UUID id) {
        Player player = players.get(id);
        if (player == null) {
            return false;
        }
        player.faction = "";
        player.reputation.clear();
        player.progress.clear();
        player.done.clear();
        player.claimDay = "";
        player.claimCount = 0;
        dirty = true;
        return true;
    }

    public synchronized int size() {
        return players.size();
    }

    /** Ищет игрока по сохранённому нику (без учёта регистра). */
    public synchronized UUID byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Player player : players.values()) {
            if (name.equalsIgnoreCase(player.name)) {
                return player.id;
            }
        }
        return null;
    }

    /** Сколько всего сдач за всё время — для админской сводки. */
    public synchronized int totalDone() {
        int total = 0;
        for (Player player : players.values()) {
            total += player.done.size();
        }
        return total;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* -------------------------------- запись ---------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Player player : players.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.name);
            if (!player.faction.isEmpty()) {
                row.put("faction", player.faction);
            }
            if (!player.reputation.isEmpty()) {
                row.put("reputation", new LinkedHashMap<>(player.reputation));
            }
            if (!player.progress.isEmpty()) {
                row.put("progress", new LinkedHashMap<>(player.progress));
            }
            if (!player.done.isEmpty()) {
                row.put("done", new ArrayList<>(player.done));
            }
            if (!player.claimDay.isEmpty()) {
                row.put("claim-day", player.claimDay);
                row.put("claim-count", player.claimCount);
            }
            rows.put(player.id.toString(), row);
        }
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    public void saveNow() {
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
    public void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Quests]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String id(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    private static long number(Object raw) {
        return raw instanceof Number value ? value.longValue() : 0L;
    }
}
