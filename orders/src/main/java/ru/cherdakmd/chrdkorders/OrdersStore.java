package ru.cherdakmd.chrdkorders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Данные доски заказов ({@code orders.json}): сами заказы и прогресс игроков.
 *
 * <p>Заказы глобальные: доска одна на сервер, бот не выбирает конкретного
 * исполнителя. Поэтому в файле лежит плоский список заказов и карта игроков
 * (UUID → сдачи, репутация, дневной счётчик). Сутки считаются по календарным
 * суткам UTC, чтобы «сегодня» не зависело от часового пояса сервера и не
 * прыгало при переезде.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}; синхронизация
 * на хранилище, потому что запись идёт с другого потока. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class OrdersStore {

    /** Сутки в миллисекундах — для дневного лимита. */
    public static final long DAY_MILLIS = 86_400_000L;

    /** Учёт одного игрока. Мутируется только методами хранилища. */
    public static final class PlayerData {
        private String name = "";
        private int completed;
        private int reputation;
        private long day;
        private int today;
        private long lastDelivery;

        private PlayerData(String name) {
            this.name = name == null ? "" : name;
        }

        public String name() {
            return name;
        }

        public int completed() {
            return completed;
        }

        public int reputation() {
            return reputation;
        }

        /** День (номер суток UTC), к которому относится {@link #today()}. */
        public long day() {
            return day;
        }

        public int today() {
            return today;
        }

        public long lastDelivery() {
            return lastDelivery;
        }
    }

    /** Строка рейтинга: без Bukkit-типов, чтобы её можно было показать где угодно. */
    public record Row(UUID id, String name, int completed, int reputation) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Comparator<Row> TOP_ORDER =
            Comparator.comparingInt(Row::completed).reversed()
                    .thenComparing(Comparator.comparingInt(Row::reputation).reversed())
                    .thenComparing(Row::name);

    private final Path file;
    private final Logger logger;
    private final Map<String, BotOrder> orders = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private long nextId = 1L;
    private boolean dirty;

    public OrdersStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("orders.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые строки пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            long storedId = number(data.get("next-id"));
            nextId = Math.max(1L, storedId);

            int skipped = 0;
            if (data.get("orders") instanceof List<?> rows) {
                for (Object row : rows) {
                    BotOrder order = row instanceof Map<?, ?> map ? parseOrder(map) : null;
                    if (order == null) {
                        skipped++;
                        continue;
                    }
                    orders.put(order.id(), order);
                    // Номер заказа должен быть больше любого уже сохранённого:
                    // иначе после ручной правки файла новый заказ затрёт старый.
                    long numeric = parseLong(order.id());
                    if (numeric >= nextId) {
                        nextId = numeric + 1;
                    }
                }
            }
            if (data.get("players") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    PlayerData player = new PlayerData(string(row.get("name")));
                    player.completed = (int) Math.min(Integer.MAX_VALUE,
                            number(row.get("completed")));
                    player.reputation = (int) Math.min(Integer.MAX_VALUE,
                            number(row.get("reputation")));
                    player.day = number(row.get("day"));
                    player.today = (int) Math.min(Integer.MAX_VALUE, number(row.get("today")));
                    player.lastDelivery = number(row.get("last-delivery"));
                    players.put(id, player);
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Orders] Пропущено записей orders.json: " + skipped + ".");
            }
            if (logger != null) {
                logger.info("[CHRDK_Orders] Загружено заказов: " + orders.size()
                        + ", игроков в базе: " + players.size() + ".");
            }
        } catch (Exception exception) {
            if (logger != null) {
                logger.severe("[CHRDK_Orders] Не удалось прочитать " + file + ": "
                        + exception.getMessage());
            }
        }
    }

    private BotOrder parseOrder(Map<?, ?> row) {
        String id = string(row.get("id")).trim();
        String material = string(row.get("material")).trim();
        long amount = number(row.get("amount"));
        if (id.isEmpty() || material.isEmpty() || amount <= 0L) {
            return null;
        }
        try {
            return new BotOrder(
                    id,
                    string(row.get("bot")),
                    string(row.get("bot-name")),
                    string(row.get("category")),
                    material,
                    (int) Math.min(576L, amount),
                    (int) number(row.get("reward")),
                    passHoursOf(row),
                    (int) number(row.get("min-reputation")),
                    (int) number(row.get("reputation")),
                    number(row.get("created")),
                    number(row.get("expires")));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /* --------------------------------- заказы --------------------------------- */

    /** Заказы в порядке появления (номер заказа растёт, доска читается слева направо). */
    public synchronized List<BotOrder> orders() {
        return List.copyOf(orders.values());
    }

    public synchronized BotOrder order(String id) {
        return id == null ? null : orders.get(id.trim());
    }

    public synchronized int size() {
        return orders.size();
    }

    /** Резервирует следующий номер заказа. */
    public synchronized String reserveId() {
        long id = Math.max(1L, nextId);
        nextId = id + 1L;
        dirty = true;
        return Long.toString(id);
    }

    public synchronized void add(BotOrder order) {
        if (order == null || order.id().isEmpty()) {
            return;
        }
        orders.put(order.id(), order);
        dirty = true;
    }

    /** Снимает заказ с доски; {@code true} — он был. */
    public synchronized boolean remove(String id) {
        if (id == null) {
            return false;
        }
        boolean removed = orders.remove(id.trim()) != null;
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Убирает истёкшие заказы; возвращает число снятых. */
    public synchronized int purgeExpired(long now) {
        int removed = 0;
        var iterator = orders.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now)) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    /** Полная очистка доски; возвращает число снятых заказов. */
    public synchronized int clear() {
        int removed = orders.size();
        if (removed > 0) {
            orders.clear();
            dirty = true;
        }
        return removed;
    }

    /* -------------------------------- игроки ---------------------------------- */

    /** Запись игрока или {@code null}, если он ещё ничего не сдавал. */
    public synchronized PlayerData player(UUID id) {
        return id == null ? null : players.get(id);
    }

    /** Запись игрока с созданием; ник обновляется при каждой сдаче. */
    public synchronized PlayerData ensure(UUID id, String name) {
        if (id == null) {
            return null;
        }
        PlayerData data = players.get(id);
        if (data == null) {
            data = new PlayerData(name);
            players.put(id, data);
            dirty = true;
            return data;
        }
        if (name != null && !name.isBlank() && !name.equals(data.name)) {
            data.name = name.trim();
            dirty = true;
        }
        return data;
    }

    /** Строка игрока по нику без учёта регистра — для {@code /orders stats <ник>} офлайн. */
    public synchronized Row byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.name.equalsIgnoreCase(wanted)) {
                return new Row(entry.getKey(), data.name, data.completed, data.reputation);
            }
        }
        return null;
    }

    public synchronized int reputation(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0 : data.reputation;
    }

    public synchronized int completed(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0 : data.completed;
    }

    /**
     * Сколько заказов игрок сдал «сегодня» (сутки UTC).
     *
     * <p>Счётчик не сбрасывается по таймеру: если день в файле старее
     * текущего, ответ — 0. Так не нужен отдельный ночной проход по игрокам.</p>
     */
    public synchronized int today(UUID id, long now) {
        PlayerData data = player(id);
        if (data == null) {
            return 0;
        }
        return data.day == dayOf(now) ? data.today : 0;
    }

    public synchronized long lastDelivery(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0L : data.lastDelivery;
    }

    /** Записывает сдачу: счётчики, репутация и «сегодня». */
    public synchronized void recordDelivery(UUID id, String name, long now, int reputationGain) {
        PlayerData data = ensure(id, name);
        if (data == null) {
            return;
        }
        long day = dayOf(now);
        if (data.day != day) {
            data.day = day;
            data.today = 0;
        }
        data.completed++;
        data.today++;
        data.reputation = Math.max(0, data.reputation + Math.max(0, reputationGain));
        data.lastDelivery = now;
        dirty = true;
    }

    /** Админская правка репутации без смены ника: имя берётся из записи. */
    public synchronized void setReputation(UUID id, int value) {
        PlayerData data = player(id);
        setReputation(id, data == null ? "" : data.name, value);
    }

    /** Админская правка репутации (клампится снизу нулём). */
    public synchronized void setReputation(UUID id, String name, int value) {
        PlayerData data = ensure(id, name);
        if (data == null) {
            return;
        }
        data.reputation = Math.max(0, value);
        dirty = true;
    }

    /** Топ по сданным заказам, при равенстве — по репутации. */
    public synchronized List<Row> top(int limit) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.completed <= 0) {
                continue;
            }
            rows.add(new Row(entry.getKey(), data.name, data.completed, data.reputation));
        }
        rows.sort(TOP_ORDER);
        int size = Math.max(0, Math.min(limit, rows.size()));
        return List.copyOf(rows.subList(0, size));
    }

    public synchronized int players() {
        return players.size();
    }

    /** Всего сдач за всё время — для админской сводки. */
    public synchronized long totalCompleted() {
        long total = 0L;
        for (PlayerData data : players.values()) {
            total += data.completed;
        }
        return total;
    }

    /* -------------------------------- запись ---------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("next-id", nextId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BotOrder order : orders.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.id());
            row.put("bot", order.botId());
            row.put("bot-name", order.botName());
            row.put("category", order.category());
            row.put("material", order.material());
            row.put("amount", order.amount());
            row.put("reward", order.rewardDiamonds());
            row.put("pass-hours", order.rewardPassHours());
            row.put("min-reputation", order.minReputation());
            row.put("reputation", order.reputation());
            row.put("created", order.createdAt());
            row.put("expires", order.expiresAt());
            rows.add(row);
        }
        data.put("orders", rows);
        Map<String, Object> playerRows = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData player = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.name);
            row.put("completed", player.completed);
            row.put("reputation", player.reputation);
            row.put("day", player.day);
            row.put("today", player.today);
            row.put("last-delivery", player.lastDelivery);
            playerRows.put(entry.getKey().toString(), row);
        }
        data.put("players", playerRows);
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
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Orders]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }
    /** Номер суток UTC — так «сегодня» одинаково на любом сервере. */
    public static long dayOf(long now) {
        return Math.max(0L, now) / DAY_MILLIS;
    }

    /** Сколько миллисекунд осталось до конца текущих суток UTC. */
    public static long millisToNextDay(long now) {
        long left = DAY_MILLIS - (Math.max(0L, now) % DAY_MILLIS);
        return left <= 0L ? DAY_MILLIS : left;
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Часы ранга из строки доски. Старые доски писали ключ {@code pass-days}:
     * читаем его как часы, чтобы ожидающие заказы не потеряли награду при
     * обновлении до 1.4.184 (сутки в старом ключе теперь не выдаются).
     */
    private static int passHoursOf(Map<?, ?> row) {
        Object value = row.get("pass-hours");
        if (value == null) {
            value = row.get("pass-days");
        }
        return (int) number(value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
