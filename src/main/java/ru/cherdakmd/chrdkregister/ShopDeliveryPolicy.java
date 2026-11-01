package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import java.util.Set;

/** Предварительная проверка: оплаченные дни не срезаются, набор не выдаётся частично. */
final class ShopDeliveryPolicy {
    private ShopDeliveryPolicy() { }

    static boolean fits(long now, long currentUntil, int paidDays, int maxDays) {
        if (paidDays <= 0 || maxDays <= 0) return false;
        long ceiling = now + (long) maxDays * ShopEntitlements.DAY_MILLIS;
        long base = Math.max(now, currentUntil);
        return base <= ceiling && (long) paidDays * ShopEntitlements.DAY_MILLIS <= ceiling - base;
    }

    /** Проверка всех листьев и бонусного корневого права до первого изменения аккаунта. */
    static boolean fitsBundle(ShopProduct product, ShopCatalog catalog, long now, int days,
                              java.util.function.ToLongFunction<ShopProduct> until) {
        if (!complete(product, catalog)) return false;
        if (product.retainsBundleEntitlement() && (days != product.includedDays()
                || !fits(now, until.applyAsLong(product), days, catalog.maxTotalDays()))) return false;
        for (String id : product.resolvedIncludes()) {
            ShopProduct part = catalog.find(id).orElse(null);
            if (part == null || !fits(now, until.applyAsLong(part), product.includedDays(part), catalog.maxTotalDays())) return false;
        }
        return true;
    }

    static int bonusDays(long actualUntil, long paidUntil) {
        return (int) Math.max(0, (actualUntil - paidUntil) / ShopEntitlements.DAY_MILLIS);
    }

    record RankPlan(ShopService.Status status, long until) { }

    static RankPlan rankPlan(RegistrationStore.PlayerAccount account, int level, int days, int bonus,
                             long now, int oldPrice, int newPrice, int maxDays) {
        int permanent = account != null && account.rankPermanent ? Math.max(1, account.rankLevel) : 0;
        int current = account == null ? 0 : account.effectiveRank(now);
        if (permanent >= level || current > level) return new RankPlan(ShopService.Status.RANK_HIGHER, 0);
        boolean temporary = account != null && account.passUntil > now && current > permanent;
        if (temporary && current == level) {
            if (!fits(now, account.passUntil, days, maxDays)) return new RankPlan(ShopService.Status.TIME_LIMIT, 0);
            return new RankPlan(ShopService.Status.GRANTED,
                    ShopEntitlements.extend(now, account.passUntil, days + bonus, maxDays));
        }
        int carried = temporary ? RankLadder.carryDays(
                ShopEntitlements.remainingDays(account.passUntil, now), oldPrice, newPrice) : 0;
        if (!fits(now, now, days + carried, maxDays)) return new RankPlan(ShopService.Status.TIME_LIMIT, 0);
        long total = Math.min((long) maxDays, (long) days + carried + Math.max(0, bonus));
        return new RankPlan(ShopService.Status.GRANTED, now + total * ShopEntitlements.DAY_MILLIS);
    }

    static boolean complete(ShopProduct product, ShopCatalog catalog) {
        return complete(product, catalog, new HashSet<>(), 0);
    }

    private static boolean complete(ShopProduct product, ShopCatalog catalog, Set<String> path, int depth) {
        if (product == null || depth > ShopCatalog.MAX_BUNDLE_DEPTH + 1 || !path.add(product.id())) return false;
        try {
            if ("clan-style-pack".equals(product.id()) && (!product.retainsBundleEntitlement() || depth > 0)) return false;
            // Ранг допустим только на корне явного гибрида, не внутри другого набора.
            if (product.hasRank() && (depth > 0 || product.includedDays() <= 0)) return false;
            if (product.includes().isEmpty()) return !product.hasRank();
            if (product.resolvedIncludes().isEmpty()) return false;
            for (String id : product.includes()) {
                if (!complete(catalog.find(id).orElse(null), catalog, path, depth + 1)) return false;
            }
            return true;
        } finally {
            path.remove(product.id());
        }
    }
}
