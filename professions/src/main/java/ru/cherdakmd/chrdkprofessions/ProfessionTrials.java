package ru.cherdakmd.chrdkprofessions;

import java.util.Map;
import java.util.UUID;

/** Четыре разряда: XP не заменяет подтверждённую работу. Валюты и повторной награды нет. */
public final class ProfessionTrials {
    public record Trial(int index,int level,int units,int sources,int works){}
    public record Progress(Trial trial,int units,int sources,int works,boolean claimed,boolean ready){}
    public static final java.util.List<Trial> ALL=java.util.List.of(
            new Trial(0,25,100,2,1),new Trial(1,50,400,3,5),
            new Trial(2,75,1000,4,15),new Trial(3,100,2500,5,30));
    private final ProfessionService service;
    private final ProfessionStore store;
    public ProfessionTrials(ProfessionService service,ProfessionStore store){this.service=service;this.store=store;}
    public Progress progress(UUID id,Profession profession,int index){
        Trial trial=ALL.get(index);Map<String,Integer> counts=store.practice(id,profession);
        int units=counts.entrySet().stream().filter(e->!e.getKey().equals("work")).mapToInt(Map.Entry::getValue).sum();
        int sources=(int)counts.entrySet().stream().filter(e->!e.getKey().equals("work")&&e.getValue()>0).count();
        int works=counts.getOrDefault("work",0),mask=store.trials(id,profession);
        boolean claimed=(mask&(1<<index))!=0;
        boolean previous=index==0||(mask&(1<<(index-1)))!=0;
        return new Progress(trial,units,sources,works,claimed,!claimed&&previous
                &&service.level(id,profession)>=trial.level()&&units>=trial.units()&&sources>=trial.sources()&&works>=trial.works());
    }
    public boolean claim(UUID id,Profession profession,int index){
        if(id==null||profession==null||index<0||index>=ALL.size()||service.active(id)!=profession)return false;
        return progress(id,profession,index).ready()&&store.claimTrial(id,profession,index);
    }
    public int rank(UUID id,Profession profession){return Integer.bitCount(store.trials(id,profession));}
    public int batch(UUID id,ProfessionMastery mastery){return mastery.repairPercent()>0?1:Math.min(64,mastery.batch()+8*rank(id,mastery.profession()));}
    public int cooldown(UUID id,ProfessionMastery mastery){return Math.max(60,mastery.cooldownSeconds()*(100-10*rank(id,mastery.profession()))/100);}
}
