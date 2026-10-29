package ru.cherdakmd.chrdkdaily;

import java.io.IOException;
import java.util.*;

/** Production executor: money obligations survive cap/offline; uncertain native effects NEVER auto-repeat. */
final class DailyDelivery {
    interface Bank {
        boolean available(); boolean registered(UUID lineage); boolean register(UUID lineage);
        boolean known(UUID owner); long credited(UUID lineage,UUID owner); boolean credit(UUID lineage,UUID owner,long total);
    }
    interface Native { void item(DailyConfig.ItemReward item) throws Exception; void boost(int hours) throws Exception; }
    static boolean bind(DailyStore store,Bank bank) throws IOException {
        if(!store.healthy()||!bank.available())return false;
        if(store.registered())return bank.registered(store.lineage());
        if(!store.initialize()||!bank.register(store.lineage()))return false;store.markRegistered();return true;
    }
    static void money(DailyStore store,Bank bank) throws IOException {
        if(!bind(store,bank))return;
        for(var e:store.totals().entrySet()) {
            if(e.getValue()==0||!bank.known(e.getKey()))continue;
            long credited=bank.credited(store.lineage(),e.getKey());if(credited<0||credited>e.getValue())throw new IOException("daily source/receipt conflict");
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
