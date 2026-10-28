package ru.cherdakmd.chrdktreasures;

import java.util.*;

/** Stable keys: коллекция считает разные записи, звание — все находки. NOVICE не награждается. */
final class TreasureRewardRules {
    static Map<String,Long> quote(long found,int distinct,TreasuresConfig config) {
        if(found<0||distinct<0||config==null) throw new IllegalArgumentException("treasure progress");
        var out=new LinkedHashMap<String,Long>();
        for(var rank:TreasureRank.values()) if(rank!=TreasureRank.NOVICE&&found>=rank.minFound())
            out.put("rank:"+rank.id(),(long)config.rankReward(rank));
        config.milestones().forEach((threshold,reward)->{
            if(threshold<=distinct) out.put("collection:"+threshold,(long)reward.diamonds());
        });
        out.forEach((key,amount)->{validKey(key); if(amount<0||amount>576) throw new IllegalArgumentException("treasure reward amount");});
        return Collections.unmodifiableMap(out);
    }
    static void validKey(String key) {
        if(key==null) throw new IllegalArgumentException("reward key");
        if(key.startsWith("rank:")) {
            var rank=TreasureRank.byId(key.substring(5));
            if(rank==null||rank==TreasureRank.NOVICE||!key.equals("rank:"+rank.id())) throw new IllegalArgumentException("rank claim");
        } else if(key.startsWith("collection:")) {
            String raw=key.substring(11);
            int count=Integer.parseInt(raw); if(count<1||!raw.equals(Integer.toString(count))) throw new IllegalArgumentException("collection claim");
        } else throw new IllegalArgumentException("reward namespace");
    }
    private TreasureRewardRules() { }
}
