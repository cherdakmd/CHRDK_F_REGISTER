package ru.cherdakmd.chrdkprofessions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkprofessions.ProfessionWorkRecovery.Decision.*;
class ProfessionWorkRecoveryTest {
    @Test void committedOperationNeverReappliesEvenIfInventoryChangedOrRolledBack(){
        for(boolean before:new boolean[]{true,false})for(boolean after:new boolean[]{true,false})
            assertEquals(FINISH_COMMITTED,ProfessionWorkRecovery.decide(true,before,after));
    }
    @Test void onlyExactBeforeCanApplyAndExactAfterOnlySaves(){
        assertEquals(APPLY_BEFORE,ProfessionWorkRecovery.decide(false,true,false));
        assertEquals(SAVE_AFTER,ProfessionWorkRecovery.decide(false,false,true));
        assertEquals(SAVE_AFTER,ProfessionWorkRecovery.decide(false,true,true));
        assertEquals(HOLD,ProfessionWorkRecovery.decide(false,false,false));
    }
}
