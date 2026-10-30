package ru.cherdakmd.chrdkclasses;

import java.util.*;

/** Ограниченный журнал реальных PvE-ран и минутного XP. Не очищается релогом. */
final class PveContributionLedger {
    private static final int MAX_ENTRIES=8192;
    private record Debt(double amount,long at) { }
    private record Rate(long since,int xp) { }
    private final Map<UUID,Debt> debts=new LinkedHashMap<>();
    private final Map<UUID,Rate> rates=new LinkedHashMap<>();
    double debt(UUID id,long now) {
        Debt d=debts.get(id);
        return d==null || now<d.at() || now-d.at()>60_000 ? 0 : d.amount();
    }
    void hurt(UUID id,double actual,long now) {
        if(!Double.isFinite(actual)||actual<=0)return;
        debts.put(id,new Debt(Math.min(20,debt(id,now)+actual),now));trim(debts);
    }
    double healed(UUID id,double actual,long now) {
        if(!Double.isFinite(actual)||actual<=0)return 0;
        double useful=Math.min(debt(id,now),actual);
        debts.put(id,new Debt(Math.max(0,debt(id,now)-useful),now));trim(debts);
        return useful;
    }
    void forgetWound(UUID id) {debts.remove(id);}
    int accept(UUID id,int requested,long now) {
        if(requested<=0)return 0;
        Rate r=rates.get(id);
        if(r==null||now-r.since()>=60_000)r=new Rate(now,0);
        if(now<r.since())return 0;
        int accepted=Math.min(requested,500-r.xp());
        rates.put(id,new Rate(r.since(),r.xp()+accepted));trim(rates);
        return accepted;
    }
    private static void trim(Map<?,?> map) {if(map.size()>MAX_ENTRIES)map.remove(map.keySet().iterator().next());}
}
