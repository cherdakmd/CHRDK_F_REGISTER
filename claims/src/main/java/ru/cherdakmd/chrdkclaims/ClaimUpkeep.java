package ru.cherdakmd.chrdkclaims;

import java.util.Map;

/** Автоматическое списание: только банк; отказ инфраструктуры не означает неуплату. */
final class ClaimUpkeep {
    static ClaimMath.RentStep day(ClaimStore store, Claim claim, int rent, int grace) {
        synchronized (store) {
            if (!store.fundsReady(claim.owner)) return null;
            var step = ClaimMath.settleRentDay(store.treasuryOf(claim.owner), rent, claim.debtDays, grace);
            if (step.charged() > 0 && !store.transferFunds(Map.of(claim.owner, -(long) step.charged()), null)) return null;
            claim.paidUntil += ClaimMath.DAY_MILLIS;
            claim.debtDays = step.debtDays();
            store.markDirty();
            return step;
        }
    }
    private ClaimUpkeep() { }
}
