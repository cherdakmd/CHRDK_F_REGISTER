package ru.cherdakmd.chrdkskills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Данные навыков (`skills.json`).
 *
 * <p>Формат: {@code {"version":1,"players":{"<uuid>":{"name":"Steve",
 * "xp":{"mining":1234},"milestones":[10,25]}}}}. Хранится опыт, ник для топа и
 * выданные вехи — уровень всегда выводится из опыта по {@link SkillCurve},
 * поэтому смена кривой в конфиге не ломает данные и не требует миграции.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}; синхронизация
 * на хранилище, потому что запись идёт с другого потока.</p>
 *
 * <p>Класс чистый (Gson + NIO) — проверяется юнит-тестами без Bukkit.</p>
 */
public final class SkillStore {

    /** Прогресс одного игрока. */
    public static final class PlayerData {
        public final Map<SkillType, Long> xp = new EnumMap<>(SkillType.class);
        public final Set<Integer> milestones = new TreeSet<>();
        /** Последний известный ник — чтобы топ не ходил за профилями. */
        public String name = "";

        public long xp(SkillType skill) {
            return xp.getOrDefault(skill, 0L);
        }

        public long totalXp() {
            long total = 0L;
            for (long value : xp.values()) {
                total += value;
            }
            return total;
        }
    }

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL).setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, PlayerData> players = new LinkedHashMap<>();
    private boolean dirty;

    public SkillStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("skills.json");
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
                if (row.get("xp") instanceof Map<?, ?> xpRows) {
                    for (Map.Entry<?, ?> xp : xpRows.entrySet()) {
                        SkillType skill = SkillType.byId(String.valueOf(xp.getKey()));
                        if (skill != null && xp.getValue() instanceof Number number) {
                            long value = number.longValue();
                            if (value > 0L) {
                                player.xp.put(skill, value);
                            }
                        }
                    }
                }
                Object name = row.get("name");
                player.name = name == null ? "" : String.valueOf(name).trim();
                if (row.get("milestones") instanceof List<?> milestones) {
                    for (Object element : milestones) {
                        if (element instanceof Number number) {
                            player.milestones.add(Math.max(1, number.intValue()));
                        }
                    }
                }
                players.put(String.valueOf(entry.getKey()), player);
            }
            logger.info("[CHRDK_Skills] Загружено профилей: " + players.size() + ".");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Skills] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    public synchronized long xp(String playerKey, SkillType skill) {
        PlayerData data = players.get(playerKey);
        return data == null || skill == null ? 0L : data.xp(skill);
    }

    /** Начисляет опыт; отрицательные приращения игнорируются. */
    public synchronized long addXp(String playerKey, SkillType skill, long amount) {
        if (playerKey == null || skill == null || amount <= 0L) {
            return xp(playerKey, skill);
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        long next = data.xp(skill) + amount;
        data.xp.put(skill, next);
        dirty = true;
        return next;
    }

    /** Прямая установка опыта (админская команда). */
    public synchronized void setXp(String playerKey, SkillType skill, long amount) {
        if (playerKey == null || skill == null) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.xp.put(skill, Math.max(0L, amount));
        dirty = true;
    }

    /** Опыт по всем навыкам разом. */
    public synchronized Map<SkillType, Long> allXp(String playerKey) {
        PlayerData data = players.get(playerKey);
        Map<SkillType, Long> out = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) {
            out.put(skill, data == null ? 0L : data.xp(skill));
        }
        return out;
    }

    /** Последний известный ник игрока (пустая строка — не записан). */
    public synchronized String name(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? "" : data.name;
    }

    /** Обновляет ник, если он изменился. */
    public synchronized void setName(String playerKey, String name) {
        if (playerKey == null || name == null || name.isBlank()) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (name.equals(data.name)) {
            return;
        }
        data.name = name.trim();
        dirty = true;
    }

    /** Топ по суммарному опыту всех навыков. */
    public synchronized List<Map.Entry<String, Long>> topByTotalXp(int limit) {
        List<Map.Entry<String, Long>> ranked = new ArrayList<>();
        for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
            ranked.add(Map.entry(entry.getKey(), entry.getValue().totalXp()));
        }
        ranked.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        return List.copyOf(ranked.subList(0, Math.min(Math.max(0, limit), ranked.size())));
    }

    public synchronized Set<Integer> milestones(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.milestones);
    }

    /** Отмечает веху выданной. {@code true} — веха новая. */
    public synchronized boolean markMilestone(String playerKey, int level) {
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!data.milestones.add(level)) {
            return false;
        }
        dirty = true;
        return true;
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
            Map<String, Object> xpRows = new LinkedHashMap<>();
            player.xp.forEach((skill, value) -> {
                if (value > 0L) {
                    xpRows.put(skill.id(), value);
                }
            });
            fields.put("name", player.name);
            fields.put("xp", xpRows);
            fields.put("milestones", new ArrayList<>(player.milestones));
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
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Skills]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }


}
