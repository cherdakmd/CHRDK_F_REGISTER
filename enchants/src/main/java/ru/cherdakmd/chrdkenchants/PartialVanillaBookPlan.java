package ru.cherdakmd.chrdkenchants;

import java.util.*;
import java.util.function.*;

/**
 * Read-only selection of vanilla enchantments, not a Bukkit transaction.
 * The caller must provide current registry compatibility and preserve the entire
 * original book metadata. No caller may consume a book using this plan alone.
 */
final class PartialVanillaBookPlan {
    enum Reason { MISMATCH, CONFLICT, NO_IMPROVEMENT }
    record Plan<K>(Map<K,Integer> result,Map<K,Integer> transferred,
                   Map<K,Integer> remaining,Map<K,Reason> reasons) {
        Plan {result=Map.copyOf(result);transferred=Map.copyOf(transferred);remaining=Map.copyOf(remaining);reasons=Map.copyOf(reasons);}
    }
    private PartialVanillaBookPlan() { }

    /**
     * canonicalId must be a unique, stable registry key; conflicts are checked
     * symmetrically even when a plugin supplies asymmetric implementations.
     * Null signals damaged input or conflicting pre-existing entries.
     */
    static <K> Plan<K> build(Map<K,Integer> target,Map<K,Integer> book,
                             Function<K,String> canonicalId,Predicate<K> applicable,
                             BiPredicate<K,K> conflicts,ToIntFunction<K> maximum) {
        if(target==null||book==null||book.isEmpty()||canonicalId==null||applicable==null||conflicts==null||maximum==null
                ||!valid(target,canonicalId,maximum)||!valid(book,canonicalId,maximum)
                ||!validConflicts(target.keySet(),conflicts)||!validConflicts(book.keySet(),conflicts))return null;
        // Reject aliases across maps: a key string identifies exactly one enchantment.
        var known=new HashMap<String,K>();
        for(K key:target.keySet())known.put(canonicalId.apply(key),key);
        for(K key:book.keySet()) {
            var existing=known.putIfAbsent(canonicalId.apply(key),key);
            if(existing!=null&&!existing.equals(key))return null;
        }
        var ordered=new ArrayList<>(book.keySet());ordered.sort(Comparator.comparing(canonicalId));
        var result=new LinkedHashMap<>(target);var transferred=new LinkedHashMap<K,Integer>();
        var remaining=new LinkedHashMap<K,Integer>();var reasons=new LinkedHashMap<K,Reason>();
        for(K key:ordered) {
            int incoming=book.get(key),before=result.getOrDefault(key,0);Reason reason=null;
            if(!applicable.test(key))reason=Reason.MISMATCH;
            else if(result.keySet().stream().anyMatch(existing->!existing.equals(key)
                    && (conflicts.test(existing,key)||conflicts.test(key,existing))))reason=Reason.CONFLICT;
            else if(AnvilPrice.merge(before,incoming,maximum.applyAsInt(key))<=before)reason=Reason.NO_IMPROVEMENT;
            if(reason!=null){remaining.put(key,incoming);reasons.put(key,reason);}
            else {result.put(key,AnvilPrice.merge(before,incoming,maximum.applyAsInt(key)));transferred.put(key,incoming);}
        }
        return new Plan<>(result,transferred,remaining,reasons);
    }
    private static <K> boolean valid(Map<K,Integer> values,Function<K,String> id,ToIntFunction<K> max) {
        var keys=new HashSet<String>();
        for(var entry:values.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null||entry.getValue()<1)return false;
            String canonical=id.apply(entry.getKey());
            if(canonical==null||canonical.isBlank()||!keys.add(canonical)||max.applyAsInt(entry.getKey())<1)return false;
        }
        return true;
    }
    private static <K> boolean validConflicts(Set<K> values,BiPredicate<K,K> conflicts) {
        var keys=new ArrayList<>(values);
        for(int i=0;i<keys.size();i++)for(int j=i+1;j<keys.size();j++)
            if(conflicts.test(keys.get(i),keys.get(j))||conflicts.test(keys.get(j),keys.get(i)))return false;
        return true;
    }
}
