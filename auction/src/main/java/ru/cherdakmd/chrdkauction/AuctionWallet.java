package ru.cherdakmd.chrdkauction;

import ru.cherdakmd.chrdkregister.api.AuctionTender;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class AuctionWallet {
    record Plan(ItemStack[] after, long debit, long cash, long change) { }
    static Plan plan(ItemStack[] before, long price, long balance) {
        if (before == null || before.length != 36) return null;
        int[] counts = new int[36], values = new int[36];
        for (int i=0; i<36; i++) {
            var item = before[i];
            if (item == null || item.hasItemMeta() || item.getAmount() <= 0 || item.getAmount() > 64) continue;
            values[i] = switch (item.getType()) {
                case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
                case DIAMOND_BLOCK -> 9;
                default -> 0;
            };
            if (values[i] > 0) counts[i] = item.getAmount();
        }
        var tender = AuctionTender.plan(price, balance, counts, values);
        if (tender == null) return null;
        var after = ItemCodec.copy(before);
        for (int i=0; i<36; i++) if (counts[i] != tender.remaining()[i]) {
            if (tender.remaining()[i] == 0) after[i] = null; else after[i].setAmount(tender.remaining()[i]);
        }
        return new Plan(after, tender.debit(), tender.cash(), tender.change());
    }
    static Plan planMinor(ItemStack[] before,long price,long balanceMinor,int rateBps) {
        if(before==null||before.length!=36||price<0||balanceMinor<0||rateBps<5000||rateBps>20000) return null;
        long debit=Math.min(price,balanceMinor),remaining=price-debit,cash=0;
        var after=ItemCodec.copy(before);
        // Whole diamonds first, then blocks; no resource or equipment can be consumed.
        for(int pass=0;pass<2&&remaining>0;pass++) for(int i=0;i<36&&remaining>0;i++) {
            var item=after[i];if(item==null||item.hasItemMeta()||item.getAmount()<=0)continue;
            int units=switch(item.getType()) { case DIAMOND -> pass==0?1:0;case DIAMOND_BLOCK -> pass==1?9:0;default -> 0; };
            if(units==0)continue;
            while(item.getAmount()>0&&remaining>0) {
                long value=Math.multiplyExact(units,(long)rateBps);cash=Math.addExact(cash,value);remaining-=value;item.setAmount(item.getAmount()-1);
            }
            if(item.getAmount()==0)after[i]=null;
        }
        if(remaining>0)return null;
        return new Plan(after,debit,cash,-remaining);
    }
    static boolean valid(AuctionStore.Pending pending) {
        var payment = pending.payment(); if (payment == null || !payment.valid()) return false;
        var plan = payment.minor()?planMinor(ItemCodec.inventory(pending.before()),payment.price(),payment.debit(),payment.rateBps())
                :plan(ItemCodec.inventory(pending.before()), payment.price(), payment.debit());
        return plan != null && plan.debit == payment.debit() && plan.cash == payment.cash() && plan.change == payment.change()
                && ItemCodec.matches(plan.after, pending.after());
    }
    private AuctionWallet() { }
}
