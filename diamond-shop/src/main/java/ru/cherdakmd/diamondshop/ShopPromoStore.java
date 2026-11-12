package ru.cherdakmd.diamondshop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Прогресс дневных акций в отдельном файле {@code promotions-data.json}.
 *
 * <p>Важно: НЕ новый root-ключ в {@code shop-data.json}. Строгий парсер рынка
 * остаётся нетронутым, а состояние акций живёт рядом со своим файлом. Сам
 * бонус при этом не эмитируется отдельно: он входит в {@code money}/{@code credit}
 * обычной сделки и проходит через тот же WAL и {@link ShopJournal}.</p>
 *
 * <p>Счётчики сбрасываются при смене календарных суток UTC. Повреждённый или
 * неполный файл читается как пустой, а не роняет магазин: худший исход —
 * акция не начислит бонус, но сделка и деньги остаются корректными.</p>
 */
final class ShopPromoStore {

    static final String FILE_NAME = "promotions-data.json";
    private static final int SCHEMA = 1;
    private static final Gson GSON = new Gson();

    /** Неизменяемый снимок прогресса игрока на конкретные сутки. */
    record Snapshot(long bonus, Map<String, Long> units, Set<String> kinds,
                    boolean contractPaid, boolean seriesPaid) {
        static Snapshot empty() {
            return new Snapshot(0, Map.of(), Set.of(), false, false);
        }

        long soldOf(String material) {
            Long value = units.get(material);
            return value == null ? 0 : value;
        }

        int distinct() { return kinds.size(); }
    }

    private static final class Progress {
        long bonus;
        final Map<String, Long> units = new LinkedHashMap<>();
        final Set<String> kinds = new LinkedHashSet<>();
        boolean contractPaid;
        boolean seriesPaid;

        Snapshot snapshot() {
            return new Snapshot(bonus, Map.copyOf(units), Set.copyOf(kinds), contractPaid, seriesPaid);
        }
    }

    private final Path file;
    private final Logger logger;
    private long day;
    private final Map<String, Progress> players = new LinkedHashMap<>();

    ShopPromoStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve(FILE_NAME);
        this.logger = logger;
        this.day = ShopPromoMath.dayIndex(System.currentTimeMillis());
    }

    synchronized void load() {
        players.clear();
        day = ShopPromoMath.dayIndex(System.currentTimeMillis());
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA) {
                throw new IllegalArgumentException("unknown schema");
            }
            // Сохраняем прочитанные сутки как есть: rollDay() сам очистит их при
            // первом обращении в новые календарные сутки. Сравнение с «реальным
            // сейчас» здесь было бы неверным при восстановлении после рестарта.
            day = root.get("day").getAsLong();
            JsonObject rows = root.getAsJsonObject("players");
            if (rows == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : rows.entrySet()) {
                Progress progress = parse(entry.getValue());
                if (progress != null) {
                    // Валидный UUID — защита от произвольных ключей.
                    UUID.fromString(entry.getKey());
                    players.put(entry.getKey(), progress);
                }
            }
        } catch (RuntimeException | IOException error) {
            players.clear();
            logger.warning("[Акции] Прогресс не прочитан (" + error.getClass().getSimpleName()
                    + ") — сутки начаты с чистого листа, сделки не затронуты.");
        }
    }

    private static Progress parse(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject row = element.getAsJsonObject();
        try {
            Progress progress = new Progress();
            progress.bonus = decimal(row.get("bonus")).longValueExact();
            if (progress.bonus < 0) {
                return null;
            }
            progress.contractPaid = row.has("contractPaid") && row.get("contractPaid").getAsBoolean();
            progress.seriesPaid = row.has("seriesPaid") && row.get("seriesPaid").getAsBoolean();
            JsonObject units = row.getAsJsonObject("units");
            if (units != null) {
                for (Map.Entry<String, JsonElement> cell : units.entrySet()) {
                    long value = decimal(cell.getValue()).longValueExact();
                    if (value > 0) {
                        progress.units.put(cell.getKey(), value);
                        progress.kinds.add(cell.getKey());
                    }
                }
            }
            return progress;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("expected number");
        }
        return value.getAsBigDecimal();
    }

    synchronized Snapshot snapshot(UUID player, long nowMillis) {
        rollDay(nowMillis);
        Progress progress = players.get(player.toString());
        return progress == null ? Snapshot.empty() : progress.snapshot();
    }

    synchronized long bonusPaid(UUID player, long nowMillis) {
        return snapshot(player, nowMillis).bonus();
    }

    /** Атомарно резервирует суточный бонус ДО принятия денежной сделки. */
    synchronized boolean reserve(UUID player, long nowMillis, long bonus) {
        if (bonus <= 0) return true;
        rollDay(nowMillis);
        Progress progress = players.computeIfAbsent(player.toString(), ignored -> new Progress());
        long before = progress.bonus;
        progress.bonus = Math.addExact(before, bonus);
        if (save()) return true;
        progress.bonus = before;
        return false;
    }

    /** Снимает резерв отклонённой сделки; при ошибке записи резерв сохраняется fail-closed. */
    synchronized void release(UUID player, long nowMillis, long bonus) {
        if (bonus <= 0) return;
        rollDay(nowMillis);
        Progress progress = players.get(player.toString());
        if (progress == null) return;
        long before = progress.bonus;
        progress.bonus = Math.max(0, before - bonus);
        if (!save()) progress.bonus = before;
    }

    /** Фиксирует успешную сделку; денежный резерв уже создан через reserve(). */
    synchronized void commit(UUID player, long nowMillis, String material, long units,
                            long bonus, boolean contractPaid, boolean seriesPaid) {
        rollDay(nowMillis);
        Progress progress = players.computeIfAbsent(player.toString(), ignored -> new Progress());
        progress.bonus = Math.max(0, progress.bonus + Math.max(0, bonus));
        if (units > 0) {
            progress.units.merge(material, units, Long::sum);
            progress.kinds.add(material);
        }
        progress.contractPaid |= contractPaid;
        progress.seriesPaid |= seriesPaid;
        save();
    }

    private void rollDay(long nowMillis) {
        long current = ShopPromoMath.dayIndex(nowMillis);
        if (current != day) {
            day = current;
            players.clear();
        }
    }

    private synchronized boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("day", day);
        JsonObject rows = new JsonObject();
        for (Map.Entry<String, Progress> entry : players.entrySet()) {
            Progress progress = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("bonus", progress.bonus);
            row.addProperty("contractPaid", progress.contractPaid);
            row.addProperty("seriesPaid", progress.seriesPaid);
            JsonObject units = new JsonObject();
            progress.units.forEach(units::addProperty);
            row.add("units", units);
            rows.add(entry.getKey(), row);
        }
        root.add("players", rows);
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            // Резерв откатывается в памяти; сделка продолжится без бонуса.
            logger.warning("[Акции] Не удалось сохранить прогресс; доплата закрыта: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }
}
