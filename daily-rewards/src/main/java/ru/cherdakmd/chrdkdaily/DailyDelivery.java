package ru.cherdakmd.chrdkdaily;

import java.io.IOException;
import java.util.*;

/** Production executor: money obligations survive cap/offline; uncertain native effects NEVER auto-repeat. */
final class DailyDelivery {
    interface Bank {
        boolean available(); boolean registered(UUID lineage); boolean register(UUID lineage);
        boolean known(UUID owner); long credited(UUID lineage,UUID owner); boolean credit(UUID lineage,UUID owner,long total);
        default boolean receiptCompatible(long credited,long totalBase){return credited>=0&&credited<=totalBase;}
    }
    interface MoneyBank {
        boolean available(); boolean registered(UUID lineage); boolean register(UUID lineage);
        boolean known(UUID owner); long credited(UUID lineage, UUID owner); boolean credit(UUID lineage, UUID owner, long totalMinor);
    }
    /** Separate lineage namespace prevents mixing old market-scaled totals and frozen hundredths. */
    static boolean bindMoney(DailyStore store, MoneyBank bank) throws IOException {
        if (!store.healthy() || !bank.available()) return false;
        if (bank.registered(store.lineage())) { if (!store.initialize()) return false; store.markMoneyRegistered(); return true; }
        if (!store.moneyMinor().isEmpty()) return false;
        if (!store.initialize() || !bank.register(store.lineage())) return false;
        store.markMoneyRegistered(); return true;
    }
    /** Compare the existing high-water before increasing it; otherwise a stale/missing
     * local owner row could be masked by the next accepted reward. */
    static void verifyMoneyOwner(DailyStore store, MoneyBank bank, UUID owner) throws IOException {
        long credited=bank.credited(store.lineage(),owner);
        if (credited<0 || credited>store.moneyMinor().getOrDefault(owner,0L))
            throw new IOException("daily money source older than bank receipt");
    }
    static void moneyMinor(DailyStore store, MoneyBank bank) throws IOException {
        if (!bindMoney(store,bank)) return;
        for (var e : store.moneyMinor().entrySet()) {
            if (e.getValue()==0 || !bank.known(e.getKey())) continue;
            long credited=bank.credited(store.lineage(),e.getKey());
            if (credited<0 || credited>e.getValue()) throw new IOException("daily money source/receipt conflict");
            bank.credit(store.lineage(),e.getKey(),e.getValue());
        }
    }
    interface Native { void item(DailyConfig.ItemReward item) throws Exception; void boost(int hours) throws Exception; }
    static boolean bind(DailyStore store,Bank bank) throws IOException {
        if(!store.healthy()||!bank.available())return false;
        if(bank.registered(store.lineage())){if(!store.initialize())return false;store.markRegistered();return true;}
        // Нулевые/пустые totals не создают банковских обязательств. История дней и
        // немонетарные награды сами по себе не запрещают восстановить тот же lineage.
        if(!store.totals().values().stream().allMatch(total->total==0)||!store.pendingRewards().isEmpty())return false;
        if(!store.initialize()||!bank.register(store.lineage()))return false;store.markRegistered();return true;
    }
    static void money(DailyStore store,Bank bank) throws IOException {
        if(!bind(store,bank))return;
        for(var e:store.totals().entrySet()) {
            if(e.getValue()==0||!bank.known(e.getKey()))continue;
            long credited=bank.credited(store.lineage(),e.getKey());if(!bank.receiptCompatible(credited,e.getValue()))throw new IOException("daily source/receipt conflict");
            bank.credit(store.lineage(),e.getKey(),e.getValue());
        }
    }
    static boolean nativeEffects(DailyStore store,UUID owner,Native port) throws Exception {
        var r=store.pending(owner);if(r==null)return true;
        if(!store.healthy()||r.executing())return false;
        while(r.completed()<r.effects()) {
            int index=r.completed();store.begin(owner); // Canonical EXECUTING precedes EVERY native effect.
            if(index<r.items().size())port.item(r.items().get(index));else port.boost(r.hours());
            store.finishEffect(owner);r=store.pending(owner);
        }
        store.complete(owner);return true;
    }
    private DailyDelivery(){}
}
