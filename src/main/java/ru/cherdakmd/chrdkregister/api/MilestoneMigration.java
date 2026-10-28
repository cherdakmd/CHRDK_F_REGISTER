package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Строгая граница перед старым lenient progress-loader: выданные деньги нельзя потерять при очистке JSON/YAML. */
public final class MilestoneMigration {
    public enum Kind { SKILLS, ACHIEVEMENTS, ARTIFACTS }
    public static UUID uuid(String raw) {
        UUID id=UUID.fromString(raw); if(!id.toString().equals(raw)) throw new IllegalArgumentException("Noncanonical award owner"); return id;
    }
    private static String key(Kind kind,JsonElement element) {
        if(element==null||element.isJsonNull()||!element.isJsonPrimitive()) throw new IllegalArgumentException("legacy award");
        if(kind==Kind.ACHIEVEMENTS) {
            if(!element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("achievement id");
            String value=element.getAsString(); MilestoneStore.validKey(value); return value;
        }
        if(!element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("milestone number");
        long value=element.getAsBigDecimal().longValueExact();
        if(value<1||value>(kind==Kind.ARTIFACTS?100:Integer.MAX_VALUE)) throw new IllegalArgumentException("milestone range");
        return (kind==Kind.SKILLS?"level:":"percent:")+value;
    }
    public static Map<UUID,Set<String>> json(Path file,Kind kind) throws IOException {
        if(!Files.exists(file)) return Map.of();
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024) throw new IllegalArgumentException("legacy capacity/type");
            var data=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); FinanceNumbers.requireIntegral(data);
            if(!data.has("version")||!data.get("version").isJsonPrimitive()||!data.get("version").getAsJsonPrimitive().isNumber()
                    ||data.get("version").getAsBigDecimal().longValueExact()!=1||!data.has("players")||!data.get("players").isJsonObject()) throw new IllegalArgumentException("legacy schema");
            var out=new LinkedHashMap<UUID,Set<String>>();
            for(var entry:data.getAsJsonObject("players").entrySet()) {
                UUID owner=uuid(entry.getKey()); var row=entry.getValue().getAsJsonObject();
                validateProgress(row,kind);
                String field=kind==Kind.SKILLS?"milestones":"claimed";
                if(!row.has(field)||!row.get(field).isJsonArray()) throw new IllegalArgumentException("missing legacy claims");
                var claims=new LinkedHashSet<String>();
                for(var value:row.getAsJsonArray(field)) if(!claims.add(key(kind,value))) throw new IllegalArgumentException("duplicate legacy award");
                out.put(owner,Set.copyOf(claims));
            }
            return Map.copyOf(out);
        } catch(RuntimeException error) { throw new IOException("Неоднозначный legacy ledger наград: "+file+"; файл не перезаписывается",error); }
    }
    private static void strings(JsonObject row,String field) {
        if(!row.has(field)||!row.get(field).isJsonArray()) throw new IllegalArgumentException("invalid progress "+field);
        for(var value:row.getAsJsonArray(field)) {
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank()) throw new IllegalArgumentException("invalid id "+field);
        }
    }
    private static void counters(JsonObject row,String field) {
        if(!row.has(field)||!row.get(field).isJsonObject()) throw new IllegalArgumentException("invalid counters "+field);
        for(var entry:row.getAsJsonObject(field).entrySet()) {
            var value=entry.getValue();
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||value.getAsBigDecimal().longValueExact()<0) throw new IllegalArgumentException("invalid counter");
        }
    }
    private static void validateProgress(JsonObject row,Kind kind) {
        if(kind==Kind.SKILLS) {
            counters(row,"xp");
            if(row.has("name")&&(!row.get("name").isJsonPrimitive()||!row.get("name").getAsJsonPrimitive().isString())) throw new IllegalArgumentException("invalid player name");
        } else if(kind==Kind.ACHIEVEMENTS) {
            counters(row,"counters"); strings(row,"unlocked");
            if(row.has("badge")&&!row.get("badge").isJsonNull()&&(!row.get("badge").isJsonPrimitive()||!row.get("badge").getAsJsonPrimitive().isString())) throw new IllegalArgumentException("invalid badge");
        } else {
            strings(row,"discovered");
            for(String field:List.of("firstFound","lastFound")) {
                if(!row.has(field)||!row.get(field).isJsonPrimitive()||!row.get(field).getAsJsonPrimitive().isNumber()||row.get(field).getAsBigDecimal().longValueExact()<0) throw new IllegalArgumentException("invalid discovery time");
            }
        }
    }
    public static Map<UUID,Set<String>> tierForge(Path file) throws IOException {
        if(!Files.exists(file)) return Map.of();
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024) throw new IllegalArgumentException("legacy capacity/type");
            var yaml=new YamlConfiguration(); yaml.load(file.toFile());
            if(yaml.contains("players")) {
                var players=yaml.getConfigurationSection("players"); if(players==null) throw new IllegalArgumentException("invalid stats players");
                for(String player:players.getKeys(false)) {
                    uuid(player); var row=players.getConfigurationSection(player); if(row==null) throw new IllegalArgumentException("invalid stats profile");
                    for(Object value:row.getValues(false).values()) {
                        if(!(value instanceof Number)||new java.math.BigDecimal(value.toString()).longValueExact()<0||new java.math.BigDecimal(value.toString()).longValueExact()>Integer.MAX_VALUE) throw new IllegalArgumentException("invalid stats counter");
                    }
                }
            }
            if(!yaml.contains("collection")) return Map.of();
            var root=yaml.getConfigurationSection("collection"); if(root==null) throw new IllegalArgumentException("collection section");
            var out=new LinkedHashMap<UUID,Set<String>>();
            for(String raw:root.getKeys(false)) {
                UUID owner=uuid(raw); var row=root.getConfigurationSection(raw);
                if(row==null||!row.isList("claimed")) throw new IllegalArgumentException("legacy collection claims");
                for(String field:List.of("tiers","properties","affixes")) {
                    if(!row.isList(field)) throw new IllegalArgumentException("invalid collection progress");
                    for(Object value:Objects.requireNonNull(row.getList(field))) if(!(value instanceof String id)||id.isBlank()) throw new IllegalArgumentException("invalid collection id");
                }
                if(row.contains("title")&&!(row.get("title") instanceof String)) throw new IllegalArgumentException("invalid title");
                var claims=new LinkedHashSet<String>();
                for(Object value:Objects.requireNonNull(row.getList("claimed"))) {
                    if(!(value instanceof String key)) throw new IllegalArgumentException("legacy claim key");
                    MilestoneStore.validKey(key); if(!claims.add(key)) throw new IllegalArgumentException("duplicate claim");
                }
                out.put(owner,Set.copyOf(claims));
            }
            return Map.copyOf(out);
        } catch(Exception error) { throw new IOException("Неоднозначный stats.yml: источник наград не сбрасывается",error); }
    }
    private MilestoneMigration() { }
}
