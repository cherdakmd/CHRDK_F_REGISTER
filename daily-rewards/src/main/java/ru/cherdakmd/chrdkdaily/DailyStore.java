package ru.cherdakmd.chrdkdaily;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/** Canonical daily progress + frozen reward + cumulative income. No progress reset recreates money. */
public final class DailyStore {
    public static final String SOURCE="daily-rewards";
    public static final class Progress {
        public long lastClaimDay=-1;
        public int streak,totalClaims;
    }
    public record Reward(long day,int streak,int cycleDay,boolean vip,List<DailyConfig.ItemReward> items,int hours,long money,int completed,boolean executing) {
        public Reward {items=List.copyOf(items);}
        int effects() {return items.size()+(hours>0?1:0);}
        Reward step(int n,boolean running) {return new Reward(day,streak,cycleDay,vip,items,hours,money,n,running);}
    }
    private static final class Data {
        int schema=1;
        UUID lineage=UUID.randomUUID();
        boolean registered;
        Map<UUID,Progress> players=new LinkedHashMap<>();
        Map<UUID,Long> totals=new LinkedHashMap<>();
        Map<UUID,Reward> pending=new LinkedHashMap<>();
    }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file; private final Logger logger;
    private final Checkpoint checkpoint;
    private Data data=new Data(); private boolean healthy=true,dirty;
    @FunctionalInterface interface Checkpoint {void afterReplace() throws IOException;}
    public DailyStore(Path folder,Logger logger) {this(folder,logger,()->{});}
    DailyStore(Path folder,Logger logger,Checkpoint checkpoint) {file=folder.resolve("daily-data.json");this.logger=logger;this.checkpoint=checkpoint;}
    public synchronized void load() {
        try {
            if(!Files.exists(file)) return;
            if(Files.size(file)>32L*1024*1024) throw new IOException("daily capacity");
            var row=DailyJson.read(file); ru.cherdakmd.chrdkregister.api.FinanceNumbers.requireIntegral(row);
            if(!row.has("players")||!row.get("players").isJsonObject()) throw new IOException("daily players");
            if(!row.has("schema")) {
                if(row.has("totals")||row.has("pending")||row.has("lineage")) throw new IOException("downgraded daily");
                data.players=JSON.fromJson(row.get("players"),new com.google.gson.reflect.TypeToken<LinkedHashMap<UUID,Progress>>(){}.getType()); dirty=true;
            } else {
                for(String key:List.of("schema","lineage","registered","players","totals","pending")) if(!row.has(key)||row.get(key).isJsonNull()) throw new IOException("missing daily "+key);
                if(!row.get("schema").isJsonPrimitive()||!row.get("schema").getAsJsonPrimitive().isNumber()||!row.get("registered").isJsonPrimitive()||!row.get("registered").getAsJsonPrimitive().isBoolean()) throw new IOException("daily metadata type");
                ru.cherdakmd.chrdkregister.api.MilestoneMigration.uuid(row.get("lineage").getAsString());
                for(var e:row.getAsJsonObject("totals").entrySet()){ru.cherdakmd.chrdkregister.api.MilestoneMigration.uuid(e.getKey());number(e.getValue());}
                for(var e:row.getAsJsonObject("pending").entrySet()) {
                    ru.cherdakmd.chrdkregister.api.MilestoneMigration.uuid(e.getKey());var r=e.getValue().getAsJsonObject(); for(String k:List.of("day","streak","cycleDay","hours","money","completed")) number(r.get(k));
                    for(String k:List.of("streak","cycleDay","hours","completed"))Math.toIntExact(number(r.get(k)));
                    for(var item:r.getAsJsonArray("items")){var i=item.getAsJsonObject();Math.toIntExact(number(i.get("amount")));if(!i.get("material").isJsonPrimitive()||!i.get("material").getAsJsonPrimitive().isString())throw new IOException("daily material");}
                    for(String k:List.of("vip","executing")) if(!r.has(k)||!r.get(k).isJsonPrimitive()||!r.get(k).getAsJsonPrimitive().isBoolean())throw new IOException("daily reward bool");
                }
                data=JSON.fromJson(row,Data.class);
            }
            for(var e:row.getAsJsonObject("players").entrySet()) {
                ru.cherdakmd.chrdkregister.api.MilestoneMigration.uuid(e.getKey()); var p=e.getValue().getAsJsonObject();
                for(String k:List.of("lastClaimDay","streak","totalClaims")) number(p.get(k));for(String k:List.of("streak","totalClaims"))Math.toIntExact(number(p.get(k)));
            }
            validate(data);
        } catch(Exception e) {healthy=false;logger.log(java.util.logging.Level.SEVERE,"DailyRewards: повреждён источник, выдачи остановлены; daily-data.json не перезаписывается",e);}
    }
    private static long number(JsonElement v) {if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("daily number");return v.getAsBigDecimal().longValueExact();}
    private static void validate(Data d) {
        if(d==null||d.schema!=1||d.lineage==null||d.players==null||d.totals==null||d.pending==null||!d.registered&&(!d.totals.isEmpty()||!d.pending.isEmpty()))throw new IllegalArgumentException("daily schema");
        for(var e:d.players.entrySet()) {var p=e.getValue();if(e.getKey()==null||p==null||p.lastClaimDay< -1||p.streak<0||p.totalClaims<0)throw new IllegalArgumentException("daily progress");}
        for(var e:d.totals.entrySet()) if(e.getKey()==null||e.getValue()==null||e.getValue()<0||!d.players.containsKey(e.getKey()))throw new IllegalArgumentException("daily total");
        for(var e:d.pending.entrySet()) {
            var r=e.getValue(); var p=d.players.get(e.getKey());
            if(r==null||p==null||r.day()<0||r.day()!=p.lastClaimDay||r.streak()!=p.streak||r.cycleDay()<1||r.hours()<0||r.hours()>24||r.money()<0||r.completed()<0||r.completed()>r.effects()||r.executing()&&r.completed()==r.effects()||d.totals.getOrDefault(e.getKey(),0L)<r.money())throw new IllegalArgumentException("daily frozen reward");
            for(var i:r.items())if(i.material()==null||!i.material().matches("[A-Z0-9_]+")||i.amount()<1||i.amount()>2304||moneyUnit(i.material())>0)throw new IllegalArgumentException("daily native reward");
        }
    }
    static int moneyUnit(String material) {return switch(material){case "DIAMOND"->1;case "DIAMOND_BLOCK"->9;default->0;};}
    public synchronized boolean healthy() {return healthy;}
    public synchronized UUID lineage() {return data.lineage;}
    public synchronized boolean registered() {return data.registered;}
    public synchronized Map<UUID,Long> totals() {return Map.copyOf(data.totals);}
    public synchronized Reward pending(UUID id) {return data.pending.get(id);}
    private Data copy() {return JSON.fromJson(JSON.toJson(data),Data.class);}
    private void write(Data next) throws IOException {
        if(!healthy)throw new IOException("daily quarantined"); validate(next);
        try {
            Files.createDirectories(file.getParent());var tmp=file.resolveSibling("daily-data.json.tmp");byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);if(bytes.length>32*1024*1024)throw new IOException("daily capacity");Files.write(tmp,bytes);
            try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);checkpoint.afterReplace();try(var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)){c.force(true);}
            data=next;dirty=false;
        } catch(IOException|RuntimeException e){healthy=false;throw e;}
    }
    public synchronized boolean initialize() {saveNow();return healthy&&Files.isRegularFile(file);}
    public synchronized void markRegistered() throws IOException {if(!data.registered){var n=copy();n.registered=true;write(n);}}
    public static long dayOf(long millis){return Math.floorDiv(millis,86_400_000L);}
    public synchronized Progress progressOf(UUID id) {var p=data.players.get(id);if(p==null)return new Progress();var c=new Progress();c.lastClaimDay=p.lastClaimDay;c.streak=p.streak;c.totalClaims=p.totalClaims;return c;}
    public synchronized boolean canClaim(UUID id,long now){return healthy&&!data.pending.containsKey(id)&&progressOf(id).lastClaimDay<dayOf(now);}
    private static int advance(Data n,UUID id,long day) {var p=n.players.computeIfAbsent(id,k->new Progress());if(p.lastClaimDay>=day)return -1;p.streak=p.lastClaimDay==day-1?Math.addExact(p.streak,1):1;p.totalClaims=Math.addExact(p.totalClaims,1);p.lastClaimDay=day;return p.streak;}
    /** Legacy pure arithmetic entry; production uses accept with frozen rewards. */
    public synchronized int claim(UUID id,long now){if(!canClaim(id,now))return -1;int s=advance(data,id,dayOf(now));dirty=s>0||dirty;return s;}
    public synchronized Reward accept(UUID id,long now,DailyConfig config,boolean vip) throws IOException {
        if(!data.registered||!canClaim(id,now))return null;
        var n=copy();int streak=advance(n,id,dayOf(now));var reward=config.rewardForStreakDay(streak);var items=new ArrayList<DailyConfig.ItemReward>();long money=0;
        for(var item:reward.itemsFor(vip)){int unit=moneyUnit(item.material());if(unit>0)money=Math.addExact(money,Math.multiplyExact((long)unit,item.amount()));else items.add(item);}
        var r=new Reward(dayOf(now),streak,reward.day(),vip,items,vip?reward.vipPassHours():0,money,0,false);
        n.pending.put(id,r);n.totals.put(id,Math.addExact(n.totals.getOrDefault(id,0L),money));write(n);return r;
    }
    public synchronized void begin(UUID id) throws IOException {var r=data.pending.get(id);if(r==null||r.executing()||r.completed()==r.effects())throw new IllegalStateException("daily effect phase");var n=copy();n.pending.put(id,r.step(r.completed(),true));write(n);}
    public synchronized void finishEffect(UUID id) throws IOException {var r=data.pending.get(id);if(r==null||!r.executing())throw new IllegalStateException("daily effect not executing");var n=copy();n.pending.put(id,r.step(r.completed()+1,false));write(n);}
    public synchronized void complete(UUID id) throws IOException {var r=data.pending.get(id);if(r==null)return;if(r.executing()||r.completed()!=r.effects())throw new IllegalStateException("daily native incomplete");var n=copy();n.pending.remove(id);write(n);}
    public synchronized void setStreak(UUID id,int streak,long now){if(!healthy||data.pending.containsKey(id))throw new IllegalStateException("daily unresolved");var n=copy();var p=n.players.computeIfAbsent(id,k->new Progress());p.streak=Math.max(0,streak);p.lastClaimDay=dayOf(now);try{write(n);}catch(IOException e){throw new IllegalStateException(e);}}
    public synchronized boolean isDirty(){return dirty;}
    public synchronized String snapshotJson(){return JSON.toJson(data);}
    public synchronized void saveNow(){if(!healthy||!dirty&&Files.isRegularFile(file))return;try{write(copy());}catch(Exception e){logger.severe("DailyRewards: запись остановлена: "+e.getMessage());}}
}
