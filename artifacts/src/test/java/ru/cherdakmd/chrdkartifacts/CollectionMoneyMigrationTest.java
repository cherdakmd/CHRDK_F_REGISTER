package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionMoneyMigrationTest {
    @Test void legacyClaimsReservationsAndEveryPreviouslyReachedPercentAreBlocked(){
        var id=UUID.randomUUID();
        var found=ArtifactRegistry.all().stream().limit(ArtifactRegistry.size()/2).map(ArtifactDef::id).collect(java.util.stream.Collectors.toSet());
        int percent=CollectionRewards.percentOf(found.size(),ArtifactRegistry.size());
        var rows=CollectionMoneyMigration.snapshot(Map.of(id,Set.of("percent:90")),Map.of(id,Set.of("percent:100")),Map.of(id,found)).get(id);
        assertEquals(State.LEGACY_UNCERTAIN,rows.get("percent:"+percent));
        assertFalse(rows.containsKey("percent:"+(percent+1)));
        assertEquals(State.LEGACY_UNCERTAIN,rows.get("percent:90"));
        assertEquals(State.LEGACY_UNCERTAIN,rows.get("percent:100"));
    }
}
