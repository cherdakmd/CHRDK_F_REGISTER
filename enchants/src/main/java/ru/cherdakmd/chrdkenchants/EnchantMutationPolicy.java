package ru.cherdakmd.chrdkenchants;

import java.util.Map;

/** Safety boundary for low-level additions, independent of the source of an enchant. */
final class EnchantMutationPolicy {
    private EnchantMutationPolicy() { }

    /** Existing data must have been strictly decoded; null means corrupt, not empty. */
    static boolean allowed(Map<String, Integer> existing, EnchantDef def) {
        if (existing == null || def == null || EnchantRegistry.retired(def.id())
                || !def.equals(EnchantRegistry.byId(def.id())) || !EnchantConflicts.validSet(existing)) return false;
        return existing.containsKey(def.id()) || !EnchantConflicts.conflictsWith(existing, def.id());
    }
}
