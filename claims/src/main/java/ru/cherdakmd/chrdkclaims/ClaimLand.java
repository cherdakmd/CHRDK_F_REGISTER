package ru.cherdakmd.chrdkclaims;

import java.util.*;

/** Domain effects included in the same durable ACK as all accounts; UI notifications run only afterwards. */
final class ClaimLand {
    static boolean buy(ClaimStore store,String world,int x,int z,String owner,int price,long paidUntil) {
        if(store.finance!=null) return store.finance.execute("land-buy",s->buy(s,world,x,z,owner,price,paidUntil),Boolean.TRUE::equals,false);
        if(price<0||paidUntil<1||store.claimAt(world,x,z)!=null||!store.transferFunds(Map.of(owner,-(long)price),owner)) return false;
        store.add(new Claim(world,x,z,owner,paidUntil)); store.addDiamondsSpent(price); return true;
    }
    static boolean sellback(ClaimStore store,Claim claim,int reward) {
        if(claim==null) return false;
        if(store.finance!=null) return store.finance.execute("land-sellback",s->sellback(s,s.claimAt(claim.world,claim.x,claim.z),reward),Boolean.TRUE::equals,false);
        if(reward<0||claim.rentedAt(System.currentTimeMillis())||store.claimAt(claim.world,claim.x,claim.z)!=claim||!store.transferFunds(Map.of(claim.owner,(long)reward),null)) return false;
        store.remove(claim); return true;
    }
    static boolean trade(ClaimStore store,Claim claim,String buyer,int tax) {
        if(claim==null) return false;
        if(store.finance!=null) return store.finance.execute("land-sale",s->trade(s,s.claimAt(claim.world,claim.x,claim.z),buyer,tax),Boolean.TRUE::equals,false);
        int price=claim.salePrice; if(price<=0||tax<0||tax>price||claim.isOwner(buyer)) return false;
        var region=store.territory(claim);
        if(region.stream().anyMatch(c->c.salePrice!=price||c.debtDays>0||c.rentOffer>0||c.rentedAt(System.currentTimeMillis()))) return false;
        var changes=new LinkedHashMap<String,Long>(); changes.put(buyer,-(long)price); changes.merge(claim.owner,(long)price-tax,Long::sum);
        if(!store.transferFunds(changes,buyer)) return false;
        store.addTaxBurned(tax);
        for(var c:region) { c.owner=buyer; c.clearMembers(); c.salePrice=0; c.debtDays=0; c.rentOffer=0; c.clearRental(); }
        store.mergeAcquired(region); store.markDirty(); return true;
    }
    private ClaimLand() { }
}
