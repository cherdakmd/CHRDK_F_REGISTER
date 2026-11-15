package ru.cherdakmd.chrdkskills;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Конвертация классового прогресса в очки общего пула (п.29): модуль classes
 * вырезан, но данные продакшена не трогаем — файл classes.json из папки
 * CHRDK_Classes читается только на чтение, а игроки получают очки атрибутов и
 * веток за уровни класса: одно очко за каждые два уровня, не больше
 * {@link #MAX_POINTS}. Идемпотентно: конвертированные игроки помечены в
 * skills.json ({@code classConverted}).
 */
final class ClassProgressMigrator {

    /** Потолок конвертации с одного классового прогресса. */
    static final int MAX_POINTS = 25;
    /** Классовых уровней на одно очко общего пула. */
    static final int LEVELS_PER_POINT = 2;
    /** Кривая класса — 50 уровней (как в вырезанном ClassDevelopmentRules). */
    static final int MAX_LEVEL = 50;

    private ClassProgressMigrator() { }

    /** Очки пула за классовый XP. */
    static int pointsFor(long classXp) {
        return Math.min(MAX_POINTS, classLevel(classXp) / LEVELS_PER_POINT);
    }

    /** Уровень класса по его XP-кривой: шаг уровня 100 + 40·(n−1) + 8·(n−1)². */
    static int classLevel(long xp) {
        if (xp <= 0) {
            return 0;
        }
        long threshold = 0;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            if (xp < threshold) {
                return level - 1;
            }
            long offset = level - 1L;
            threshold += 100L + 40L * offset + 8L * offset * offset;
        }
        return MAX_LEVEL;
    }

    /**
     * Читает classes.json (только чтение!) и начисляет очки. Повреждённая
     * строка или файл не ломают загрузку навыков: конвертация просто пропускается.
     */
    static void run(SkillStore store, Path dataFolder, Logger log) {
        Path file = dataFolder.resolve("CHRDK_Classes").resolve("classes.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("players") || !root.get("players").isJsonObject()) {
                return;
            }
            int converted = 0;
            int granted = 0;
            for (var entry : root.getAsJsonObject("players").entrySet()) {
                try {
                    String key = UUID.fromString(entry.getKey()).toString();
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    int due = pointsFor(classXp(entry.getValue().getAsJsonObject()));
                    if (due > 0 && store.migrateClassPoints(key, due)) {
                        converted++;
                        granted += due;
                    }
                } catch (RuntimeException ignored) {
                    // одна битая строка не отменяет конвертацию остальных игроков
                }
            }
            if (converted > 0) {
                log.info("[CHRDK_Skills] Классовый прогресс конвертирован в очки пула: "
                        + converted + " игроков, " + granted + " очков.");
            }
        } catch (Exception error) {
            log.warning("[CHRDK_Skills] classes.json не прочитан, конвертация пропущена: "
                    + error.getMessage());
        }
    }

    /** Суммарный XP классов из строки профиля: {@code development.xp}. */
    private static long classXp(JsonObject row) {
        if (row.has("development") && row.get("development").isJsonObject()) {
            JsonObject development = row.getAsJsonObject("development");
            if (development.has("xp") && development.get("xp").isJsonPrimitive()) {
                return development.get("xp").getAsLong();
            }
        }
        return 0L;
    }
}
