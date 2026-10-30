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
    private UUID market=UUID.randomUUID();
    private long sequence;
    private boolean registered;
    synchronized boolean registered() { return registered; }
    synchronized boolean markRegistered() {
        if(!legacyAvailable) return false; if(registered) return true;
        var json=com.google.gson.JsonParser.parseString(snapshotJson()).getAsJsonObject(); json.addProperty("registered",true);
        if(!writeJson(GSON.toJson(json))) return false; registered=true; dirty=false; return true;
    }
    private ShopJournal.Pending pending;
    synchronized UUID market() { return market; }
    synchronized long sequence() { return sequence; }
    synchronized ShopJournal.Pending pending() { return pending; }
    synchronized boolean ensureMarket() { saveNow(); return legacyAvailable&&Files.isRegularFile(file)&&!dirty; }
    synchronized void stop(String reason) { quarantine(new IllegalStateException(reason)); }
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
            for(String key:data.keySet()) if(!java.util.Set.of("balances","items","limits","turnover","schema","market","sequence","pending","registered").contains(key)) throw new IllegalArgumentException("Неизвестное поле магазина: "+key);
            var nextBalances=new HashMap<UUID,java.math.BigDecimal>();
            var nextItems=new HashMap<String,ItemState>();
            var nextLimits=new HashMap<UUID,LimitRow>();
            for(var entry:table(data,"balances",true).entrySet()) nextBalances.put(uuid(entry.getKey()),legacyDecimal(entry.getValue()));
            for(var entry:table(data,"items",false).entrySet()) {
                if(!entry.getKey().matches("[A-Z][A-Z0-9_:]{0,127}")||!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Неверный товар");
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
            UUID nextMarket=market; boolean nextRegistered=false; long nextSequence=0; ShopJournal.Pending nextPending=null;
            if(data.has("schema")) {
                if(integer(data,"schema",true)!=1) throw new IllegalArgumentException("shop schema");
                if(!data.has("market")||!data.get("market").isJsonPrimitive()||!data.get("market").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("market UUID");
                var registration=data.get("registered"); if(registration==null||!registration.isJsonPrimitive()||!registration.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("shop registration");
                nextRegistered=registration.getAsBoolean();
                nextMarket=uuid(data.get("market").getAsString()); nextSequence=integer(data,"sequence",true);
                if(!nextRegistered&&nextSequence>0) throw new IllegalArgumentException("sequence without registration ACK");
                if(nextSequence>=Long.MAX_VALUE-1) throw new IllegalArgumentException("shop sequence exhausted");
                if(data.has("pending")) {
                    var row=data.getAsJsonObject("pending"); ru.cherdakmd.chrdkregister.api.ShopExchange.requireJson(row.getAsJsonObject("trade"));
                    for(String field:java.util.List.of("stock","sold","bought","time","day","historyPoints","historyInterval")) integer(row,field,true);
                    integer(row,"historyPoints",true); row.get("historyPoints").getAsBigDecimal().intValueExact();
                    for(String field:java.util.List.of("turnover","price")) decimal(row.get(field));
                    var used=row.get("used"); if(used==null||!used.isJsonPrimitive()||!used.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("pending used");
                    nextPending=GSON.fromJson(row,ShopJournal.Pending.class);
                    var limit=nextLimits.get(nextPending.trade().owner()); double oldUsed=limit!=null&&limit.dayStart==nextPending.day()?limit.used:0;
                    if(!nextRegistered||!nextMarket.equals(nextPending.trade().market())||nextPending.trade().sequence()!=nextSequence+1||!nextPending.matches(nextItems.get(nextPending.trade().material()),oldUsed,nextTurnover)) throw new IllegalArgumentException("pending effect");
                }
            } else if(data.has("market")||data.has("sequence")||data.has("pending")||data.has("registered")) throw new IllegalArgumentException("downgraded shop metadata");
            // Сначала проверена вся структура: не публикуем половину файла до первой ошибки.
            balances.clear(); balances.putAll(nextBalances); items.clear(); items.putAll(nextItems); limits.clear(); limits.putAll(nextLimits);
            turnover=nextTurnover; market=nextMarket; registered=nextRegistered; sequence=nextSequence; pending=nextPending; dirty=!data.has("schema");
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

    /** Та же модель восстановления без инициализации товара, dirty и изменения часов. */
    public synchronized long peekStock(ShopConfig.ShopItem item,long nowMillis,double recoveryPerHour) {
        ItemState state=items.get(item.material());
        if(state==null) return Math.max(1,item.refStock());
        if(pending!=null) return state.stock;
        return Math.max(1,PriceEngine.recover(state.stock,item.refStock(),recoveryPerHour,nowMillis-state.recoveryAt));
    }

    /** Лениво применяет возврат запаса к эталону и возвращает актуальный stock. */
    public synchronized long stockOf(ShopConfig.ShopItem item, long nowMillis, double recoveryPerHour) {
        ItemState state = itemState(item, nowMillis);
        if(pending!=null) return state.stock;
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
        if(pending!=null) throw new IllegalStateException("Рынок зарезервирован незавершённой сделкой");
        ItemState state = itemState(item, nowMillis);
        stockOf(item, nowMillis, recoveryPerHour);
        state.stock = Math.max(1, state.stock + delta);
        state.recoveryAt = nowMillis;
        dirty = true;
    }

    public synchronized void setStock(ShopConfig.ShopItem item, long value, long nowMillis) {
        if(pending!=null) throw new IllegalStateException("Рынок зарезервирован незавершённой сделкой");
        ItemState state = itemState(item, nowMillis);
        state.stock = Math.max(1, value);
        state.recoveryAt = nowMillis;
        dirty = true;
    }

    /** Записывает сделку в статистику (units — штуки товара). */
    public synchronized void recordTrade(ShopConfig.ShopItem item, long units, boolean playerSold, double revenue) {
        if(pending!=null) throw new IllegalStateException("Рынок зарезервирован незавершённой сделкой");
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
        if(pending!=null||!legacyAvailable) return;
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
        if(pending!=null) return false;
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
        data.put("schema",1); data.put("registered",registered); data.put("market",market); data.put("sequence",sequence); if(pending!=null) data.put("pending",pending);
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

    private static boolean exactIncrease(double before,double after,long money) {
        return Double.isFinite(after)&&java.math.BigDecimal.valueOf(after).subtract(java.math.BigDecimal.valueOf(before)).compareTo(java.math.BigDecimal.valueOf(money))==0;
    }
    synchronized boolean prepare(ru.cherdakmd.chrdkregister.api.ShopExchange t,ShopConfig.ShopItem item,long now,double recovery,double limit,int points,long interval,double price) {
        if(!legacyAvailable||!registered||pending!=null||!market.equals(t.market())||t.sequence()!=sequence+1||!t.valid()||!item.material().equals(t.material())||!(t.money()==0 && t.carriesRemainder() ? legacyAvailable && Double.isFinite(limit) && limit>=0 && (limit==0 || usedToday(t.owner(),now)<=limit) : canRecordTurnover(t.owner(),t.money(),limit,now))) return false;
        try {
            stockOf(item,now,recovery); var state=items.get(item.material());
            var p=new ShopJournal.Pending(t,Math.max(1,Math.addExact(state.stock,t.buy()?-t.units():t.units())),
                Math.addExact(state.soldByPlayers,t.buy()?0:t.units()),Math.addExact(state.boughtByPlayers,t.buy()?t.units():0),now,
                now-Math.floorMod(now,86400000L),limit==0?-1:PriceEngine.round(usedToday(t.owner(),now)+t.money()),PriceEngine.round(turnover+t.money()),price,points,interval);
            if(!p.valid()||!exactIncrease(turnover,p.turnover(),t.money())||p.used()!=-1&&!exactIncrease(usedToday(t.owner(),now),p.used(),t.money())) return false;
            var json=com.google.gson.JsonParser.parseString(snapshotJson()).getAsJsonObject(); json.add("pending",GSON.toJsonTree(p));
            if(!writeJson(GSON.toJson(json))) return false;
            pending=p; dirty=false; return true;
        } catch(ArithmeticException e) { return false; }
    }
    /** Caller has just received authoritative MISSING from healthy Core; no item/money effect exists. */
    synchronized boolean cancelUnaccepted(ShopJournal.Pending p) {
        if(!legacyAvailable||!p.equals(pending)) return false;
        var json=com.google.gson.JsonParser.parseString(snapshotJson()).getAsJsonObject(); json.remove("pending");
        if(!writeJson(GSON.toJson(json))) return false;
        pending=null; dirty=false; return true;
    }
    synchronized boolean finish(ShopJournal.Pending p) {
        if(!legacyAvailable) return false;
        if(pending==null) return sequence==p.trade().sequence();
        if(!pending.equals(p)) return false;
        var state=items.get(p.trade().material());
        if(!p.matches(state,usedToday(p.trade().owner(),p.time()),turnover)) { stop("Не совпадает frozen effect рынка"); return false; }
        var json=com.google.gson.JsonParser.parseString(snapshotJson()).getAsJsonObject(); json.remove("pending"); json.addProperty("sequence",p.trade().sequence()); json.addProperty("turnover",p.turnover());
        var row=json.getAsJsonObject("items").getAsJsonObject(p.trade().material()); row.addProperty("stock",p.stock()); row.addProperty("recoveryAt",p.time()); row.addProperty("soldByPlayers",p.sold()); row.addProperty("boughtByPlayers",p.bought());
        double[] history=state.history.clone(); long historyAt=state.historyAt;
        if(history.length>0&&p.time()-historyAt<p.historyInterval()) history[history.length-1]=p.price();
        else { int keep=Math.min(p.historyPoints()-1,history.length); var next=new double[keep+1]; System.arraycopy(history,history.length-keep,next,0,keep); next[keep]=p.price(); history=next; historyAt=p.time(); }
        row.add("history",GSON.toJsonTree(history)); row.addProperty("historyAt",historyAt);
        if(p.used()!=-1) { var limit=new JsonObject(); limit.addProperty("dayStart",p.day()); limit.addProperty("used",p.used()); json.getAsJsonObject("limits").add(p.trade().owner().toString(),limit); }
        if(!writeJson(GSON.toJson(json))) return false;
        state.stock=p.stock(); state.recoveryAt=p.time(); state.soldByPlayers=p.sold(); state.boughtByPlayers=p.bought(); state.history=history; state.historyAt=historyAt;
        if(p.used()!=-1) { var limit=new LimitRow(); limit.dayStart=p.day(); limit.used=p.used(); limits.put(p.trade().owner(),limit); }
        turnover=p.turnover(); sequence=p.trade().sequence(); pending=null; dirty=false; return true;
    }
    /** Snapshot+force+replace+ACK под тем же монитором, что и мутации рынка. */
    public synchronized void saveNow() {
        if(!legacyAvailable||(!dirty&&Files.isRegularFile(file))) return;
        if(writeJson(snapshotJson())) dirty=false;
    }
    private boolean writeJson(String json) {
        try {
            if(json.getBytes(StandardCharsets.UTF_8).length>64L*1024*1024) throw new IOException("shop capacity");
            Files.createDirectories(file.getParent()); var temp=file.resolveSibling("shop-data.json.tmp");
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING); checkpoint.afterReplace();
            try(var directory=java.nio.channels.FileChannel.open(file.getParent(),java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
            return true;
        } catch(IOException|RuntimeException error) { quarantine(error); return false; }
    }
}
