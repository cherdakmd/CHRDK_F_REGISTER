package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Infrastructure for new once-awards denominated in exact /bal minor units.
 * Legacy outcomes are explicit markers, never automatic money. This class does
 * not itself infer whether an old native award was delivered.
 */
public final class FixedMoneyMilestones {
    public enum State { LEGACY_DONE, LEGACY_UNCERTAIN, MONEY }
    public record Award(State state,long minor) { }
    private static final class Data {
        int schema=1; String source; UUID lineage=UUID.randomUUID(); boolean imported,registered;
        Map<UUID,Map<String,Award>> awards=new LinkedHashMap<>();
    }
    public interface Bank {
        boolean available(); boolean registered(String source,UUID lineage);
        boolean register(String source,UUID lineage); boolean known(UUID owner);
        long credited(String source,UUID lineage,UUID owner);
        boolean credit(String source,UUID lineage,UUID owner,long totalMinor);
    }
    @FunctionalInterface public interface Checkpoint { void afterReplace() throws IOException; }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file; private final String source; private final Checkpoint checkpoint;
    private Data data; private boolean healthy=true;
    public FixedMoneyMilestones(Path folder,String source) throws IOException { this(folder,source,()->{}); }
    public FixedMoneyMilestones(Path folder,String source,Checkpoint checkpoint) throws IOException {
        if(source==null||!source.matches("[a-z][a-z0-9-]{0,47}"))throw new IllegalArgumentException("source");
        this.file=folder.resolve("fixed-money.json");this.source=source;this.checkpoint=Objects.requireNonNull(checkpoint);
        if(!Files.exists(file)) { data=new Data();data.source=source;write(data);return; }
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024)throw new IllegalArgumentException("capacity");
            var root=StrictFinancialJson.read(file); FinanceNumbers.requireIntegral(root);
            for(String k:List.of("schema","source","lineage","imported","registered","awards"))
                if(!root.has(k)||root.get(k).isJsonNull())throw new IllegalArgumentException("missing "+k);
            if(!root.get("schema").isJsonPrimitive()||!root.get("schema").getAsJsonPrimitive().isNumber()
                    ||!root.get("source").isJsonPrimitive()||!root.get("source").getAsJsonPrimitive().isString()
                    ||!root.get("lineage").isJsonPrimitive()||!root.get("lineage").getAsJsonPrimitive().isString()
                    ||!root.get("imported").isJsonPrimitive()||!root.get("imported").getAsJsonPrimitive().isBoolean()
                    ||!root.get("registered").isJsonPrimitive()||!root.get("registered").getAsJsonPrimitive().isBoolean()
                    ||!root.get("awards").isJsonObject())throw new IllegalArgumentException("metadata type");
            if(root.get("schema").getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("schema");
            MilestoneMigration.uuid(root.get("lineage").getAsString());
            for(var owner:root.getAsJsonObject("awards").entrySet()) {
                MilestoneMigration.uuid(owner.getKey());
                for(var entry:owner.getValue().getAsJsonObject().entrySet()) {
                    var row=entry.getValue().getAsJsonObject();
                    if(!row.has("state")||!row.get("state").isJsonPrimitive()||!row.get("state").getAsJsonPrimitive().isString()
                            ||!row.has("minor")||!row.get("minor").isJsonPrimitive()||!row.get("minor").getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("award type");
                }
            }
            data=JSON.fromJson(root,Data.class);validate(data);
            if(!source.equals(data.source))throw new IllegalArgumentException("wrong source");
        } catch(RuntimeException e){healthy=false;throw new IOException("Invalid fixed money milestone journal; do not reset",e);}
    }
    private static void validate(Data d) {
        if(d==null||d.schema!=1||d.source==null||!d.source.matches("[a-z][a-z0-9-]{0,47}")||d.lineage==null||d.awards==null
                ||!d.imported&&(d.registered||!d.awards.isEmpty()))throw new IllegalArgumentException("header");
        for(var owner:d.awards.entrySet()) {
            if(owner.getKey()==null||owner.getValue()==null||owner.getValue().isEmpty())throw new IllegalArgumentException("owner");
            long total=0;
            for(var row:owner.getValue().entrySet()) {
                MilestoneStore.validKey(row.getKey());var award=row.getValue();
                if(award==null||award.state()==null||award.minor()<0||award.minor()>100_000_000_000L
                        ||award.state()!=State.MONEY&&award.minor()!=0||award.state()==State.MONEY&&!d.registered)
                    throw new IllegalArgumentException("award");
                if(award.state()==State.MONEY)total=Math.addExact(total,award.minor());
            }
        }
    }
    private Data copy(){return JSON.fromJson(JSON.toJson(data),Data.class);}
    private void write(Data next) throws IOException {
        if(!healthy)throw new IOException("journal quarantined");validate(next);
        try {
            Files.createDirectories(file.getParent());byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>32L*1024*1024)throw new IOException("journal capacity");
            var tmp=file.resolveSibling("fixed-money.json.tmp");Files.write(tmp,bytes);
            try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);checkpoint.afterReplace();
            try(var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)){c.force(true);}
            data=next;
        } catch(IOException|RuntimeException e){healthy=false;throw e;}
    }
    public synchronized boolean healthy(){return healthy;}
    public synchronized UUID lineage(){return data.lineage;}
    public synchronized Award award(UUID owner,String key){var rows=data.awards.get(owner);return rows==null?null:rows.get(key);}
    public synchronized Map<String,Award> awards(UUID owner){var rows=data.awards.get(owner);return rows==null?Map.of():Map.copyOf(rows);}
    public synchronized Map<UUID,Map<String,Award>> snapshot(){
        var out=new LinkedHashMap<UUID,Map<String,Award>>();data.awards.forEach((id,rows)->out.put(id,Map.copyOf(rows)));return Map.copyOf(out);
    }
    /** The caller must build a complete, validated migration snapshot before calling. */
    public synchronized void importLegacy(Map<UUID,Map<String,State>> legacy) throws IOException {
        if(data.imported)return;
        Objects.requireNonNull(legacy);var next=copy();next.imported=true;
        for(var owner:legacy.entrySet()) {
            if(owner.getKey()==null||owner.getValue()==null)throw new IllegalArgumentException("owner");
            var rows=new LinkedHashMap<String,Award>();
            for(var row:owner.getValue().entrySet()) {
                MilestoneStore.validKey(row.getKey());
                if(row.getValue()!=State.LEGACY_DONE&&row.getValue()!=State.LEGACY_UNCERTAIN)throw new IllegalArgumentException("legacy state");
                rows.put(row.getKey(),new Award(row.getValue(),0));
            }
            if(!rows.isEmpty())next.awards.put(owner.getKey(),rows);
        }
        write(next);
    }
    public synchronized boolean bind(Bank bank) throws IOException {
        if(!healthy||!data.imported||!bank.available())return false;
        if(!bank.registered(source,data.lineage)) {
            // Never recreate a previously bound lineage after a possible bank rollback.
            if(data.registered||!bank.register(source,data.lineage))return false;
        }
        if(!data.registered){var next=copy();next.registered=true;write(next);}
        return true;
    }
    public synchronized long total(UUID owner) {
        long total=0;var rows=data.awards.get(owner);
        if(rows!=null)for(var a:rows.values())if(a.state()==State.MONEY)total=Math.addExact(total,a.minor());
        return total;
    }
    private void verify(Bank bank,UUID owner) throws IOException {
        long credited=bank.credited(source,data.lineage,owner);
        if(credited<0||credited>total(owner))throw new IOException("fixed money journal behind bank receipt or source mismatch");
    }
    /** Accepted amount is immutable even if a retry quotes a different amount. */
    public synchronized Award accept(Bank bank,UUID owner,String key,long minor) throws IOException {
        if(owner==null||minor<0||minor>100_000_000_000L)throw new IllegalArgumentException("award");
        MilestoneStore.validKey(key);
        if(!bind(bank))throw new IOException("bank unavailable or lineage conflict");
        verify(bank,owner);
        var existing=award(owner,key);if(existing!=null)return existing;
        Math.addExact(total(owner),minor);
        var next=copy();var result=new Award(State.MONEY,minor);
        next.awards.computeIfAbsent(owner,unused->new LinkedHashMap<>()).put(key,result);write(next);return result;
    }
    /** Total is obtained from this durable journal, never supplied by a caller. */
    public synchronized boolean pay(Bank bank,UUID owner) throws IOException {
        if(owner==null||!bind(bank)||!bank.known(owner))return false;
        verify(bank,owner);long total=total(owner);return total==0||bank.credit(source,data.lineage,owner,total);
    }
    public static long quote(int diamonds,int hours,int uniqueDiamonds,DiamondExchangePolicy.Quote rate) {
        if(diamonds<0||hours<0||uniqueDiamonds<0)throw new IllegalArgumentException("negative award");
        return Math.addExact(rate.scaleMinor(Math.multiplyExact((long)diamonds,100)),
                Math.addExact(Math.multiplyExact((long)hours,15_000),Math.multiplyExact((long)uniqueDiamonds,100)));
    }
}
