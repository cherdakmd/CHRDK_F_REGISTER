package ru.cherdakmd.chrdkauction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryDecisionTest {
    @Test void crashBeforeTransferRollsBack() { assertEquals(RecoveryDecision.ROLLBACK,RecoveryDecision.of(true,false)); }
    @Test void crashAfterTransferCompletes() { assertEquals(RecoveryDecision.COMPLETE,RecoveryDecision.of(false,true)); }
    @Test void ambiguityNeverGuesses() {
        assertEquals(RecoveryDecision.CONFLICT,RecoveryDecision.of(false,false));
        assertEquals(RecoveryDecision.CONFLICT,RecoveryDecision.of(true,true));
    }
}
