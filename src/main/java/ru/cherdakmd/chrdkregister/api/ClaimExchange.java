package ru.cherdakmd.chrdkregister.api;

import java.util.*;

/** Frozen Claims domain transition. Native tender is durably funded into /bal BEFORE this request. */
public record ClaimExchange(UUID transaction,UUID lineage,long sequence,String kind,Map<UUID,Long> deltas,String effectHash) {
    public enum Status { UNAVAILABLE,MISSING,COMMITTED,CONFLICT }
    public ClaimExchange { if(deltas!=null) deltas=Map.copyOf(deltas); }
    public boolean valid() {
        if(transaction==null||lineage==null||sequence<1||sequence>=Long.MAX_VALUE||kind==null||!kind.matches("[a-z][a-z-]{0,47}")||deltas==null||deltas.size()>8||effectHash==null||!effectHash.matches("[0-9a-f]{64}")) return false;
        for(var e:deltas.entrySet()) if(e.getKey()==null||e.getValue()==null||e.getValue() < -1_000_000_000L||e.getValue()>1_000_000_000L) return false;
        return true;
    }
    public static void requireJson(com.google.gson.JsonObject row) {
        for(String field:List.of("transaction","lineage","kind","effectHash")) {
            var v=row.get(field); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("claim text");
        }
        MilestoneMigration.uuid(row.get("transaction").getAsString()); MilestoneMigration.uuid(row.get("lineage").getAsString());
        number(row.get("sequence")).longValueExact();
        if(!row.has("deltas")||!row.get("deltas").isJsonObject()) throw new IllegalArgumentException("claim deltas");
        for(var e:row.getAsJsonObject("deltas").entrySet()) { MilestoneMigration.uuid(e.getKey()); number(e.getValue()).longValueExact(); }
    }
    private static java.math.BigDecimal number(com.google.gson.JsonElement v) {
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("claim number"); return v.getAsBigDecimal();
    }
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
