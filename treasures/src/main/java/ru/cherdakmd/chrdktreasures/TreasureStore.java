package ru.cherdakmd.chrdktreasures;

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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Данные кладоискателей (`treasures.json`).
 *
 * <p>Формат: {@code {"version":1,"players":{"<uuid>":{"name":"Steve",
 * "mined":123,"tiers":{"common":2},"items":{"plains-reap-hook":1},
 * "settings":{"announce":true},"milestones":[10],"streak":4,
 * "best_streak":9,"last_find":1730000000000,"shards":46,
 * "shards_spent":12,"rank_tag":"auto"}}}}.</p>
 *
 * <p>Поля серии и осколков необязательны: старые файлы читаются без них, а
 * новые поля пишутся рядом — версия формата остаётся 1, ломать чужие данные не
 * нужно.</p>
 *
 * <p>Счётчик добытых блоков — топливо прогрессии: он растёт на каждом
 * подходящем блоке, даже если клад не выпал. Число находок по редкостям нужно
 * для топа и витрины, а карта предметов — для коллекции.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке, снимок и
 * асинхронная запись раз в 5 секунд, синхронизация на хранилище.</p>
 */
public final class TreasureStore {

    /** Прогресс одного игрока. */
    public static final class PlayerData {
        public String name = "";
        public long mined;
        public final Map<TreasureTier, Long> tiers = new EnumMap<>(TreasureTier.class);
        public final Map<String, Integer> items = new LinkedHashMap<>();
        public final Set<Integer> milestones = new TreeSet<>();
        public boolean announce = true;
        public boolean particles = true;
        public boolean sounds = true;
        /** Текущая серия находок и личный рекорд. */
        public int streak;
        public int bestStreak;
        /** Время последней находки в миллисекундах (0 — находок не было). */
        public long lastFindAt;
        /** Осколки кладов: баланс обменника и сколько уже потрачено за всё время. */
        public long shards;
        public long shardsSpent;
        /** Показывать ли звание рядом с ником: авто, всегда, никогда (1.4.166). */
        public TreasureRankTags.Choice rankTag = TreasureRankTags.Choice.AUTO;

        public long found() {
            long total = 0L;
            for (long value : tiers.values()) {
                total += value;
            }
            return total;
        }

