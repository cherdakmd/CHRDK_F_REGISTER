package ru.cherdakmd.chrdkregister;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Общий замок для async допуска, истории и режима атаки. Диска, DNS и HTTP здесь нет. */
final class GuardDefense {
    record Key(UUID id,String address) {
        Key { java.util.Objects.requireNonNull(id);java.util.Objects.requireNonNull(address); }
    }
    record Snapshot(boolean attack,int history,int addresses,int cooling) { }
    private static final class History {
        boolean recent;
        long authenticatedAt, strikeAt, cooldownAt, cooldownDuration;
        int strikes;
    }
    private GuardSettings settings;
    private GuardRates rates;
    private GuardRates.Bucket restricted;
    private final GuardPressure pressure;
    private final Map<Key,History> history=new HashMap<>();
    private long lastSweep;
    private boolean restrictedActive;

    GuardDefense(GuardSettings settings,long now) {
        pressure=new GuardPressure(now);lastSweep=now;configure(settings,now);
    }
    synchronized void configure(GuardSettings settings,long now) {
        this.settings=settings;rates=new GuardRates(settings,now);
        restricted=new GuardRates.Bucket(settings.defense().attackBurst(),settings.defense().attackPerSecond(),now);
        // Смена конфига не очищает strikes, cooldown и историю успешного входа.
    }
    private void maintenance(long now) {
        if(!pressure.active(settings.defense(),now))restrictedActive=false;
        if(now-lastSweep>=1_000_000_000L) {
            history.values().removeIf(h -> !recent(h,now) && (h.strikes==0 || now-h.strikeAt>=
                    Math.max(settings.defense().strikeSeconds()*1_000_000_000L,h.cooldownDuration)));
            lastSweep=now;
        }
    }
    private boolean recent(History h,long now) {
        return h!=null && h.recent && now-h.authenticatedAt<settings.defense().recentSeconds()*1_000_000_000L;
    }
    synchronized boolean returning(Key key,long now) { maintenance(now);return recent(history.get(key),now); }
    synchronized void authenticated(Key key,long now) {
        maintenance(now);
        if(key.address().equals("unknown"))return;
        History h=entry(key);if(h==null)return;
        h.recent=true;h.authenticatedAt=now;
    }
    private History entry(Key key) {
        History h=history.get(key);
        if(h==null && history.size()<settings.maxAddresses()) { h=new History();history.put(key,h); }
        return h;
    }
    synchronized void strike(Key key,long now) {
        maintenance(now);
        History h=entry(key);if(h==null)return;
        if(h.strikes>0 && now-h.strikeAt>=settings.defense().strikeSeconds()*1_000_000_000L) h.strikes=0;
        h.strikeAt=now;h.strikes=Math.min(40,h.strikes+1);
        int extra=h.strikes-settings.defense().strikes();
        if(extra>=0) {
            long seconds=Math.min(settings.defense().maxCooldownSeconds(),
                    settings.defense().cooldownSeconds()*(1L<<Math.min(30,extra)));
            h.cooldownAt=now;h.cooldownDuration=seconds*1_000_000_000L;
        }
    }
    synchronized long cooldownSeconds(Key key,long now) {
        maintenance(now);History h=history.get(key);
        if(h==null || h.cooldownDuration==0)return 0;
        long remaining=h.cooldownDuration-(now-h.cooldownAt);
        return remaining<=0 ? 0 : (remaining+999_999_999L)/1_000_000_000L;
    }
    synchronized GuardRates.Admission enter(Key key,long now) {
        maintenance(now);
        if(!settings.enabled())return GuardRates.Admission.ALLOW;
        if(cooldownSeconds(key,now)>0)return GuardRates.Admission.COOLDOWN;
        boolean attack=pressure.active(settings.defense(),now);
        if(attack && !restrictedActive)
            restricted=new GuardRates.Bucket(settings.defense().attackBurst(),settings.defense().attackPerSecond(),now);
        restrictedActive=attack;
        boolean returning=recent(history.get(key),now);
        GuardRates.Admission result=rates.enter(key.address(),returning,now);
        if(result==GuardRates.Admission.ALLOW && attack && !returning && !restricted.take(now))
            result=GuardRates.Admission.ATTACK_RATE;
        if(result!=GuardRates.Admission.ALLOW) pressure.rejection(settings.defense(),key.address(),now);
        return result;
    }
    synchronized void slotRejected(String address,long now) { maintenance(now);pressure.rejection(settings.defense(),address,now); }
    synchronized Snapshot snapshot(long now) {
        maintenance(now);
        int cooling=0;for(History h:history.values()) if(h.cooldownDuration>0 && now-h.cooldownAt<h.cooldownDuration)cooling++;
        return new Snapshot(settings.enabled() && pressure.active(settings.defense(),now),history.size(),rates.tracked(),cooling);
    }
}
