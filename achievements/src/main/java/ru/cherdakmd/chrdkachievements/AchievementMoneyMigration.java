package ru.cherdakmd.chrdkachievements;

import ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State;
import java.util.*;

/** Preserve every old reservation, completed claim and unlocked achievement without inventing an outcome. */
final class AchievementMoneyMigration {
    @SafeVarargs static Map<UUID,Map<String,State>> snapshot(Map<UUID,Set<String>>... inputs){
        var out=new LinkedHashMap<UUID,Map<String,State>>();
        for(var input:inputs)for(var owner:input.entrySet()){
            var rows=out.computeIfAbsent(owner.getKey(),unused->new LinkedHashMap<>());
            for(String id:owner.getValue())rows.put(id,State.LEGACY_UNCERTAIN);
        }
        return out;
    }
    private AchievementMoneyMigration(){}
}
