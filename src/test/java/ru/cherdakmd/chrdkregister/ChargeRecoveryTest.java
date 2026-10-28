package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.api.ChargeRecovery.Decision.*;
class ChargeRecoveryTest {
    @Test void committedCashOnlyAcceptsOneUnambiguousInventoryAndPublishedSourceCannotDebitAgain() {
        for (boolean sourceAfter:new boolean[]{false,true}) for (boolean before:new boolean[]{false,true}) for (boolean after:new boolean[]{false,true}) {
            var expected=!sourceAfter&&before&&!after ? APPLY_AFTER : CONFLICT;
            assertEquals(expected,ChargeRecovery.decide(BankCharge.Status.COMMITTED,9,!sourceAfter,sourceAfter,before,after));
        }
    }
    @Test void missingOnlyRollsBackUntouchedSourceAndUntouchedStorage() {
        for (boolean sb:new boolean[]{false,true}) for (boolean sa:new boolean[]{false,true}) for (boolean ib:new boolean[]{false,true}) for (boolean ia:new boolean[]{false,true})
            assertEquals(sb&&!sa&&ib&&!ia ? ROLLBACK:CONFLICT,ChargeRecovery.decide(BankCharge.Status.MISSING,9,sb,sa,ib,ia));
    }
    @Test void bankOnlyIgnoresPlayerdataButNeverIgnoresUnknownBankOrConflictingSource() {
        for (boolean ib:new boolean[]{false,true}) for (boolean ia:new boolean[]{false,true}) {
            assertEquals(COMPLETE,ChargeRecovery.decide(BankCharge.Status.COMMITTED,0,true,false,ib,ia));
            assertEquals(COMPLETE,ChargeRecovery.decide(BankCharge.Status.COMMITTED,0,false,true,ib,ia));
            assertEquals(ROLLBACK,ChargeRecovery.decide(BankCharge.Status.MISSING,0,true,false,ib,ia));
        }
        for (var status:BankCharge.Status.values()) {
            assertEquals(status==BankCharge.Status.UNAVAILABLE ? WAIT:CONFLICT,ChargeRecovery.decide(status,0,false,false,false,false));
        }
    }
    @Test void oneDiamondPriceWorksWithoutRelaxingAuctionPaymentMinimum() {
        int[] counts=new int[36], values=new int[36]; counts[0]=1; values[0]=9; var plan=AuctionTender.plan(1,0,counts,values); assertNotNull(plan); assertEquals(8,plan.change());
        assertFalse(new AuctionPayment(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),1,1,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),1,0,0,1,0,"a".repeat(64)).valid());
    }
}
