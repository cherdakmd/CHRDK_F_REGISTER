package ru.cherdakmd.diamondshop;

import com.google.gson.*;
import com.google.gson.GsonBuilder;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Состояние магазина (shop-data.json): неизменяемый вход старых балансов, виртуальные
 * запасы товаров с меткой восстановления и накопленная статистика сделок.
 *
 * <p>Запись асинхронная (как у ядра): мутации — на главном потоке через synchronized-методы,
 * периодический {@link #saveAsync} пишет force+atomic replace под общим монитором.
 * Snapshot не снимает dirty; IO/runtime-неопределённость останавливает writer до restart/сверки.</p>
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
    @FunctionalInterface interface WriteCheckpoint { void afterReplace() throws IOException; }
    private final WriteCheckpoint checkpoint;

    private final Path file;
    private final Logger logger;

    private final Map<UUID, java.math.BigDecimal> balances = new HashMap<>();
    private final Map<String, ItemState> items = new HashMap<>();
    private final Map<UUID, LimitRow> limits = new HashMap<>();
    private double turnover;
    private boolean dirty;
    private volatile boolean legacyAvailable = true;
    public boolean legacyAvailable() { return legacyAvailable; }

    public ShopStore(Path dataFolder, Logger logger) {
        this(dataFolder, logger, () -> { });
    }
    ShopStore(Path dataFolder, Logger logger, WriteCheckpoint checkpoint) {
        this.file = dataFolder.resolve("shop-data.json");
        this.logger = logger;
        this.checkpoint = checkpoint;
    }

    /** Строго читаем весь источник до публикации. Битую строку/лимит не пропускаем. */
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            if (!Files.isRegularFile(file)) throw new IOException("shop-data.json не является файлом");
            var data=ShopJson.read(file);
            for(String key:data.keySet()) if(!java.util.Set.of("balances","items","limits","turnover").contains(key)) throw new IllegalArgumentException("Неизвестное поле магазина: "+key);
            var nextBalances=new HashMap<UUID,java.math.BigDecimal>();
            var nextItems=new HashMap<String,ItemState>();
            var nextLimits=new HashMap<UUID,LimitRow>();
            for(var entry:table(data,"balances",true).entrySet()) nextBalances.put(uuid(entry.getKey()),legacyDecimal(entry.getValue()));
            for(var entry:table(data,"items",false).entrySet()) {
                if(!entry.getKey().matches("[A-Z][A-Z0-9_]{0,127}")||!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Неверный товар");
                var row=entry.getValue().getAsJsonObject(); var state=new ItemState();
                state.stock=integer(row,"stock",true); if(state.stock<1) throw new IllegalArgumentException("stock < 1");
                state.recoveryAt=integer(row,"recoveryAt",true);
                state.soldByPlayers=integer(row,"soldByPlayers",true); state.boughtByPlayers=integer(row,"boughtByPlayers",true);
                state.historyAt=integer(row,"historyAt",false);
                if(row.has("history")) {
                    if(!row.get("history").isJsonArray()) throw new IllegalArgumentException("Неверная история");
                    var array=row.getAsJsonArray("history"); if(array.size()>4096) throw new IllegalArgumentException("Слишком длинная история");
                    state.history=new double[array.size()]; for(int i=0;i<array.size();i++) state.history[i]=decimal(array.get(i));
                }
                nextItems.put(entry.getKey(),state);
            }
            for(var entry:table(data,"limits",false).entrySet()) {
                var id=uuid(entry.getKey()); if(!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Неверный лимит");
                var row=entry.getValue().getAsJsonObject(); var limit=new LimitRow();
                limit.dayStart=integer(row,"dayStart",true); if(Math.floorMod(limit.dayStart,86_400_000L)!=0) throw new IllegalArgumentException("Не сутки UTC");
                limit.used=decimal(row.get("used")); nextLimits.put(id,limit);
            }
            double nextTurnover=data.has("turnover")?decimal(data.get("turnover")):0;
            // Сначала проверена вся структура: не публикуем половину файла до первой ошибки.
            balances.clear(); balances.putAll(nextBalances); items.clear(); items.putAll(nextItems); limits.clear(); limits.putAll(nextLimits);
            turnover=nextTurnover; dirty=false;
            logger.info("[DiamondShop] Загружено: "+balances.size()+" старых входов миграции, "+items.size()+" товаров.");
        } catch(Exception error) { quarantine(error); }
    }
    private static JsonObject table(JsonObject data,String name,boolean required) {
        if(!data.has(name)&&!required) return new JsonObject();
        var value=data.get(name); if(value==null||!value.isJsonObject()) throw new IllegalArgumentException("Неверная таблица "+name);
        return value.getAsJsonObject();
    }
    private static UUID uuid(String value) {
        var id=UUID.fromString(value); if(!id.toString().equals(value)) throw new IllegalArgumentException("Не canonical UUID"); return id;
    }
    private static java.math.BigDecimal legacyDecimal(JsonElement value) {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Не старая сумма");
        var amount=value.getAsBigDecimal();
        if(amount.signum()<0||Math.abs((long)amount.scale())>10000||amount.precision()>10000) throw new IllegalArgumentException("Неверная старая сумма");
        return amount;
    }
    private static double decimal(JsonElement value) {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Не число");
        double number=value.getAsDouble();
        if(!Double.isFinite(number)||value.getAsBigDecimal().signum()<0||(number==0&&value.getAsBigDecimal().signum()!=0)) throw new IllegalArgumentException("Сумма/статистика вне диапазона");
        return number;
    }
    private static long integer(JsonObject row,String name,boolean required) {
        if(!row.has(name)&&!required) return 0;
        var value=row.get(name); decimal(value); return value.getAsBigDecimal().longValueExact();
    }
    private void quarantine(Exception error) {
        legacyAvailable=false;
        logger.severe("[DiamondShop] Хранилище остановлено; исходный shop-data.json не перезаписывается: "+error.getMessage());
    }

    /* ------------------------------- балансы ------------------------------- */

    /** Только неизменяемый вход миграции. В новых сделках эти деньги не расходуются. */
    public synchronized Map<UUID, Double> legacyBalances() {
        var view=new HashMap<UUID,Double>(); balances.forEach((id,value)->view.put(id,value.doubleValue())); return Map.copyOf(view);
    }
    /** Округляем вверх точное исходное число, а не предварительно округлённый double. */
    public synchronized long legacyAmount(UUID id) {
        if(!legacyAvailable||id==null) return -1;
        var amount=balances.getOrDefault(id,java.math.BigDecimal.ZERO);
        if(amount.compareTo(java.math.BigDecimal.valueOf(WholeTrades.MAX))>0) return -1;
        if(amount.signum()>0&&amount.compareTo(java.math.BigDecimal.ONE)<0) return 1;
        return amount.setScale(0,java.math.RoundingMode.CEILING).longValueExact();
    }
    public synchronized boolean canRecordTurnover(UUID id, double amount, double limit, long now) {
        return legacyAvailable && id != null && Double.isFinite(amount) && amount > 0 && Double.isFinite(limit) && limit >= 0 && (limit == 0 || usedToday(id, now) + amount <= limit);
    }
    public synchronized double balanceOf(UUID uuid) {
        return balances.getOrDefault(uuid, java.math.BigDecimal.ZERO).doubleValue();
    }

    /* ------------------------------- запасы ------------------------------- */

    /** Состояние товара с ленивой инициализацией под эталонный запас. */
    private synchronized ItemState itemState(ShopConfig.ShopItem item, long nowMillis) {
        ItemState state = items.computeIfAbsent(item.material(), key -> {
            ItemState created = new ItemState();
            created.stock = item.refStock();
            created.recoveryAt = nowMillis;
            dirty=true;
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
        copy.history=state.history.clone(); copy.historyAt=state.historyAt;
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
        if(!canRecordTurnover(uuid,amount,limit,nowMillis)) return false;
        if (limit == 0) return true;
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
        Map<String, java.math.BigDecimal> balancesCopy = new LinkedHashMap<>();
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
        return GSON.toJson(data);
    }

    /** Пишет snapshot в файл из асинхронного контекста, если были изменения. */
    public void saveAsync() {
        saveNow();
    }

    /** Snapshot+force+replace+ACK под тем же монитором, что и мутации рынка. */
    public synchronized void saveNow() {
        if(!legacyAvailable||(!dirty&&Files.isRegularFile(file))) return;
        try {
            String json=snapshotJson();
            if(json.getBytes(StandardCharsets.UTF_8).length>64L*1024*1024) throw new IOException("shop capacity");
            Files.createDirectories(file.getParent());
            var temp=file.resolveSibling("shop-data.json.tmp");
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            checkpoint.afterReplace();
            try(var directory=java.nio.channels.FileChannel.open(file.getParent(),java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
            dirty=false;
        } catch(IOException|RuntimeException error) { quarantine(error); }
    }
}
