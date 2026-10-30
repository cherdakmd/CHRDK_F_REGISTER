package ru.cherdakmd.chrdkclasses;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class PveContributionLedgerTest {
    private final UUID id=UUID.randomUUID();
    @Test void noXpForUnprovenOrRepeatedHealing() {
        var l=new PveContributionLedger();assertEquals(0,l.healed(id,5,1000));
        l.hurt(id,6,1000);assertEquals(4,l.healed(id,4,1100));assertEquals(2,l.healed(id,8,1200));assertEquals(0,l.healed(id,8,1300));
    }
    @Test void vanillaHealingConsumesTheSameWound() {
        var l=new PveContributionLedger();l.hurt(id,6,1000);l.healed(id,6,1100);assertEquals(0,l.debt(id,1200));
    }
    @Test void oldAndClockReversedWoundsDoNotAward() {
        var l=new PveContributionLedger();l.hurt(id,6,1000);assertEquals(0,l.debt(id,999));assertEquals(0,l.debt(id,61001));
    }
    @Test void damageDebtIsBoundedAndCanBeInvalidated() {
        var l=new PveContributionLedger();l.hurt(id,1000,1000);assertEquals(20,l.debt(id,1100));l.forgetWound(id);assertEquals(0,l.debt(id,1200));
    }
    @Test void perMinuteLimitDoesNotResetWhenWoundIsForgotten() {
        var l=new PveContributionLedger();assertEquals(500,l.accept(id,1000,1000));l.forgetWound(id);
        assertEquals(0,l.accept(id,25,2000));assertEquals(0,l.accept(id,25,999));assertEquals(25,l.accept(id,25,61000));
    }
    @Test void invalidHealthValuesCannotCreateCredit() {
        var l=new PveContributionLedger();l.hurt(id,Double.NaN,1000);l.hurt(id,-4,1000);l.hurt(id,Double.POSITIVE_INFINITY,1000);assertEquals(0,l.debt(id,1100));
    }
}
