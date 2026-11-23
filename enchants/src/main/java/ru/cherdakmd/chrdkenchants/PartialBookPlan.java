package ru.cherdakmd.chrdkenchants;

import java.util.*;
import java.util.function.Predicate;

/**
 * Read-only planning for the custom layer of a future partial book transfer.
 * It does not mutate items, debit XP, create a remainder or authorize an anvil click.
 * The caller must validate and preserve the vanilla layer and all other metadata
 * separately, and must use a recoverable transaction before enabling a transfer.
 */
final class PartialBookPlan {
    enum Reason { DISABLED, MISMATCH, CONFLICT, LIMIT, LEGENDARY_LIMIT, NO_IMPROVEMENT }
    record Plan(Map<String,Integer> result, Map<String,Integer> transferred,
                Map<String,Integer> remaining, Map<String,Reason> reasons) {
        Plan {
            result=Map.copyOf(result);transferred=Map.copyOf(transferred);
            remaining=Map.copyOf(remaining);reasons=Map.copyOf(reasons);
        }
    }
    private PartialBookPlan() { }

    /**
     * Null means invalid data; never treat a damaged or conflicting book as empty.
     * Order is by stable ID, not unspecified map/PDC iteration order. When limits
     * force a choice this deliberately gives a reproducible, not optimal, subset.
     * A no-op stays on the book, including when another enchant is transferred.
     */
    static Plan build(String material,boolean bookTarget,Map<String,Integer> target,
                      Map<String,Integer> book,Predicate<String> enabled,int max,int legendaryMax) {
        if(target==null||book==null||book.isEmpty()||enabled==null||max<0||legendaryMax<0
                ||(!bookTarget && (material==null||material.equals("AIR")||material.equals("BOOK")))
                ||!valid(target)||!valid(book)||!EnchantConflicts.validSet(target)||!EnchantConflicts.validSet(book))return null;
        var result=new LinkedHashMap<>(target);var transferred=new LinkedHashMap<String,Integer>();
        var remaining=new LinkedHashMap<String,Integer>();var reasons=new LinkedHashMap<String,Reason>();
        for(String id:new TreeSet<>(book.keySet())) {
            var def=EnchantRegistry.byId(id);int incoming=book.get(id),before=result.getOrDefault(id,0);
            Reason reason=null;
            if(EnchantRegistry.retired(id)||!enabled.test(id))reason=Reason.DISABLED;
            else if(!bookTarget&&!def.supports(material))reason=Reason.MISMATCH;
            else if(EnchantConflicts.conflictsWith(result,id))reason=Reason.CONFLICT;
            else {
                var others=new LinkedHashMap<>(result);others.remove(id);
                if(EnchantRegistry.activeCount(others)>=max)reason=Reason.LIMIT;
                else if(def.rarity()==EnchantDef.Rarity.LEGENDARY && others.keySet().stream()
                        .filter(key->!EnchantRegistry.retired(key)).map(EnchantRegistry::byId).filter(Objects::nonNull)
                        .filter(d->d.rarity()==EnchantDef.Rarity.LEGENDARY).count()>=legendaryMax)reason=Reason.LEGENDARY_LIMIT;
                else if(AnvilPrice.merge(before,incoming,def.maxLevel())<=before)reason=Reason.NO_IMPROVEMENT;
            }
            if(reason!=null){remaining.put(id,incoming);reasons.put(id,reason);}
            else {result.put(id,AnvilPrice.merge(before,incoming,def.maxLevel()));transferred.put(id,incoming);}
        }
        return new Plan(result,transferred,remaining,reasons);
    }
    private static boolean valid(Map<String,Integer> values){
        for(var row:values.entrySet()){
            var def=EnchantRegistry.byId(row.getKey());
            if(def==null||!def.id().equals(row.getKey())||row.getValue()==null||row.getValue()<1||row.getValue()>def.maxLevel())return false;
        }
        return true;
    }
}
