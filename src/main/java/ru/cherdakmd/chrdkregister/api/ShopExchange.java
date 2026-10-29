package ru.cherdakmd.chrdkregister.api;

import java.util.*;

/** Frozen whole-lot market exchange. Canonical market sequence is not a second wallet. */
public record ShopExchange(UUID transaction,UUID market,long sequence,UUID owner,boolean buy,String material,
        long units,long money,long debit,long credit,long physical,Map<String,Integer> rates,String before,String after) {
    public enum Status { UNAVAILABLE,MISSING,PREPARED,COMMITTED,CONFLICT }
    public ShopExchange { if(rates!=null) rates=Map.copyOf(rates); }
    public boolean valid() {
        if(transaction==null||market==null||owner==null||sequence<1||sequence>=Long.MAX_VALUE||material==null||!material.matches("[A-Z][A-Z0-9_:]{0,127}")
                ||units<1||units>2304||money<1||money>1_000_000_000L||debit<0||debit>money||credit<0||credit>1_000_000_000L||physical<0||physical>36L*64*Integer.MAX_VALUE
                ||rates==null||rates.size()>4096||before==null||after==null||before.equals(after)||before.length()>4*1024*1024||after.length()>4*1024*1024) return false;
        for(var r:rates.entrySet()) if(!r.getKey().matches("[A-Z][A-Z0-9_]{0,127}")||r.getValue()==null||r.getValue()<1) return false;
        return buy?debit+physical-credit==money:debit==0&&physical==0&&credit==money;
    }
    public boolean fits(long balance) { return valid()&&balance>=debit&&balance-debit<=1_000_000_000L-credit; }
    /** JSON adapters must not coerce quoted/fractional counters to longs. */
    public static void requireJson(com.google.gson.JsonObject row) {
        for(String key:List.of("sequence","units","money","debit","credit","physical")) number(row.get(key)).longValueExact();
        for(String key:List.of("transaction","market","owner","material","before","after")) {
            var v=row.get(key); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("shop text "+key);
        }
        for(String key:List.of("transaction","market","owner")) MilestoneMigration.uuid(row.get(key).getAsString());
        var buy=row.get("buy"); if(buy==null||!buy.isJsonPrimitive()||!buy.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("shop buy");
        if(!row.has("rates")||!row.get("rates").isJsonObject()) throw new IllegalArgumentException("shop rates");
        row.getAsJsonObject("rates").entrySet().forEach(e->number(e.getValue()).intValueExact());
    }
    private static java.math.BigDecimal number(com.google.gson.JsonElement v) {
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("shop number"); return v.getAsBigDecimal();
    }
}
