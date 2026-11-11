package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.DiamondExchangePolicy;
import ru.cherdakmd.chrdkregister.api.DiamondMoney;

import static org.junit.jupiter.api.Assertions.*;

class DiamondExchangePolicyTest {
    private static DiamondExchangePolicy.Snapshot neutral() {
        return new DiamondExchangePolicy.Snapshot(1_000_000,0,0,0,0,20,0,1_000_000,20);
    }
    @Test void neutralMarketKeepsOneDiamondAtOneAndQuotesExactHundredths() {
        var q=DiamondExchangePolicy.quote(neutral(),10_000);
        assertEquals(10_000,q.rateBps()); assertEquals(100,q.creditMinor(1));
        assertEquals(12_300,q.creditMinor(123)); assertEquals("1",q.text());
        assertEquals(25,DiamondExchangePolicy.quote(neutral(),10_000).scaleMinor(25));
    }
    @Test void demandMoneySupplyEmissionAndActivityMoveRateWithoutRandomness() {
        var busy=new DiamondExchangePolicy.Snapshot(2_000_000,200_000,10_000,300_000,100_000,40,10,1_000_000,20);
        var a=DiamondExchangePolicy.quote(busy,10_000); var b=DiamondExchangePolicy.quote(busy,10_000);
        assertEquals(a,b); assertTrue(a.demandBps()>0); assertTrue(a.inflationBps()<0);
        assertTrue(a.emissionBps()<0); assertTrue(a.activityBps()>0); assertTrue(a.turnoverBps()>0);
    }
    @Test void everyObservationIsBoundedAndLongRunCannotLeaveBand() {
        var extreme=new DiamondExchangePolicy.Snapshot(Long.MAX_VALUE/2,Long.MAX_VALUE/3,0,Long.MAX_VALUE/4,0,1000,1000,100_000,1);
        int rate=10_000;
        for(int i=0;i<1000;i++) { int old=rate; rate=DiamondExchangePolicy.quote(extreme,rate).rateBps();
            assertTrue(Math.abs(rate-old)<=250); assertTrue(rate>=5000&&rate<=20000); }
        var empty=new DiamondExchangePolicy.Snapshot(0,0,Long.MAX_VALUE/4,0,0,0,0,1_000_000,20);
        assertEquals(10_250,DiamondExchangePolicy.quote(empty,10_000).rateBps());
    }
    @Test void invalidValuesAndOverflowDoNotBecomeNegativeMoney() {
        assertThrows(IllegalArgumentException.class,()->new DiamondExchangePolicy.Quote(4999,0,0,0,0,0));
        assertThrows(ArithmeticException.class,()->DiamondExchangePolicy.quote(neutral(),10_000).creditMinor(Long.MAX_VALUE));
        assertEquals(-1,DiamondMoney.whole(-1));
    }
}
