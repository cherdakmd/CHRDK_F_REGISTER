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
        UUID registeredLineage();
        /** Durable committed sequence for the registered lineage; -1 means unknown or unavailable. */
        long sequence(UUID lineage);
        /** Resolve exact Core-bank deltas; this method must never inspect or consume inventory items. */
        Map<UUID,Long> resolve(Map<String,Long> deltas,String payer);
        ClaimExchange.Status status(ClaimExchange t);
        ClaimExchange.Status commit(ClaimExchange t);
        default boolean minor() { return false; }
        default long spendable(String name) { return balance(name); }
    }
    private final ClaimStore store;
    private final Bank bank;
    private boolean planning;
    ClaimFinance(ClaimStore store,Bank bank) { this.store=store; this.bank=bank; }
    boolean busy() { return planning||store.financialPending()!=null||!store.moneyAvailable(); }
    /** Read-only readiness: no registration, migration, persistence or WAL recovery. */
    boolean readReady() {
        if(busy()||!store.financialRegistered()) return false;
        UUID lineage=store.financialLineage();
        return bank.registered(lineage)&&bank.sequence(lineage)==store.financialSequence();
    }
    private boolean registrationRecoverable() {
        return store.moneyAvailable()&&store.financialSequence()==0&&store.financialPending()==null;
    }
    boolean binding() {
        if(!store.moneyAvailable()) return false;
        UUID local=store.financialLineage();
        UUID external=bank.registeredLineage();
        if(external!=null&&!external.equals(local)) {
            // A different Core lineage is authoritative only while both journals prove
            // that no Claims settlement has ever been committed. Adoption rewrites only
            // financial metadata; the land/profile/domain snapshot remains byte-for-byte
            // equivalent after canonicalization.
            if(!registrationRecoverable()||bank.sequence(external)!=0||!store.adoptFinanceLineage(external)) return false;
            local=store.financialLineage();
        }
        if(bank.registered(local)) {
            long highWater=bank.sequence(local);
            ClaimFinance.Pending pending=store.financialPending();
            if(highWater<0||(pending==null&&highWater!=store.financialSequence())) return false;
            return store.ensureFinance()&&store.markFinanceRegistered();
        }
        // A missing registration can be retried only before the first accepted
        // domain transition. Unknown Core lineage/history is never treated as empty.
        if(!registrationRecoverable()) return false;
        return store.ensureFinance()&&bank.register(local)&&store.markFinanceRegistered();
    }
    boolean recover() {
        synchronized(store) {
            if(!binding()) return false;
            var p=store.financialPending(); if(p==null) return bank.registered(store.financialLineage())&&bank.sequence(store.financialLineage())==store.financialSequence();
            var status=bank.status(p.exchange());
            if(status==ClaimExchange.Status.MISSING) status=bank.commit(p.exchange());
            if(status==ClaimExchange.Status.COMMITTED) return store.finishFinance(p);
            if(status==ClaimExchange.Status.CONFLICT) store.stopFinance("Конфликт sequence/receipt Claims; требуется согласованная копия");
            else if(status==ClaimExchange.Status.MISSING&&bank.status(p.exchange())==ClaimExchange.Status.MISSING) store.cancelFinance(p);
            return false;
        }
    }
    UUID bankLineage() { return bank.registeredLineage(); }
    boolean bankRegistered() { return bank.registered(store.financialLineage()); }
    long bankSequence() {
        UUID external=bank.registeredLineage();
        return bank.sequence(external==null?store.financialLineage():external);
    }
    String recoveryReason() {
        if(planning) return "planning";
        if(!store.moneyAvailable()) return "claims-store-unavailable";
        UUID local=store.financialLineage(), external=bank.registeredLineage();
        ClaimFinance.Pending pending=store.financialPending();
        if(external!=null&&!external.equals(local)) {
            long externalSequence=bank.sequence(external);
            return registrationRecoverable()&&externalSequence==0
                    ? "lineage-mismatch-safe-to-adopt" : "lineage-mismatch-financial-history";
        }
        if(!bank.registered(local)) return external==null?"bank-source-unregistered-or-unavailable":"bank-lineage-mismatch";
        long highWater=bank.sequence(local);
        if(highWater<0) return "bank-sequence-unavailable";
        if(pending!=null) return "pending-reconciliation";
        if(highWater!=store.financialSequence()) return "sequence-mismatch";
        if(!store.financialRegistered()) return "local-registration-ack-missing";
        return "ready";
    }
    <T> T execute(String kind,Function<ClaimStore,T> action,Predicate<T> success,T refused) {
        synchronized(store) {
            if(planning||!recover()) return refused;
            planning=true;
            try {
                long revision=store.financeRevision(); String before=store.domainJson();
                var plan=new Planning(bank); var shadow=store.financeCopy(plan);
                T result=action.apply(shadow); if(!success.test(result)) return result;
                if(!plan.valid) return refused;
                String after=shadow.domainJson();
                var deltas=bank.resolve(plan.deltas,plan.payer); if(deltas==null||store.financeRevision()!=revision||!before.equals(store.domainJson())) return refused;
                var t=new ClaimExchange(UUID.randomUUID(),store.financialLineage(),store.financialSequence()+1,kind,deltas,ClaimExchange.hash(after),bank.minor());
                if(!store.prepareFinance(new Pending(t,ClaimExchange.hash(before),after))) return refused;
                return recover()?result:refused;
            } catch(RuntimeException e) {
                // A detached plan/reentrant native callback cannot publish a partial domain.
                // If WAL/IO is already uncertain its pending/blocked state remains untouched.
                return refused;
            } finally { planning=false; }
        }
    }
    private static final class Planning implements ClaimAccounts {
        final Bank bank; final Map<String,Long> deltas=new LinkedHashMap<>(); String payer; boolean valid=true;
        Planning(Bank bank) { this.bank=bank; }
        public boolean ready(String name) { return bank.ready(name); }
        public long balance(String name) { return bank.balance(name)+deltas.getOrDefault(name.toLowerCase(Locale.ROOT),0L); }
        public boolean exchange(Map<String,Long> changes,String paymentPayer) {
            var next=new LinkedHashMap<>(deltas);
            try {
                if(paymentPayer!=null) { if(payer!=null&&!payer.equalsIgnoreCase(paymentPayer)) return false; payer=paymentPayer; }
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
