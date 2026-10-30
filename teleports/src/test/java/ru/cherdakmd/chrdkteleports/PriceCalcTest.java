package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceCalcTest {
    @Test void allDefaultRankPricesAndTheHighestRankRemainMeaningful() {
        int[] discounts={0,10,20,30,40,50};
        int[][] expected={{60,54,48,42,36,30},{40,36,32,28,24,20},{30,27,24,21,18,15},{20,18,16,14,12,10}};
        int[] costs={60,40,30,20};
        for(int i=0;i<costs.length;i++) for(int rank=0;rank<6;rank++)
            assertEquals(expected[i][rank],PriceCalc.apply(costs[i],rank>0,discounts[rank]));
    }
    @Test void evenLegacyOneDiamondOrFullDiscountCannotGoBelowTen() {
        for(int cost:new int[]{1,2,5,10,30,640}) for(int discount:new int[]{-1,0,50,100,999}) {
            assertTrue(PriceCalc.apply(cost,true,discount)>=10);
            assertTrue(PriceCalc.apply(cost,false,discount)>=10);
        }
    }
    @Test void onlyExplicitlyFreePointSetupMayRemainZero() {
        assertEquals(0,PriceCalc.apply(0,true,50));assertEquals(0,PriceCalc.apply(0,false,50));
        assertEquals(0,PriceCalc.apply(-1,true,50));
    }
    @Test void wholeCostRoundsUpAndUsesLongBeforeMultiplication() {
        assertEquals(16,PriceCalc.apply(31,true,50));
        assertEquals(Integer.MAX_VALUE,PriceCalc.apply(Integer.MAX_VALUE,false,0));
        assertEquals(1073741824,PriceCalc.apply(Integer.MAX_VALUE,true,50));
    }
}
