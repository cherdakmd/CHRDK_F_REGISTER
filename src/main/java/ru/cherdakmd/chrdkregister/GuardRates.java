package ru.cherdakmd.chrdkregister;

import java.util.HashMap;
import java.util.Map;

/** Ограничители по монотонным наносекундам; единый замок для всех потоков pre-login. */
final class GuardRates {
    enum Admission { ALLOW, IP_RATE, GLOBAL_RATE, CAPACITY }
    static final long IDLE_NANOS = 300_000_000_000L;

    static final class Bucket {
        private final double capacity;
        private final double perNano;
        private double tokens;
        private long last;
        Bucket(int capacity, double perSecond, long now) {
            if (capacity < 1 || !Double.isFinite(perSecond) || perSecond <= 0)
                throw new IllegalArgumentException("Неверный ограничитель");
            this.capacity=capacity; this.tokens=capacity; this.perNano=perSecond/1_000_000_000d; this.last=now;
        }
        boolean take(long now) {
            long elapsed=now-last;
            if (elapsed>0) { tokens=Math.min(capacity,tokens+elapsed*perNano); last=now; }
            if(tokens<1) return false;
            tokens-=1; return true;
        }
    }

    private static final class Address {
        final Bucket bucket;
        long seen;
        Address(GuardSettings s,long now) { bucket=new Bucket(s.ipBurst(),1d/s.ipRefillSeconds(),now); seen=now; }
    }
    private final GuardSettings settings;
    private final Bucket global;
    private final Map<String,Address> addresses=new HashMap<>();
    private long lastSweep;
    GuardRates(GuardSettings s,long now) { settings=s;global=new Bucket(s.globalBurst(),s.globalPerSecond(),now);lastSweep=now; }

    synchronized Admission enter(String address,long now) {
        if(now-lastSweep>=1_000_000_000L) {
            addresses.values().removeIf(entry -> now-entry.seen>=Math.max(IDLE_NANOS,settings.ipBurst()*(long)settings.ipRefillSeconds()*1_000_000_000L));
            lastSweep=now;
        }
        Address existing=addresses.get(address);
        if(existing!=null) {
            existing.seen=now;
            if(!existing.bucket.take(now)) return Admission.IP_RATE;
        }
        // Ни индивидуальный отказ, ни переполнение таблицы не расходуют квоту остальных.
        if(existing==null && addresses.size()>=settings.maxAddresses()) return Admission.CAPACITY;
        if(!global.take(now)) return Admission.GLOBAL_RATE;
        if(existing==null) {
            Address added=new Address(settings,now); added.bucket.take(now);addresses.put(address,added);
        }
        return Admission.ALLOW;
    }
    synchronized int tracked() { return addresses.size(); }
}
