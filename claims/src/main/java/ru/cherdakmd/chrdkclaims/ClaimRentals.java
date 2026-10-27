package ru.cherdakmd.chrdkclaims;

/** Суточная аренда одного чанка: единая транзакция казны, оплаты сервера и доступа. */
final class ClaimRentals {
    static final int MAX_PRICE = 1_000_000;
    private ClaimRentals() { }

    static String offer(ClaimStore store, Claim claim, String owner, int price, long now) {
        synchronized (store) {
            if (!current(store, claim) || !claim.isOwner(owner)) return "rent-not-owner";
            if (price < 0 || price > MAX_PRICE) return "rent-usage";
            if (price > 0 && store.territory(claim).stream().anyMatch(c -> c.salePrice > 0)) return "rent-sale";
            claim.rentOffer = price;
            if (price == 0) claim.rentRenew = false; // Уже оплаченный срок не сокращаем.
            store.markDirty();
            return null;
        }
    }

    static String take(ClaimStore store, Claim claim, String tenant, int expectedPrice, long now, int serverRent) {
        synchronized (store) {
            if (!current(store, claim) || claim.rentOffer <= 0) return "rent-no-offer";
            if (expectedPrice != claim.rentOffer) return "rent-price-changed";
            if (claim.isOwner(tenant)) return "rent-self";
            if (claim.rentedAt(now)) return "rent-busy";
            if (store.territory(claim).stream().anyMatch(c -> c.salePrice > 0)) return "rent-sale";
            String refusal = payDay(store, claim, tenant, claim.rentOffer, now, serverRent);
            if (refusal != null) return refusal;
            claim.tenant = tenant;
            claim.rentPrice = claim.rentOffer;
            claim.rentRenew = true;
            store.markDirty();
            return null;
        }
    }

    static String stop(ClaimStore store, Claim claim, String tenant) {
        synchronized (store) {
            if (!current(store, claim) || !claim.tenant.equalsIgnoreCase(tenant)) return "rent-not-tenant";
            claim.rentRenew = false;
            store.markDirty();
            return null;
        }
    }

    /** 0 — ничего, 1 — продлена, -1 — закончилась. Простой сервера не тарифицируется задним числом. */
    static int settle(ClaimStore store, Claim claim, long now, int serverRent) {
        synchronized (store) {
            if (!current(store, claim) || claim.tenant.isBlank() || claim.rentedAt(now)) return 0;
            if (serverRent < 0 || !claim.rentRenew || claim.rentOffer <= 0
                    || payDay(store, claim, claim.tenant, claim.rentPrice, now, serverRent) != null) {
                claim.clearRental();
                store.markDirty();
                return -1;
            }
            store.markDirty();
            return 1;
        }
    }

    private static boolean current(ClaimStore store, Claim c) {
        return c != null && store.claimAt(c.world, c.x, c.z) == c;
    }

    private static String payDay(ClaimStore store, Claim c, String tenant, int price, long now, int serverRent) {
        if (tenant == null || tenant.isBlank() || price <= 0 || price > MAX_PRICE || c.isOwner(tenant)
                || now < 0 || now > Long.MAX_VALUE - ClaimMath.DAY_MILLIS) return "rent-no-offer";
        if (store.treasuryOf(tenant) < price) return "rent-funds";
        long end = now + ClaimMath.DAY_MILLIS;
        // Покрываем серверную ренту вперёд до конца аренды, включая прежний долг.
        // Владелец не может обнулить казну и лишить арендатора уже оплаченного привата.
        long days = c.paidUntil >= end ? 0 : 1 + (end - Math.max(0, c.paidUntil) - 1) / ClaimMath.DAY_MILLIS;
        long cover = serverRent <= 0 ? 0 : (days + Math.max(0, c.debtDays)) * serverRent;
        long ownerAfter = (long) store.treasuryOf(c.owner) + price - cover;
        if (cover > Integer.MAX_VALUE || ownerAfter < 0 || ownerAfter > Integer.MAX_VALUE) return "rent-owner-funds";
        if (!store.takeTreasury(tenant, price)) return "rent-funds";
        long net = price - cover;
        if (net >= 0) store.addTreasury(c.owner, (int) net);
        else store.takeTreasury(c.owner, (int) -net);
        if (serverRent > 0) {
            c.paidUntil = Math.max(0, c.paidUntil) + days * ClaimMath.DAY_MILLIS;
            c.debtDays = 0;
        }
        c.rentUntil = end;
        return null;
    }
}
