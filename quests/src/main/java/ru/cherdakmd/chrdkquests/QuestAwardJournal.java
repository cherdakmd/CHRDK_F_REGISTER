package ru.cherdakmd.chrdkquests;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import ru.cherdakmd.chrdkregister.api.MilestoneMigration;

/** Durable once ledger. READY means the inventory proof (if any) is already durable. */
final class QuestAwardJournal {
    static final String SOURCE="quest-money";
    record Award(long minor, boolean ready, InventoryTransfer.Intent inventory) { }
    private static final class Data {
        int schema=1; UUID lineage=UUID.randomUUID(); boolean imported, registered;
        Map<UUID,Map<String,Award>> awards=new LinkedHashMap<>();
    }
    @FunctionalInterface interface Checkpoint { void afterReplace() throws IOException; }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file; private final Checkpoint checkpoint;
    private Data data=new Data(); private boolean healthy=true;
    QuestAwardJournal(Path folder) throws IOException { this(folder,()->{}); }
    QuestAwardJournal(Path folder, Checkpoint checkpoint) throws IOException {
        this.file=folder.resolve("quest-money.json"); this.checkpoint=checkpoint;
        if(!Files.exists(file)) { write(data); return; }
        try {
            if(Files.size(file)>32L*1024*1024) throw new IllegalArgumentException("capacity");
            var root=QuestJournalJson.read(file); FinanceNumbers.requireIntegral(root);
            for(String key:List.of("schema","lineage","imported","registered","awards"))
                if(!root.has(key)||root.get(key).isJsonNull()) throw new IllegalArgumentException("missing "+key);
            if(!root.get("schema").isJsonPrimitive()||!root.get("schema").getAsJsonPrimitive().isNumber()
                    ||!root.get("registered").isJsonPrimitive()||!root.get("registered").getAsJsonPrimitive().isBoolean()
                    ||!root.get("imported").isJsonPrimitive()||!root.get("imported").getAsJsonPrimitive().isBoolean()
                    ||!root.get("awards").isJsonObject() || root.get("schema").getAsBigDecimal().intValueExact()!=1) throw new IllegalArgumentException("metadata type");
            MilestoneMigration.uuid(root.get("lineage").getAsString());
            for(var player:root.getAsJsonObject("awards").entrySet()) {
                MilestoneMigration.uuid(player.getKey());
                for(var entry:player.getValue().getAsJsonObject().entrySet()) {
                    var row=entry.getValue().getAsJsonObject();
                    if(!row.has("minor")||!row.get("minor").isJsonPrimitive()||!row.get("minor").getAsJsonPrimitive().isNumber()
                            ||!row.has("ready")||!row.get("ready").isJsonPrimitive()||!row.get("ready").getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException("award type");
                }
            }
            data=JSON.fromJson(root,Data.class); validate(data);
        } catch(RuntimeException error) { healthy=false; throw new IOException("Invalid quest money journal; do not reset",error); }
    }
    private static void key(String key) {
        if(key==null||!key.matches("[a-z0-9][a-z0-9_-]{0,119}")) throw new IllegalArgumentException("quest id");
    }
    private static void validate(Data d) {
        if(d==null||d.schema!=1||d.lineage==null||d.awards==null||!d.imported&&(d.registered||!d.awards.isEmpty())) throw new IllegalArgumentException("journal header");
        if(!d.registered && d.awards.values().stream().flatMap(rows->rows.values().stream()).anyMatch(a->a!=null&&(a.minor()!=0||!a.ready()))) throw new IllegalArgumentException("unregistered awards");
        for(var p:d.awards.entrySet()) {
            if(p.getKey()==null||p.getValue()==null||p.getValue().isEmpty()) throw new IllegalArgumentException("journal owner");
            int pending=0; long total=0;
            for(var row:p.getValue().entrySet()) {
                key(row.getKey()); var a=row.getValue();
                if(a==null||a.minor()<0||a.minor()>100_000_000_000L||a.ready()&&a.inventory()!=null||!a.ready()&&a.inventory()==null)
                    throw new IllegalArgumentException("journal award");
                if(a.inventory()!=null) {
                    var i=a.inventory(); if(!p.getKey().equals(i.owner())) throw new IllegalArgumentException("journal inventory owner");
                    new InventoryTransfer.Intent(i.transaction(),i.owner(),i.before(),i.after()); pending++;
                } else total=Math.addExact(total,a.minor());
            }
            if(pending>1) throw new IllegalArgumentException("multiple pending inventories");
        }
    }
    private Data copy(){return JSON.fromJson(JSON.toJson(data),Data.class);}
    private void write(Data next) throws IOException {
        if(!healthy)throw new IOException("quest journal quarantined"); validate(next);
        try {
            Files.createDirectories(file.getParent()); byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>32*1024*1024) throw new IOException("quest journal capacity");
            var tmp=file.resolveSibling("quest-money.json.tmp"); Files.write(tmp,bytes);
            try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); checkpoint.afterReplace();
            try(var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)){c.force(true);}
            data=next;
        } catch(IOException|RuntimeException e){healthy=false;throw e;}
    }
    synchronized boolean healthy(){return healthy;}
    synchronized UUID lineage(){return data.lineage;}
    synchronized boolean registered(){return data.registered;}
    synchronized boolean imported(){return data.imported;}
    /** Preserve legacy completed markers without creating retroactive money. */
    synchronized void importLegacy(Map<UUID,Set<String>> legacy) throws IOException {
        if(data.imported)return;
        var n=copy();n.imported=true;
        for(var owner:legacy.entrySet()) {
            if(owner.getKey()==null||owner.getValue()==null)throw new IllegalArgumentException("legacy owner");
            if(owner.getValue().isEmpty())continue;
            var rows=new LinkedHashMap<String,Award>();
            for(String quest:owner.getValue()){key(quest);rows.put(quest,new Award(0,true,null));}
            n.awards.put(owner.getKey(),rows);
        }
        write(n);
    }
    synchronized void markRegistered() throws IOException { if(!data.registered){var n=copy();n.registered=true;write(n);} }
    synchronized Award award(UUID owner,String quest){var rows=data.awards.get(owner);return rows==null?null:rows.get(quest);}
    synchronized Map<UUID,Map<String,Award>> snapshot(){
        var out=new LinkedHashMap<UUID,Map<String,Award>>();data.awards.forEach((owner,rows)->out.put(owner,Map.copyOf(rows)));return Map.copyOf(out);
    }
    synchronized Map<String,Award> awards(UUID owner){var rows=data.awards.get(owner);return rows==null?Map.of():Map.copyOf(rows);}
    synchronized boolean pending(UUID owner){return awards(owner).values().stream().anyMatch(a->!a.ready());}
    synchronized void prepare(UUID owner,String quest,long minor,InventoryTransfer.Intent intent) throws IOException {
        if(!data.registered||owner==null||award(owner,quest)!=null||pending(owner)) throw new IllegalStateException("quest already accepted or pending");
        key(quest); var next=copy(); next.awards.computeIfAbsent(owner,k->new LinkedHashMap<>()).put(quest,new Award(minor,intent==null,intent)); write(next);
    }
    synchronized void confirmInventory(UUID owner,String quest) throws IOException {
        var a=award(owner,quest);if(a==null||a.ready()||a.inventory()==null)throw new IllegalStateException("quest inventory phase");
        var n=copy();n.awards.get(owner).put(quest,new Award(a.minor(),true,null));write(n);
    }
    synchronized Map<UUID,Long> totals(){
        var result=new LinkedHashMap<UUID,Long>();
        data.awards.forEach((id,rows)->{long n=0;for(var a:rows.values())if(a.ready())n=Math.addExact(n,a.minor());result.put(id,n);});
        return Map.copyOf(result);
    }
}
