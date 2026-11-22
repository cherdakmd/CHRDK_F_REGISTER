package ru.cherdakmd.chrdkachievements;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AchievementMoneyMigrationTest {
    @Test void allPreviouslyOpenedClaimedAndReservedIdsAreConservativelyFrozen(){
        var id=UUID.randomUUID();
        var result=AchievementMoneyMigration.snapshot(Map.of(id,Set.of("claimed")),Map.of(id,Set.of("opened")),Map.of(id,Set.of("reserved")));
        assertEquals(Map.of("claimed",State.LEGACY_UNCERTAIN,"opened",State.LEGACY_UNCERTAIN,"reserved",State.LEGACY_UNCERTAIN),result.get(id));
        assertFalse(result.get(id).containsKey("new"));
    }
}
