package ru.cherdakmd.diamondshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Одноразовый подарок: durable-обязательство + идемпотентная банковская квитанция. */
public final class ShopWelcomeGift {
    public enum Status { CLAIMED, ALREADY, UNAVAILABLE }
    interface Bank {
        boolean available(UUID market);
        boolean credit(UUID market,UUID player,String key,long amount);
    }
    public static final String FILE_NAME="welcome-gifts.json";
    public static final String RECEIPT_KEY="welcome-gift";
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Logger logger;
    private final boolean enabled;
    private final long amount;
    private final Map<UUID,Long> claims=new LinkedHashMap<>();
    private boolean healthy=true;

    public ShopWelcomeGift(Path folder,Logger logger,boolean enabled,long amount) {
        this.file=folder.resolve(FILE_NAME); this.logger=logger;
        this.enabled=enabled&&amount>0&&amount<=1_000_000_000L; this.amount=amount;
        load();
    }
    public synchronized boolean enabled() { return enabled&&healthy; }
    public synchronized long amount() { return amount; }
    public synchronized boolean claimed(UUID player) { return player!=null&&claims.containsKey(player); }

    /** Повтор после сбоя безопасен: Core creditMarketEntitlement доплачивает только разницу. */
    public synchronized Status claim(UUID player,UUID market,Bank bank) {
        if(!enabled()||player==null||market==null||bank==null||!bank.available(market)) return Status.UNAVAILABLE;
        Long previous=claims.get(player);
        long total=previous==null?amount:previous;
        if(previous==null) {
            claims.put(player,total);
            if(!save()) { claims.remove(player); healthy=false; return Status.UNAVAILABLE; }
        }
        // Сначала локальное обязательство, затем деньги. При сбое следующий клик повторит
        // тот же receipt и не сможет начислить подарок дважды, даже после restart.
        if(!bank.credit(market,player,RECEIPT_KEY,total)) return Status.UNAVAILABLE;
        return previous==null?Status.CLAIMED:Status.ALREADY;
    }

    private void load() {
        if(!Files.exists(file)) return;
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>4*1024*1024) throw new IOException("gift journal");
            JsonObject root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!root.keySet().equals(java.util.Set.of("schema","claims"))||root.get("schema").getAsInt()!=1
                    ||!root.get("claims").isJsonObject()) throw new IOException("gift schema");
            for(var row:root.getAsJsonObject("claims").entrySet()) {
                UUID player=UUID.fromString(row.getKey());
                if(!player.toString().equals(row.getKey())||!row.getValue().isJsonPrimitive()
                        ||!row.getValue().getAsJsonPrimitive().isNumber()) throw new IOException("gift player");
                long total=row.getValue().getAsBigDecimal().longValueExact();
                if(total<=0||total>1_000_000_000L||claims.put(player,total)!=null) throw new IOException("gift amount");
            }
        } catch(Exception error) {
            healthy=false;
            logger.severe("[Подарок] Журнал повреждён; новые выдачи закрыты. Сохраните "
                    +FILE_NAME+": "+error.getClass().getSimpleName());
        }
    }
    private boolean save() {
        JsonObject root=new JsonObject(); root.addProperty("schema",1);
        JsonObject rows=new JsonObject(); claims.forEach((player,total)->rows.addProperty(player.toString(),total));
        root.add("claims",rows);
        Path temporary=file.resolveSibling(FILE_NAME+".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary,GSON.toJson(root),StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            return true;
        } catch(IOException error) {
            logger.severe("[Подарок] Не удалось сохранить обязательство; выдача закрыта: "+error.getClass().getSimpleName());
            return false;
        }
    }
}
