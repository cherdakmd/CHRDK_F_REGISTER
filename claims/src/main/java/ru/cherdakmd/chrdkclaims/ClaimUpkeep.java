package ru.cherdakmd.chrdkclaims;

import java.util.Map;

/** Автоматическое списание: только банк; отказ инфраструктуры не означает неуплату. */
final class ClaimUpkeep {
    static ClaimMath.RentStep day(ClaimStore store, Claim claim, int rent, int grace) {
        return day(store,claim,rent,grace,0);
    }
    static ClaimMath.RentStep day(ClaimStore store,Claim claim,int rent,int grace,long clampTo) {
        synchronized (store) {
            if(store.finance!=null) return store.finance.execute("land-upkeep",s->{
                var c=s.claimAt(claim.world,claim.x,claim.z); if(c==null) return null;
                var step=day(s,c,rent,grace,clampTo); if(step!=null&&step.released()) s.remove(c); return step;
            },java.util.Objects::nonNull,null);
            if (!store.fundsReady(claim.owner)) return null;
            var step = ClaimMath.settleRentDay(store.treasuryOf(claim.owner), rent, claim.debtDays, grace);
            if (step.charged() > 0 && !store.transferFunds(Map.of(claim.owner, -(long) step.charged()), null)) return null;
            claim.paidUntil = Math.max(Math.addExact(claim.paidUntil,ClaimMath.DAY_MILLIS),clampTo);
            claim.debtDays = step.debtDays();
            store.markDirty();
            return step;
        }
    }
    static Integer debt(ClaimStore store,Claim claim,int rent) {
        synchronized(store) {
            if(store.finance!=null) return store.finance.execute("land-debt",s->debt(s,s.claimAt(claim.world,claim.x,claim.z),rent),r->r!=null&&r>0,null);
            if(claim==null||rent<=0||!store.fundsReady(claim.owner)) return null;
            int days=Math.min(claim.debtDays,store.treasuryOf(claim.owner)/rent); if(days<=0) return 0;
            int cost=Math.multiplyExact(days,rent); if(!store.transferFunds(Map.of(claim.owner,-(long)cost),null)) return null;
            claim.debtDays-=days; store.markDirty(); return cost;
        }
    }
    private ClaimUpkeep() { }
}