        public int foundItems() {
            return items.size();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, PlayerData> players = new LinkedHashMap<>();
    private boolean dirty;

    public TreasureStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("treasures.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null || !(data.get("players") instanceof Map<?, ?> rows)) {
                return;
            }
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                PlayerData player = new PlayerData();
                Object name = row.get("name");
                player.name = name == null ? "" : String.valueOf(name).trim();
                player.mined = number(row.get("mined"));
                if (row.get("tiers") instanceof Map<?, ?> tiers) {
                    for (Map.Entry<?, ?> tier : tiers.entrySet()) {
                        TreasureTier type = TreasureTier.byId(String.valueOf(tier.getKey()));
                        if (type != null) {
                            player.tiers.put(type, number(tier.getValue()));
                        }
                    }
                }
                if (row.get("items") instanceof Map<?, ?> items) {
                    for (Map.Entry<?, ?> item : items.entrySet()) {
                        int count = (int) Math.min(Integer.MAX_VALUE, number(item.getValue()));
                        if (count > 0) {
                            player.items.put(String.valueOf(item.getKey()), count);
                        }
                    }
                }
                if (row.get("settings") instanceof Map<?, ?> settings) {
                    player.announce = bool(settings.get("announce"), true);
                    player.particles = bool(settings.get("particles"), true);
                    player.sounds = bool(settings.get("sounds"), true);
                }
                if (row.get("milestones") instanceof List<?> milestones) {
                    for (Object element : milestones) {
                        if (element instanceof Number count) {
                            player.milestones.add(Math.max(1, count.intValue()));
                        }
                    }
                }
                player.streak = (int) Math.min(Integer.MAX_VALUE, number(row.get("streak")));
                player.bestStreak = (int) Math.min(Integer.MAX_VALUE,
                        number(row.get("best_streak")));
                player.bestStreak = Math.max(player.bestStreak, player.streak);
                player.lastFindAt = number(row.get("last_find"));
                player.shards = Math.max(0L, number(row.get("shards")));
                player.shardsSpent = Math.max(0L, number(row.get("shards_spent")));
                TreasureRankTags.Choice rankTag = TreasureRankTags.Choice.byId(
                        row.get("rank_tag") == null ? null : String.valueOf(row.get("rank_tag")));
                player.rankTag = rankTag == null ? TreasureRankTags.Choice.AUTO : rankTag;
                players.put(String.valueOf(entry.getKey()), player);
            }
            logger.info("[CHRDK_Treasures] Загружено кладоискателей: " + players.size() + ".");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Treasures] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean flag ? flag : fallback;
    }

    private PlayerData data(String playerKey) {
        return players.computeIfAbsent(playerKey, key -> new PlayerData());
    }

    public synchronized String name(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? "" : data.name;
    }

    public synchronized void setName(String playerKey, String name) {
        if (playerKey == null || name == null || name.isBlank()) {
            return;
        }
        PlayerData data = data(playerKey);
        String trimmed = name.trim();
        if (trimmed.equals(data.name)) {
            return;
        }
        data.name = trimmed;
        dirty = true;
    }

    public synchronized long mined(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.mined;
    }

    /** Прибавляет добытые блоки и возвращает новый итог. */
    public synchronized long addMined(String playerKey, long amount) {
        if (playerKey == null || amount <= 0L) {
            return mined(playerKey);
        }
        PlayerData data = data(playerKey);
        data.mined = Math.min(Long.MAX_VALUE, data.mined + amount);
        dirty = true;
        return data.mined;
    }

    /** Выбор игрока «показывать ли звание рядом с ником». */
    public synchronized TreasureRankTags.Choice rankTag(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? TreasureRankTags.Choice.AUTO : data.rankTag;
    }

    /** Запоминает выбор игрока; {@code null} возвращает «авто». */
    public synchronized void setRankTag(String playerKey, TreasureRankTags.Choice choice) {
        PlayerData data = data(playerKey);
        data.rankTag = choice == null ? TreasureRankTags.Choice.AUTO : choice;
        dirty = true;
    }

    /** Баланс осколков игрока. */
    public synchronized long shards(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.shards;
    }

    /** Сколько осколков игрок потратил за всё время. */
    public synchronized long shardsSpent(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.shardsSpent;
    }

    /**
     * Начисляет осколки и возвращает новый баланс.
     *
     * <p>Идёт через то же хранилище, что и находки: баланс не может уехать
     * дальше {@link Long#MAX_VALUE}, а отрицательное начисление игнорируется —
     * списание делается только через {@link #takeShards(String, int)}.</p>
     */
    public synchronized long addShards(String playerKey, int amount) {
        if (playerKey == null || amount <= 0) {
            return shards(playerKey);
        }
        PlayerData data = data(playerKey);
        data.shards = Math.min(Long.MAX_VALUE, data.shards + amount);
        dirty = true;
        return data.shards;
    }

    /**
     * Списывает осколки, если их хватает.
     *
     * <p>Проверка и списание — одна синхронизированная операция: два быстрых
     * клика в меню не могут купить одно предложение дважды.</p>
     *
     * @return {@code true} — покупка оплачена, баланс уменьшен
     */
    public synchronized boolean takeShards(String playerKey, int amount) {
        if (playerKey == null || amount <= 0) {
            return false;
        }
        PlayerData data = players.get(playerKey);
        if (data == null || data.shards < amount) {
            return false;
        }
        data.shards -= amount;
        data.shardsSpent = Math.min(Long.MAX_VALUE, data.shardsSpent + amount);
        dirty = true;
        return true;
    }

    /**
     * Сбрасывает прогресс игрока: добычу, находки, коллекцию, вехи и серию.
     * Имя и настройки оформления остаются — после сброса игроку не нужно
     * заново выключать чужие поздравления.
     */
    public synchronized void reset(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) {
            return;
        }
        data.mined = 0L;
        data.tiers.clear();
        data.items.clear();
        data.milestones.clear();
        data.streak = 0;
        data.bestStreak = 0;
        data.lastFindAt = 0L;
        data.shards = 0L;
        data.shardsSpent = 0L;
        dirty = true;
    }

    /**
     * Сбрасывает прогресс всех игроков.
     *
     * @return сколько профилей было удалено
     */
    public synchronized int resetAll() {
        int removed = players.size();
        if (removed == 0) {
            return 0;
        }
        players.clear();
        dirty = true;
        return removed;
    }

    /** Отмечает находку: редкость и, если повезло, именной предмет. */
    public synchronized void recordTreasure(String playerKey, TreasureTier tier, String itemId) {
        recordTreasure(playerKey, tier, itemId, System.currentTimeMillis(), 0L);
    }

    /**
     * Отмечает находку и продлевает (или начинает заново) серию.
     *
     * @param now          время находки в миллисекундах
     * @param windowMillis допустимая пауза серии; 0 — серия всегда начинается с 1
     * @return длина серии после этой находки
     */
    public synchronized int recordTreasure(String playerKey, TreasureTier tier, String itemId,
                                           long now, long windowMillis) {
        if (playerKey == null || tier == null) {
            return 0;
        }
        PlayerData data = data(playerKey);
        data.tiers.merge(tier, 1L, Long::sum);
        if (itemId != null && !itemId.isBlank()) {
            data.items.merge(itemId, 1, Integer::sum);
        }
        long stamp = now > 0L ? now : System.currentTimeMillis();
        data.streak = TreasureStreak.next(data.streak, data.lastFindAt, stamp, windowMillis);
        data.lastFindAt = stamp;
        data.bestStreak = Math.max(data.bestStreak, data.streak);
        dirty = true;
        return data.streak;
    }

    public synchronized int streak(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.streak;
    }

    public synchronized int bestStreak(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.bestStreak;
    }

    public synchronized long lastFind(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.lastFindAt;
    }

    public synchronized long found(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.found();
    }

    public synchronized long tierCount(String playerKey, TreasureTier tier) {
        PlayerData data = players.get(playerKey);
        if (data == null || tier == null) {
            return 0L;
        }
        return data.tiers.getOrDefault(tier, 0L);
    }

    public synchronized int itemCount(String playerKey, String itemId) {
        PlayerData data = players.get(playerKey);
        return data == null || itemId == null ? 0 : data.items.getOrDefault(itemId, 0);
    }

    /** Сколько уникальных именных предметов найдено. */
    public synchronized int foundItems(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.foundItems();
    }

    public synchronized Set<String> itemIds(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.items.keySet());
    }

    public synchronized boolean announce(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null || data.announce;
    }

    public synchronized void setAnnounce(String playerKey, boolean value) {
        PlayerData data = data(playerKey);
        data.announce = value;
        dirty = true;
    }

    public synchronized boolean particles(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null || data.particles;
    }

    public synchronized void setParticles(String playerKey, boolean value) {
        PlayerData data = data(playerKey);
        data.particles = value;
        dirty = true;
    }

    public synchronized boolean sounds(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null || data.sounds;
    }

    public synchronized void setSounds(String playerKey, boolean value) {
        PlayerData data = data(playerKey);
        data.sounds = value;
        dirty = true;
    }

    public synchronized Set<Integer> milestones(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.milestones);
    }

    /** Отмечает веху коллекции. {@code true} — веха новая. */
    public synchronized boolean markMilestone(String playerKey, int found) {
        PlayerData data = data(playerKey);
        if (!data.milestones.add(found)) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Топ по числу находок. */
    public synchronized List<Map.Entry<String, Long>> topByFound(int limit) {
        List<Map.Entry<String, Long>> ranked = new ArrayList<>();
        for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
            ranked.add(Map.entry(entry.getKey(), entry.getValue().found()));
        }
        ranked.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        return List.copyOf(ranked.subList(0, Math.min(Math.max(0, limit), ranked.size())));
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        Map<String, Object> rows = new LinkedHashMap<>();
        players.forEach((key, player) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("name", player.name);
            fields.put("mined", player.mined);
            Map<String, Object> tiers = new LinkedHashMap<>();
            player.tiers.forEach((tier, count) -> {
                if (count > 0) {
                    tiers.put(tier.id(), count);
                }
            });
            fields.put("tiers", tiers);
            Map<String, Object> items = new LinkedHashMap<>();
            player.items.forEach((item, count) -> {
                if (count > 0) {
                    items.put(item, count);
                }
            });
            fields.put("items", items);
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("announce", player.announce);
            settings.put("particles", player.particles);
            settings.put("sounds", player.sounds);
            fields.put("settings", settings);
            fields.put("milestones", new ArrayList<>(player.milestones));
            fields.put("streak", player.streak);
            fields.put("best_streak", player.bestStreak);
            fields.put("last_find", player.lastFindAt);
            fields.put("shards", player.shards);
            fields.put("shards_spent", player.shardsSpent);
            fields.put("rank_tag", player.rankTag.id());
            rows.put(key, fields);
        });
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, если есть изменения (или его ещё нет). */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty && Files.isRegularFile(file)) {
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
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            move(temp, file);
        } catch (IOException exception) {
            logger.severe("[CHRDK_Treasures] Не удалось записать " + file + ": "
                    + exception.getMessage());
            synchronized (this) {
                dirty = true;
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // временный файл безвреден и перезапишется в следующий раз
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
