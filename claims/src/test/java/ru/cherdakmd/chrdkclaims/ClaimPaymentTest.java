package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaimPaymentTest {
    @Test void bankFirstAndDenominationsSmallFirst() {
        int[] source={2,4}; var p=ClaimPayment.plan(10,7,source,new int[]{9,1});
        assertNotNull(p); assertEquals(7,p.bank()); assertEquals(3,p.physical()); assertEquals(0,p.change());
        assertArrayEquals(new int[]{2,1},p.remaining()); assertArrayEquals(new int[]{2,4},source);
    }
    @Test void wholeBlockChangeIsCreditedToBank() {
        var p=ClaimPayment.plan(10,7,new int[]{1},new int[]{9});
        assertEquals(7,p.bank()); assertEquals(9,p.physical()); assertEquals(6,p.change());
        assertArrayEquals(new int[]{0},p.remaining());
        assertEquals(10,p.bank()+p.physical()-p.change());
    }
    @Test void bankCoversPriceWithoutConsumingPhysicalItems() {
        var p=ClaimPayment.plan(16,100,new int[]{64},new int[]{9});
        assertEquals(16,p.bank()); assertEquals(0,p.physical()); assertArrayEquals(new int[]{64},p.remaining());
    }
    @Test void insufficientAndMalformedInputAreNonMutating() {
        int[] source={1}; assertNull(ClaimPayment.plan(12,2,source,new int[]{9})); assertArrayEquals(new int[]{1},source);
        assertNull(ClaimPayment.plan(-1,10,source,new int[]{1}));
        assertNull(ClaimPayment.plan(1,0,new int[]{65},new int[]{1}));
        assertNull(ClaimPayment.plan(1,0,source,new int[]{3}));
        assertNull(ClaimPayment.plan(1,0,source,new int[0]));
    }
    @Test void ignoredSlotsCannotPayEvenIfTheyContainNamedDiamonds() {
        // Адаптер передаёт value=0 для предметов с метаданными; offhand/armor/cursor вовсе не входят в 36 слотов.
        assertNull(ClaimPayment.plan(1,0,new int[]{64},new int[]{0}));
    }
}
