package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import java.util.Set;

/** Неперекрывающиеся десятисекундные окна и гистерезис; вызывается под замком GuardDefense. */
final class GuardPressure {
    static final long WINDOW=10_000_000_000L;
    private long start, lastQualified;
    private int rejected, consecutive;
    private boolean active;
    private final Set<String> addresses=new HashSet<>(); // Не более 256 адресов текущего окна.
    GuardPressure(long now) { start=now; }

    void advance(GuardTuning tuning,long now) {
        long elapsed=now-start;
        if(elapsed>=WINDOW) {
            long windows=elapsed/WINDOW;
            boolean qualified=rejected>=tuning.attackRejects() && addresses.size()>=tuning.attackAddresses();
            if(qualified) {
                consecutive++;
                lastQualified=start+WINDOW;
                if(consecutive>=tuning.attackWindows() && tuning.automatic()) active=true;
            } else consecutive=0;
            if(windows>1) consecutive=0; // Пропущенные тихие окна не считаются нагрузкой.
            start+=windows*WINDOW;rejected=0;addresses.clear();
        }
        if(!tuning.automatic() || (active && now-lastQualified>=tuning.quietSeconds()*1_000_000_000L)) active=false;
    }
    void rejection(GuardTuning tuning,String address,long now) {
        advance(tuning,now);
        rejected=Math.min(1_000_000,rejected+1);
        if(addresses.size()<256) addresses.add(address);
    }
    boolean active(GuardTuning tuning,long now) { advance(tuning,now);return active; }
    int addressCount() { return addresses.size(); }
}
