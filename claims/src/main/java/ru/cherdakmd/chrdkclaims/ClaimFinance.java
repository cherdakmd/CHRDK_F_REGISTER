package ru.cherdakmd.chrdkclaims;

import ru.cherdakmd.chrdkregister.api.ClaimExchange;
import java.util.*;
import java.util.function.*;

/** One canonical domain WAL; every domain decision is made on a detached store, never the live territory. */
final class ClaimFinance {
    record Pending(ClaimExchange exchange,String beforeHash,String after) {
        boolean valid() { return exchange!=null&&exchange.valid()&&beforeHash!=null&&beforeHash.matches("[0-9a-f]{64}")&&after!=null&&after.length()<=32*1024*1024&&exchange.effectHash().equals(ClaimExchange.hash(after)); }
    }
    interface Bank extends ClaimAccounts {
        boolean registered(UUID lineage);
        boolean register(UUID lineage);
        /** May durably convert shortage into the player's own bank; never consumes for the domain. */
        Map<UUID,Long> resolve(Map<String,Long> deltas,String payer);
        ClaimExchange.Status status(ClaimExchange t);
        ClaimExchange.Status commit(ClaimExchange t);
        default long spendable(String name) { return balance(name); }
    }
    private final ClaimStore store;
    private final Bank bank;
    private boolean planning;
    ClaimFinance(ClaimStore store,Bank bank) { this.store=store; this.bank=bank; }
    boolean busy() { return planning||store.financialPending()!=null||!store.moneyAvailable(); }
    boolean binding() {
        if(!store.moneyAvailable()) return false;
        if(store.financialRegistered()) return bank.registered(store.financialLineage());
        return store.ensureFinance()&&bank.register(store.financialLineage())&&store.markFinanceRegistered();
    }
    boolean recover() {
        synchronized(store) {
            if(!binding()) return false;
            var p=store.financialPending(); if(p==null) return true;
            var status=bank.status(p.exchange());
            if(status==ClaimExchange.Status.MISSING) status=bank.commit(p.exchange());
            if(status==ClaimExchange.Status.COMMITTED) return store.finishFinance(p);
            if(status==ClaimExchange.Status.CONFLICT) store.stopFinance("Конфликт sequence/receipt Claims; требуется согласованная копия");
            else if(status==ClaimExchange.Status.MISSING&&bank.status(p.exchange())==ClaimExchange.Status.MISSING) store.cancelFinance(p);
            return false;
        }
    }
    <T> T execute(String kind,Function<ClaimStore,T> action,Predicate<T> success,T refused) {
        synchronized(store) {
            if(planning||!recover()) return refused;
            planning=true;
            try {
                long revision=store.financeRevision();
                var plan=new Planning(bank); var shadow=store.financeCopy(plan);
                T result=action.apply(shadow); if(!success.test(result)) return result;
                if(!plan.valid) return refused;
                var deltas=bank.resolve(plan.deltas,plan.payer); if(deltas==null||store.financeRevision()!=revision) return refused;
                String after=shadow.domainJson();
                var t=new ClaimExchange(UUID.randomUUID(),store.financialLineage(),store.financialSequence()+1,kind,deltas,ClaimExchange.hash(after));
                if(!store.prepareFinance(new Pending(t,ClaimExchange.hash(store.domainJson()),after))) return refused;
                return recover()?result:refused;
            } finally { planning=false; }
        }
    }
    private static final class Planning implements ClaimAccounts {
        final Bank bank; final Map<String,Long> deltas=new LinkedHashMap<>(); String payer; boolean valid=true;
        Planning(Bank bank) { this.bank=bank; }
        public boolean ready(String name) { return bank.ready(name); }
        public long balance(String name) { return bank.balance(name)+deltas.getOrDefault(name.toLowerCase(Locale.ROOT),0L); }
        public boolean exchange(Map<String,Long> changes,String inventoryPayer) {
            var next=new LinkedHashMap<>(deltas);
            try {
                if(inventoryPayer!=null) { if(payer!=null&&!payer.equalsIgnoreCase(inventoryPayer)) return false; payer=inventoryPayer; }
                for(var e:changes.entrySet()) next.merge(e.getKey().toLowerCase(Locale.ROOT),e.getValue(),Math::addExact);
                for(var e:next.entrySet()) {
                    if(!bank.ready(e.getKey())) return false;
                    long value=bank.balance(e.getKey())+e.getValue();
                    if(value>1_000_000_000L||value<0&&(payer==null||!payer.equalsIgnoreCase(e.getKey())||bank.spendable(e.getKey())+e.getValue()<0)) return false;
                }
            } catch(ArithmeticException|NullPointerException e) {valid=false;return false;}
            deltas.clear(); deltas.putAll(next); return true;
        }
    }
}
