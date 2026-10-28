package ru.cherdakmd.chrdktreasures;

import com.google.gson.*;
import com.google.gson.GsonBuilder;
import ru.cherdakmd.chrdkregister.api.MilestoneMigration;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.Collections;

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


/** Canonical treasures.json v2: прогресс и once-награды одним durable replace.
 * Обычный autosave сериализован с финансовым commit; reset не удаляет денежные claims.
 * Предметный loot/inventory не входит в эту файловую транзакцию. */
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
                total = add(total,value);
            }
            return total;
        }

        public int foundItems() {
            return items.size();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String SOURCE="treasures-income";
    @FunctionalInterface public interface Checkpoint { void afterReplace() throws IOException; }
    private final Path file;
    private final Logger logger;
    private final Checkpoint checkpoint;
    private final Map<String,PlayerData> players=new LinkedHashMap<>();
    // Не часть сбрасываемого профиля: нулевые исходы тоже закрывают награду навсегда.
    private final Map<UUID,Map<String,Long>> awards=new LinkedHashMap<>();
    private UUID lineage=UUID.randomUUID();
    private boolean registered,healthy=true,dirty;

    public TreasureStore(Path folder,Logger logger) { this(folder,logger,()->{}); }
    public TreasureStore(Path folder,Logger logger,Checkpoint checkpoint) {
        file=folder.resolve("treasures.json"); this.logger=logger; this.checkpoint=java.util.Objects.requireNonNull(checkpoint);
    }
    public Path file() { return file; }
    public synchronized boolean healthy() { return healthy; }
    public synchronized UUID lineage() { return lineage; }
    public synchronized boolean registered() { return registered; }
    private void writable() { if(!healthy) throw new IllegalStateException("treasures.json quarantined"); }
    public synchronized void markRegistered() throws IOException { writable(); if(!registered) { registered=true; persist(); } }
    public synchronized long income(UUID owner) {
        long total=0; for(long value:awards.getOrDefault(owner,Map.of()).values()) total=Math.addExact(total,value); return total;
    }
    public synchronized Map<UUID,Long> incomes() {
        Map<UUID,Long> out=new LinkedHashMap<>(); awards.keySet().forEach(id->out.put(id,income(id))); return Collections.unmodifiableMap(out);
    }
    public synchronized Map<String,Long> claims(UUID owner) { return Map.copyOf(awards.getOrDefault(owner,Map.of())); }
    public synchronized long frozen(UUID owner,String key) { return awards.getOrDefault(owner,Map.of()).getOrDefault(key,0L); }
    /** Проверяем весь план до первой мутации; неизменные суммы не берутся из нового конфига повторно. */
    private void validatePlan(UUID owner,Map<String,Long> plan) {
        MilestoneMigration.uuid(owner.toString()); long total=income(owner); var existing=awards.getOrDefault(owner,Map.of());
        for(var entry:plan.entrySet()) {
            TreasureRewardRules.validKey(entry.getKey()); long value=entry.getValue();
            if(value<0||value>576) throw new IllegalArgumentException("reward amount");
            if(!existing.containsKey(entry.getKey())) total=Math.addExact(total,value);
        }
    }
    private boolean freeze(UUID owner,Map<String,Long> plan) {
        if(plan.isEmpty()) return false;
        boolean added=false; var rows=awards.computeIfAbsent(owner,id->new LinkedHashMap<>());
        for(var entry:plan.entrySet()) if(!rows.containsKey(entry.getKey())) { rows.put(entry.getKey(),entry.getValue()); added=true; }
        return added;
    }
    public synchronized int recordRewardedTreasure(UUID owner,TreasureTier tier,String item,long now,long window,TreasuresConfig config) throws IOException {
        writable(); if(owner==null||tier==null) throw new IllegalArgumentException("treasure owner/tier");
        String id=owner.toString(); var data=players.get(id); long found=data==null?0:data.found(); int distinct=data==null?0:data.foundItems();
        if(item!=null&&!item.isBlank()&&(data==null||!data.items.containsKey(item))) distinct=Math.addExact(distinct,1);
        var plan=TreasureRewardRules.quote(add(found,1),distinct,config); validatePlan(owner,plan);
        int streak=recordTreasure(id,tier,item,now,window);
        if(freeze(owner,plan)) persist(); // В том числе нулевой денежный исход; до native world/inventory.
        return streak;
    }
    /** Новые пороги коллекции/награды из конфига: только реально сохранённый прогресс. */
    public synchronized boolean reconcile(UUID owner,TreasuresConfig config) throws IOException {
        writable(); var data=players.get(owner.toString()); if(data==null) return false;
        var plan=TreasureRewardRules.quote(data.found(),data.foundItems(),config); validatePlan(owner,plan);
        if(!freeze(owner,plan)) return false; persist(); return true;
    }
    public synchronized void load() {
        try {
            if(!Files.exists(file)) { persist(); return; }
            if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024) throw new IOException("treasures capacity/type");
            var root=TreasureJson.read(file); FinanceNumbers.requireIntegral(root);
            long version=num(root,"version",false); if(version!=1&&version!=2) throw new IllegalArgumentException("treasures version");
            var loaded=new LinkedHashMap<String,PlayerData>();
            for(var entry:obj(root,"players").entrySet()) {
                UUID owner=MilestoneMigration.uuid(entry.getKey()); var row=entry.getValue().getAsJsonObject(); var data=new PlayerData();
                data.name=text(row,"name"); data.mined=num(row,"mined",false);
                for(var t:obj(row,"tiers").entrySet()) {
                    TreasureTier tier=TreasureTier.byId(t.getKey());
                    if(tier==null||!t.getKey().equals(tier.id())) throw new IllegalArgumentException("tier");
                    data.tiers.put(tier,number(t.getValue()));
                }
                for(var item:obj(row,"items").entrySet()) {
                    long count=number(item.getValue()); if(item.getKey().isBlank()||count<1||count>Integer.MAX_VALUE) throw new IllegalArgumentException("item counter");
                    data.items.put(item.getKey(),(int)count);
                }
                var settings=obj(row,"settings"); data.announce=bool(settings,"announce",version==1,true);
                data.particles=bool(settings,"particles",version==1,true); data.sounds=bool(settings,"sounds",version==1,true);
                var milestones=row.getAsJsonArray("milestones"); if(milestones==null) throw new IllegalArgumentException("missing milestones");
                for(var value:milestones) {
                    long count=number(value); if(count<1||count>Integer.MAX_VALUE||!data.milestones.add((int)count)) throw new IllegalArgumentException("milestone");
                }
                data.streak=integer(row,"streak",version==1); data.bestStreak=integer(row,"best_streak",version==1);
                data.bestStreak=Math.max(data.streak,data.bestStreak);
                data.lastFindAt=num(row,"last_find",version==1); data.shards=num(row,"shards",version==1); data.shardsSpent=num(row,"shards_spent",version==1);
                if(row.has("rank_tag")) {
                    var tag=TreasureRankTags.Choice.byId(text(row,"rank_tag")); if(tag==null) throw new IllegalArgumentException("rank tag"); data.rankTag=tag;
                } else if(version==2) throw new IllegalArgumentException("rank tag missing");
                loaded.put(owner.toString(),data);
            }
            var loadedAwards=new LinkedHashMap<UUID,Map<String,Long>>();
            if(version==1) {
                loaded.forEach((raw,data)->{
                    var rows=new LinkedHashMap<String,Long>();
                    for(var rank:TreasureRank.values()) if(rank!=TreasureRank.NOVICE&&data.found()>=rank.minFound()) rows.put("rank:"+rank.id(),0L);
                    data.milestones.forEach(count->rows.put("collection:"+count,0L));
                    loadedAwards.put(UUID.fromString(raw),rows);
                });
            } else {
                var finance=obj(root,"finance"); if(!SOURCE.equals(text(finance,"source"))) throw new IllegalArgumentException("source namespace");
                lineage=MilestoneMigration.uuid(text(finance,"lineage")); registered=bool(finance,"registered",false,false);
                for(var entry:obj(finance,"awards").entrySet()) {
                    UUID id=MilestoneMigration.uuid(entry.getKey()); var rows=new LinkedHashMap<String,Long>(); long total=0;
                    for(var award:entry.getValue().getAsJsonObject().entrySet()) {
                        TreasureRewardRules.validKey(award.getKey()); long amount=number(award.getValue());
                        if(amount>576) throw new IllegalArgumentException("award amount"); total=Math.addExact(total,amount); rows.put(award.getKey(),amount);
                    }
                    loadedAwards.put(id,rows);
                }
            }
            players.clear(); players.putAll(loaded); awards.clear(); awards.putAll(loadedAwards); dirty=false;
            if(version==1) persist();
            if(logger!=null) logger.info("[CHRDK_Treasures] Загружено кладоискателей: "+players.size()+".");
        } catch(Exception error) {
            healthy=false; throw new IllegalStateException("Неоднозначный treasures.json; оригинал сохранён, не удаляйте финансовый source",error);
        }
    }
    private static JsonObject obj(JsonObject row,String key) {
        if(!row.has(key)||!row.get(key).isJsonObject()) throw new IllegalArgumentException("object "+key); return row.getAsJsonObject(key);
    }
    private static String text(JsonObject row,String key) {
        var value=row.get(key); if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("text "+key); return value.getAsString();
    }
    private static long number(JsonElement value) {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("counter type");
        long n=value.getAsBigDecimal().longValueExact(); if(n<0) throw new IllegalArgumentException("negative counter"); return n;
    }
    private static long num(JsonObject row,String key,boolean optional) { return optional&&!row.has(key)?0:number(row.get(key)); }
    private static int integer(JsonObject row,String key,boolean optional) { long n=num(row,key,optional); if(n>Integer.MAX_VALUE) throw new IllegalArgumentException("int counter"); return (int)n; }
    private static boolean bool(JsonObject row,String key,boolean optional,boolean fallback) {
        var value=row.get(key); if(value==null&&optional) return fallback;
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("boolean "+key); return value.getAsBoolean();
    }
    private static long add(long a,long b) { return b>=Long.MAX_VALUE-a?Long.MAX_VALUE:a+b; }
    private PlayerData data(String playerKey) { writable(); MilestoneMigration.uuid(playerKey); return players.computeIfAbsent(playerKey,key->new PlayerData()); }

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
        data.mined = add(data.mined, amount);
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
        data.shards = add(data.shards, amount);
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
        writable();
        if (playerKey == null || amount <= 0) {
            return false;
        }
        PlayerData data = players.get(playerKey);
        if (data == null || data.shards < amount) {
            return false;
        }
        data.shards -= amount;
        data.shardsSpent = add(data.shardsSpent, amount);
        dirty = true;
        return true;
    }

    /**
     * Сбрасывает прогресс игрока: добычу, находки, коллекцию, вехи и серию.
     * Имя и настройки оформления остаются — после сброса игроку не нужно
     * заново выключать чужие поздравления.
     */
    public synchronized void reset(String playerKey) {
        writable();
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
        writable();
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
        data.tiers.merge(tier, 1L, TreasureStore::add);
        if (itemId != null && !itemId.isBlank()) {
            data.items.merge(itemId, 1, (a,b) -> a==Integer.MAX_VALUE?a:a+b);
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
        if (found < 1) return false;
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

    /** Снимок не снимает dirty: нужен durable ACK под тем же монитором. */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 2);
        Map<String,Object> finance=new LinkedHashMap<>(); finance.put("source",SOURCE); finance.put("lineage",lineage); finance.put("registered",registered); finance.put("awards",awards); data.put("finance",finance);
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
        return GSON.toJson(data);
    }

    /** Старый async snapshot не может затереть новый финансовый commit. */
    public synchronized void saveNow() { if(healthy&&dirty) flush(); }
    public synchronized void flush() {
        if(!healthy) return;
        try { persist(); } catch(IOException|RuntimeException error) {
            if(logger!=null) logger.log(java.util.logging.Level.SEVERE,"treasures.json приостановлен; не удаляйте финансовый source",error);
        }
    }
    private void persist() throws IOException {
        writable();
        try {
            byte[] bytes=snapshotJson().getBytes(StandardCharsets.UTF_8); if(bytes.length>32*1024*1024) throw new IOException("treasures capacity");
            Files.createDirectories(file.getParent()); var temp=file.resolveSibling("treasures.json.tmp"); Files.write(temp,bytes);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); checkpoint.afterReplace();
            try(var directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)) { directory.force(true); }
            dirty=false;
        } catch(IOException|RuntimeException error) { healthy=false; dirty=true; throw error; }
    }
}
