package ru.cherdakmd.chrdkenchants;

import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Combined, read-only partition of both layers of a marked enchant book.
 * This is not an inventory transaction and does not authorize consuming a book.
 */
final class PartialMixedBookPlan {
    record Plan<K>(PartialBookPlan.Plan custom, PartialVanillaBookPlan.Plan<K> vanilla) {
        boolean transfersAnything() {
            return !custom.transferred().isEmpty() || vanilla != null && !vanilla.transferred().isEmpty();
        }
        boolean hasRemainder() {
            return !custom.remaining().isEmpty() || vanilla != null && !vanilla.remaining().isEmpty();
        }
        boolean partial() { return transfersAnything() && hasRemainder(); }
    }

    private PartialMixedBookPlan() { }

    /** A null result denotes invalid input, not an empty layer. */
    static <K> Plan<K> build(String material, boolean bookTarget,
            Map<String,Integer> customTarget, Map<String,Integer> customBook,
            Predicate<String> enabled, int max, int legendaryMax,
            Map<K,Integer> vanillaTarget, Map<K,Integer> vanillaBook,
            Function<K,String> canonicalId, Predicate<K> applicable,
            BiPredicate<K,K> conflicts, ToIntFunction<K> maximum) {
        if (vanillaTarget == null || vanillaBook == null) return null;
        var custom = PartialBookPlan.build(material, bookTarget, customTarget, customBook, enabled, max, legendaryMax);
        if (custom == null) return null;
        PartialVanillaBookPlan.Plan<K> vanilla = null;
        if (!vanillaBook.isEmpty()) {
            vanilla = PartialVanillaBookPlan.build(vanillaTarget, vanillaBook, canonicalId,
                    applicable, conflicts, maximum);
            if (vanilla == null) return null;
        }
        return new Plan<>(custom, vanilla);
    }
}
