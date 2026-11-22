package ru.cherdakmd.chrdkartifacts;

import ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State;
import java.util.*;

/** Previously reached percentages may have been accepted before progress was saved. */
final class CollectionMoneyMigration {
    static Map<UUID,Map<String,State>> snapshot(Map<UUID,Set<String>> claims,Map<UUID,Set<String>> reservations,
            Map<UUID,Set<String>> discovered){
        var out=new LinkedHashMap<UUID,Map<String,State>>();
        for(var input:List.of(claims,reservations))for(var owner:input.entrySet()) {
            var rows=out.computeIfAbsent(owner.getKey(),unused->new LinkedHashMap<>());
            for(String key:owner.getValue())rows.put(key,State.LEGACY_UNCERTAIN);
        }
        for(var owner:discovered.entrySet()) {
            int found=(int)owner.getValue().stream().filter(id->ArtifactRegistry.byId(id)!=null).count();
            int percent=CollectionRewards.percentOf(found,ArtifactRegistry.size());
            if(percent>0){var rows=out.computeIfAbsent(owner.getKey(),unused->new LinkedHashMap<>());
                for(int i=1;i<=percent;i++)rows.putIfAbsent("percent:"+i,State.LEGACY_UNCERTAIN);}
        }
        return out;
    }
    private CollectionMoneyMigration(){}
}
