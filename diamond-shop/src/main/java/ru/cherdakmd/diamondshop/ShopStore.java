package ru.cherdakmd.diamondshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Персистентное состояние магазина (shop-data.json): алмазные балансы игроков, виртуальные
 * запасы товаров с меткой восстановления и накопленная статистика сделок.
 *
 * <p>Запись асинхронная (как у ядра): мутации — на главном потоке через synchronized-методы,
 * периодический {@link #saveAsync} снимает snapshot и пишет файл вне главного потока.</p>
 */
public final class ShopStore {

    /** Состояние одного товара: запас, метка времени восстановления и счётчики сделок. */
    public static final class ItemState {
        public long stock;
        public long recoveryAt;
        public long soldByPlayers;   // товар, влитый игроками В магазин
        public long boughtByPlayers; // товар, выкупленный игроками из магазина
        public double[] history = new double[0]; // mid-цены по тикам истории (старые → новые)
        long historyAt; // метка последнего тика истории
    }

    /** Дневное окно оборота игрока (для лимита антиманипуляции). */
    public static final class LimitRow {
        public long dayStart;
        public double used;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;

    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<String, ItemState> items = new HashMap<>();
    private final Map<UUID, LimitRow> limits = new HashMap<>();
    private double turnover;
    private boolean dirty;
    private volatile boolean legacyAvailable = true;
    public boolean legacyAvailable() { return legacyAvailable; }

    public ShopStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("shop-data.json");
        this.logger = logger;
    }

    /** Загружает состояние с диска (вызывается при включении плагина). */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) throw new IllegalArgumentException("Пустой денежный файл");
            if (data.get("balances") != null && !(data.get("balances") instanceof Map<?, ?>)) throw new IllegalArgumentException("Неверный balances");
            if (data.get("balances") instanceof Map<?, ?> rows) for (var entry : rows.entrySet()) {
                UUID.fromString(String.valueOf(entry.getKey()));
                if (!(entry.getValue() instanceof Number value) || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0)
                    throw new IllegalArgumentException("Неверная старая сумма: " + entry.getKey());
            }
            transferMap(data.get("balances"), balances, Double.class);
            Object itemsObj = data.get("items");
            if (itemsObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> state)) continue;
                    ItemState itemState = new ItemState();
                    itemState.stock = longOf(state.get("stock"));
                    itemState.recoveryAt = longOf(state.get("recoveryAt"));
                    itemState.soldByPlayers = longOf(state.get("soldByPlayers"));
                    itemState.boughtByPlayers = longOf(state.get("boughtByPlayers"));
                    Object historyObj = state.get("history");
                    if (historyObj instanceof List<?> list) {
                        double[] history = new double[list.size()];
                        for (int i = 0; i < list.size(); i++) {
                            history[i] = list.get(i) instanceof Number number ? number.doubleValue() : 0d;
                        }
                        itemState.history = history;
                    }
                    itemState.historyAt = longOf(state.get("historyAt"));
                    items.put(String.valueOf(entry.getKey()), itemState);
                }
            }
            Object limitsObj = data.get("limits");
            if (limitsObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> row)) continue;
                    try {
                        LimitRow limitRow = new LimitRow();
                        limitRow.dayStart = longOf(row.get("dayStart"));
                        limitRow.used = doubleOf(row.get("used"));
                        limits.put(UUID.fromString(String.valueOf(entry.getKey())), limitRow);
                    } catch (Exception ignored) {
                        // повреждённая запись — пропускаем
                    }
                }
            }
            turnover = doubleOf(data.get("turnover"));
            logger.info("[DiamondShop] Загружено: " + balances.size() + " балансов, "
                    + items.size() + " товаров, оборот " + turnover + " ⛁.");
        } catch (Exception e) {
            legacyAvailable = false;
            logger.severe("[DiamondShop] Не удалось прочитать " + file + ": " + e.getMessage());
        }
    }

    /* ------------------------------- балансы ------------------------------- */

    /** Только неизменяемый вход миграции. В новых сделках эти деньги не расходуются. */
    public synchronized Map<UUID, Double> legacyBalances() { return Map.copyOf(balances); }
    public synchronized boolean canRecordTurnover(UUID id, double amount, double limit, long now) {
        return Double.isFinite(amount) && amount > 0 && (limit <= 0 || usedToday(id, now) + amount <= limit);
    }
    public synchronized double balanceOf(UUID uuid) {
        return balances.getOrDefault(uuid, 0d);
    }

    public synchronized void addBalance(UUID uuid, double amount) {
        balances.merge(uuid, amount, Double::sum);
        dirty = true;
    }

    /** Списывает средства, если хватает; иначе ничего не делает. */
    public synchronized boolean trySpend(UUID uuid, double amount) {
        double current = balanceOf(uuid);
        if (current + 1e-9 < amount) return false;
        balances.put(uuid, PriceEngine.round(current - amount));
        dirty = true;
        return true;
    }

    /* ------------------------------- запасы ------------------------------- */

    /** Состояние товара с ленивой инициализацией под эталонный запас. */
    public synchronized ItemState itemState(ShopConfig.ShopItem item, long nowMillis) {
        ItemState state = items.computeIfAbsent(item.material(), key -> {
            ItemState created = new ItemState();
            created.stock = item.refStock();
            created.recoveryAt = nowMillis;
            return created;
        });
        return state;
    }

    /** Лениво применяет возврат запаса к эталону и возвращает актуальный stock. */
    public synchronized long stockOf(ShopConfig.ShopItem item, long nowMillis, double recoveryPerHour) {
        ItemState state = itemState(item, nowMillis);
        long stock = PriceEngine.recover(state.stock, item.refStock(), recoveryPerHour, nowMillis - state.recoveryAt);
        if (stock != state.stock) {
            state.stock = stock;
            state.recoveryAt = nowMillis;
            dirty = true;
        }
        return Math.max(1, stock);
    }

    /** Применяет изменение запаса после сделки (дельта < 0 при покупке игроком). */
    public synchronized void adjustStock(ShopConfig.ShopItem item, long delta, long nowMillis, double recoveryPerHour) {
        ItemState state = itemState(item, nowMillis);
        stockOf(item, nowMillis, recoveryPerHour);
        state.stock = Math.max(1, state.stock + delta);
        state.recoveryAt = nowMillis;
        dirty = true;
    }

    public synchronized void setStock(ShopConfig.ShopItem item, long value, long nowMillis) {
        ItemState state = itemState(item, nowMillis);
        state.stock = Math.max(1, value);
        state.recoveryAt = nowMillis;
        dirty = true;
    }

    /** Записывает сделку в статистику (units — штуки товара). */
    public synchronized void recordTrade(ShopConfig.ShopItem item, long units, boolean playerSold, double revenue) {
        ItemState state = itemState(item, System.currentTimeMillis());
        if (playerSold) state.soldByPlayers += units;
        else state.boughtByPlayers += units;
        turnover = PriceEngine.round(turnover + revenue);
        dirty = true;
    }

    public synchronized double turnover() {
        return turnover;
    }

    /** Snapshot статистики товара для вывода (копия полей). */
    public synchronized ItemState snapshotOf(String material) {
        ItemState state = items.get(material);
        if (state == null) return null;
        ItemState copy = new ItemState();
        copy.stock = state.stock;
        copy.recoveryAt = state.recoveryAt;
        copy.soldByPlayers = state.soldByPlayers;
        copy.boughtByPlayers = state.boughtByPlayers;
        return copy;
    }

    /* ------------------------------- сохранение ------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Сериализованный snapshot (из синхронизированного контекста; писать можно асинхронно). */
    /* ------------------------------- история цен ------------------------------- */

    /**
     * Добавляет точку mid-цены в историю товара. Если с прошлой точки прошло меньше
     * {@code minIntervalMillis}, обновляет саму последнюю точку (сделки не плодят шум).
     */
    public synchronized void recordPrice(ShopConfig.ShopItem item, double price, long nowMillis,
                                         int maxPoints, long minIntervalMillis) {
        ItemState state = itemState(item, nowMillis);
        if (state.history.length > 0 && nowMillis - state.historyAt < minIntervalMillis) {
            state.history[state.history.length - 1] = price; // тот же тик — обновляем значение
        } else {
            int keep = Math.min(maxPoints - 1, state.history.length);
            double[] next = new double[keep + 1];
            System.arraycopy(state.history, state.history.length - keep, next, 0, keep);
            next[keep] = price;
            state.history = next;
            state.historyAt = nowMillis;
        }
        dirty = true;
    }

    /** Вся накопленная история товара (копия массива). */
    public synchronized double[] historyOf(String material) {
        ItemState state = items.get(material);
        return state == null ? new double[0] : state.history.clone();
    }

    /* ------------------------------- дневной лимит оборота ------------------------------- */

    /**
     * Пытается записать оборот в дневное окно игрока. {@code limit <= 0} — лимит выключен.
     * Окно — календарные сутки UTC.
     */
    public synchronized boolean tryRecordTurnover(UUID uuid, double amount, double limit, long nowMillis) {
        if (limit <= 0) return true;
        long today = nowMillis - Math.floorMod(nowMillis, 86_400_000L);
        LimitRow row = limits.get(uuid);
        if (row == null || row.dayStart != today) {
            row = new LimitRow();
            row.dayStart = today;
            limits.put(uuid, row);
        }
        if (row.used + amount > limit + 1e-9) {
            dirty = true; // окно могло пересоздаться
            return false;
        }
        row.used = PriceEngine.round(row.used + amount);
        dirty = true;
        return true;
    }

    /** Сколько оборота игрок «сжёг» сегодня. */
    public synchronized double usedToday(UUID uuid, long nowMillis) {
        LimitRow row = limits.get(uuid);
        long today = nowMillis - Math.floorMod(nowMillis, 86_400_000L);
        return row == null || row.dayStart != today ? 0d : row.used;
    }

    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Double> balancesCopy = new LinkedHashMap<>();
        balances.forEach((uuid, value) -> balancesCopy.put(uuid.toString(), value));
        data.put("balances", balancesCopy);
        Map<String, Object> itemsCopy = new LinkedHashMap<>();
        items.forEach((material, state) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stock", state.stock);
            fields.put("recoveryAt", state.recoveryAt);
            fields.put("soldByPlayers", state.soldByPlayers);
            fields.put("boughtByPlayers", state.boughtByPlayers);
            fields.put("history", state.history);
            fields.put("historyAt", state.historyAt);
            itemsCopy.put(material, fields);
        });
        data.put("items", itemsCopy);
        Map<String, Object> limitsCopy = new LinkedHashMap<>();
        limits.forEach((uuid, row) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("dayStart", row.dayStart);
            fields.put("used", row.used);
            limitsCopy.put(uuid.toString(), fields);
        });
        data.put("limits", limitsCopy);
        data.put("turnover", turnover);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет snapshot в файл из асинхронного контекста, если были изменения. */
    public void saveAsync() {
        saveNow();
    }

    /** Синхронная запись (использовать при выключении и в тестах). */
    public void saveNow() {
        if (!legacyAvailable) return; // Не перезаписываем повреждённый денежный источник пустым состоянием.
        if (!dirty && Files.isRegularFile(file)) return;
        String json = snapshotJson();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (IOException e) {
            logger.severe("[DiamondShop] Не удалось записать " + file + ": " + e.getMessage());
            dirty = true;
        }
    }

    /* ------------------------------- утилиты парсинга ------------------------------- */

    @SuppressWarnings("unchecked")
    private static <T> void transferMap(Object source, Map<UUID, T> target, Class<T> type) {
        if (!(source instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            try {
                target.put(UUID.fromString(String.valueOf(entry.getKey())),
                        type.cast(Double.class.cast(entry.getValue())));
            } catch (Exception ignored) {
                // повреждённая запись — пропускаем
            }
        }
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }
}
