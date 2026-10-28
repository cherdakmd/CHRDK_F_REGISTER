package ru.cherdakmd.chrdkorders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import com.google.gson.*;
import ru.cherdakmd.chrdkregister.api.InventoryIncome;
import ru.cherdakmd.chrdkregister.api.MilestoneMigration;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import ru.cherdakmd.chrdkregister.api.ChargeJournal;
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
 * snapshot+асинхронная запись {@link #saveNow()} выполняются под одним монитором.
 * Критические переходы intent/publish/регистрация синхронно force+atomic-replace,
 * поэтому старый async snapshot не может затереть денежное обязательство. Класс чистый
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

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Comparator<Row> TOP_ORDER =
            Comparator.comparingInt(Row::completed).reversed()
                    .thenComparing(Comparator.comparingInt(Row::reputation).reversed())
                    .thenComparing(Row::name);

    public static final String SOURCE = "orders-income";
    /** Весь заказ заморожен; после durable intent конфиг и TTL уже не отменяют принятую сдачу. */
    public record Pending(InventoryIncome.Intent inventory, BotOrder order, String name, long now) { }
    private UUID lineage = UUID.randomUUID();
    private boolean registered, healthy = true;
    private Pending pending;
    private final Map<UUID,Long> incomes = new LinkedHashMap<>();
    private final ChargeJournal.WriteCheckpoint checkpoint;
    private final Path file;
    private final Logger logger;
    private final Map<String, BotOrder> orders = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private long nextId = 1L;
    private boolean dirty;

    public OrdersStore(Path dataFolder, Logger logger) { this(dataFolder,logger,()->{}); }
    public OrdersStore(Path dataFolder, Logger logger, ChargeJournal.WriteCheckpoint checkpoint) {
        this.file=dataFolder.resolve("orders.json"); this.logger=logger; this.checkpoint=java.util.Objects.requireNonNull(checkpoint);
    }
    public Path file() { return file; }
    public synchronized boolean healthy() { return healthy; }
    public synchronized boolean idle() { return healthy && pending==null; }
    public synchronized Pending pending() { return pending; }
    public synchronized UUID lineage() { return lineage; }
    public synchronized boolean registered() { return registered; }
    public synchronized Map<UUID,Long> incomes() { return Map.copyOf(incomes); }
    private void writable() { if(!healthy) throw new IllegalStateException("orders.json quarantined"); }
    private void idleWrite() { writable(); if(pending!=null) throw new IllegalStateException("unfinished order delivery"); }
    public synchronized void markRegistered() throws IOException {
        writable(); if(registered) return; registered=true; persist();
    }
    /** Строгое чтение одной версии байтов. Ошибка запрещает даже onDisable flush. */
    public synchronized void load() {
        writable();
        try {
            if(!Files.exists(file)) { persist(); return; }
            if(!Files.isRegularFile(file)||Files.size(file)>16L*1024*1024) throw new IllegalArgumentException("orders file size/type");
            var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); FinanceNumbers.requireIntegral(root);
            long version=integer(root,"version",1,2);
            nextId=integer(root,"next-id",1,Long.MAX_VALUE-1);
            if(!root.has("orders")||!root.get("orders").isJsonArray()||!root.has("players")||!root.get("players").isJsonObject()) throw new IllegalArgumentException("orders schema");
            for(var value:root.getAsJsonArray("orders")) {
                var row=value.getAsJsonObject();
                String id=text(row,"id"); long numeric=Long.parseLong(id);
                if(numeric<1||numeric>=Long.MAX_VALUE-1||!id.equals(Long.toString(numeric))) throw new IllegalArgumentException("order id");
                for(String key:List.of("bot","bot-name","category","material")) text(row,key);
                if(text(row,"material").isBlank()) throw new IllegalArgumentException("order material");
                integer(row,"amount",1,576); integer(row,"reward",0,576);
                integer(row,row.has("pass-hours")?"pass-hours":"pass-days",0,OrderCategory.MAX_PASS_HOURS);
                integer(row,"min-reputation",0,100_000); integer(row,"reputation",0,100);
                integer(row,"created",0,Long.MAX_VALUE); integer(row,"expires",0,Long.MAX_VALUE);
                BotOrder order=parseOrder(GSON.fromJson(row,DATA_TYPE));
                if(order==null||orders.putIfAbsent(id,order)!=null) throw new IllegalArgumentException("duplicate/invalid order");
                nextId=Math.max(nextId,numeric+1);
            }
            for(var entry:root.getAsJsonObject("players").entrySet()) {
                UUID id=MilestoneMigration.uuid(entry.getKey()); var row=entry.getValue().getAsJsonObject();
                var player=new PlayerData(text(row,"name"));
                player.completed=(int)integer(row,"completed",0,Integer.MAX_VALUE); player.reputation=(int)integer(row,"reputation",0,Integer.MAX_VALUE);
                player.day=integer(row,"day",0,Long.MAX_VALUE); player.today=(int)integer(row,"today",0,player.completed);
                player.lastDelivery=integer(row,"last-delivery",0,Long.MAX_VALUE); players.put(id,player);
            }
            if(version==1&&root.has("finance")) throw new IllegalArgumentException("finance schema downgrade");
            if(version==2) {
                var finance=root.getAsJsonObject("finance");
                if(!SOURCE.equals(text(finance,"source"))) throw new IllegalArgumentException("order namespace");
                lineage=MilestoneMigration.uuid(text(finance,"lineage"));
                if(!finance.has("registered")||!finance.get("registered").isJsonPrimitive()||!finance.get("registered").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("registered type");
                registered=finance.get("registered").getAsBoolean();
                for(var entry:finance.getAsJsonObject("incomes").entrySet()) {
                    UUID id=MilestoneMigration.uuid(entry.getKey());
                    long total=integer(finance.getAsJsonObject("incomes"),entry.getKey(),0,(long)completed(id)*576);
                    if(!players.containsKey(id)) throw new IllegalArgumentException("unbacked income owner"); incomes.put(id,total);
                }
                if(finance.has("pending")&&!finance.get("pending").isJsonNull()) {
                    var raw=finance.getAsJsonObject("pending"); var inventory=raw.getAsJsonObject("inventory");
                    MilestoneMigration.uuid(text(inventory,"transaction")); MilestoneMigration.uuid(text(inventory,"owner"));
                    text(inventory,"beforeInventory"); text(inventory,"afterInventory"); text(raw,"name"); integer(raw,"now",0,Long.MAX_VALUE);
                    pending=GSON.fromJson(raw,Pending.class);
                    if(!GSON.toJsonTree(pending.order()).equals(raw.get("order"))||!pending.order().equals(orders.get(pending.order().id()))) throw new IllegalArgumentException("pending order mismatch");
                }
            } else {
                // v1: исторические completed — уже оплаченные physical заказы, не новые деньги.
                dirty=true; persist();
            }
        } catch(Exception error) {
            healthy=false; throw new IllegalStateException("Неоднозначный orders.json: запуск/перезапись запрещены",error);
        }
    }
    private static String text(JsonObject row,String key) {
        if(row==null||!row.has(key)||!row.get(key).isJsonPrimitive()||!row.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid "+key);
        return row.get(key).getAsString();
    }
    private static long integer(JsonObject row,String key,long min,long max) {
        if(row==null||!row.has(key)||!row.get(key).isJsonPrimitive()||!row.get(key).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("invalid "+key);
        long value=row.get(key).getAsBigDecimal().longValueExact(); if(value<min||value>max) throw new IllegalArgumentException("range "+key); return value;
    }
    /** Единственный вход финансовой сдачи; проверка лимитов повторяется перед durable intent. */
    public synchronized Pending prepare(UUID owner,String name,BotOrder order,long now,int maxPerDay,int cooldownSeconds,String before,String after) throws IOException {
        idleWrite();
        if(owner==null||name==null||now<0||!order.equals(orders.get(order.id()))||order.expired(now)
                ||completed(owner)==Integer.MAX_VALUE||reputation(owner)<order.minReputation()||maxPerDay>0&&today(owner,now)>=maxPerDay
                ||cooldownSeconds>0&&lastDelivery(owner)>0&&now-lastDelivery(owner)<cooldownSeconds*1000L) throw new IllegalStateException("delivery rules changed");
        // Проверяем переполнение до первой записи и до списания предметов.
        Math.addExact(incomes.getOrDefault(owner,0L),order.rewardDiamonds());
        pending=new Pending(new InventoryIncome.Intent(UUID.randomUUID(),owner,before,after),order,name,now);
        persist(); return pending;
    }
    /** Вызывать только после CONSUMED/witness. Все денежные и игровые отметки — один replace. */
    public synchronized void publish(Pending verified) throws IOException {
        writable(); if(pending==null||!pending.equals(verified)) throw new IllegalArgumentException("pending identity");
        var p=pending; pending=null; orders.remove(p.order().id());
        recordDelivery(p.inventory().owner(),p.name(),p.now(),p.order().reputation());
        incomes.merge(p.inventory().owner(),(long)p.order().rewardDiamonds(),Math::addExact); persist();
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
        idleWrite();
        if(nextId>=Long.MAX_VALUE-1) throw new IllegalStateException("order ids exhausted");
        long id = Math.max(1L, nextId);
        nextId = id + 1L;
        dirty = true;
        return Long.toString(id);
    }

    public synchronized void add(BotOrder order) {
        idleWrite();
        if (order == null || order.id().isEmpty()) {
            return;
        }
        long numeric=Long.parseLong(order.id());
        if(numeric<1||numeric>=Long.MAX_VALUE-1||!order.id().equals(Long.toString(numeric))||orders.containsKey(order.id())) throw new IllegalArgumentException("order id");
        nextId=Math.max(nextId,numeric+1); orders.put(order.id(), order);
        dirty = true;
    }

    /** Снимает заказ с доски; {@code true} — он был. */
    public synchronized boolean remove(String id) {
        idleWrite();
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
        idleWrite();
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
        idleWrite();
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
        idleWrite();
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
        idleWrite();
        PlayerData data = ensure(id, name);
        if (data == null) {
            return;
        }
        long day = dayOf(now);
        if (data.day != day) {
            data.day = day;
            data.today = 0;
        }
        data.completed=(int)Math.min(Integer.MAX_VALUE,(long)data.completed+1);
        data.today=(int)Math.min(Integer.MAX_VALUE,(long)data.today+1);
        data.reputation=(int)Math.min(Integer.MAX_VALUE,(long)data.reputation+Math.max(0,reputationGain));
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
        idleWrite();
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

    /** Снимок без ACK записи. Dirty снимается только после успешного durable replace. */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 2);
        Map<String,Object> finance=new LinkedHashMap<>();
        finance.put("source",SOURCE); finance.put("lineage",lineage); finance.put("registered",registered); finance.put("incomes",incomes);
        if(pending!=null) finance.put("pending",pending); data.put("finance",finance);
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
        return GSON.toJson(data);
    }

    /** Весь snapshot+replace под тем же монитором: старый async-save не затрёт финансовый commit. */
    public synchronized void saveNow() { if(healthy&&dirty) flush(); }
    public synchronized void flush() {
        if(!healthy) return;
        try { persist(); } catch(IOException error) { if(logger!=null) logger.log(java.util.logging.Level.SEVERE,"orders.json приостановлен, не удаляйте файл",error); }
    }
    private void persist() throws IOException {
        writable();
        try {
            byte[] bytes=snapshotJson().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>16*1024*1024) throw new IOException("orders capacity");
            Files.createDirectories(file.getParent()); var temp=file.resolveSibling("orders.json.tmp"); Files.write(temp,bytes);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            checkpoint.afterReplace();
            try(var directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)) { directory.force(true); }
            dirty=false;
        } catch(IOException error) { healthy=false; dirty=true; throw error; }
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
     * Часы буста из строки доски. Старые доски писали ключ {@code pass-days}:
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
