package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ShopSaleRemainder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ShopSaleRemainderTest {
    @Test void everyPieceIsPaidExactlyWithoutRoundingUpOrLoss() {
        for(int batch:new int[]{2,64,512,2304}) {
            String remainder="0/1"; long total=0;
            for(int i=0;i<batch;i++) { var q=ShopSaleRemainder.quote(remainder,batch,3,1); remainder=q.remainder(); total+=q.money(); }
            assertEquals(3,total); assertEquals("0/1",remainder);
        }
    }
    @Test void mixedMaterialDenominatorsHaveNoIntermediateRounding() {
        var first=ShopSaleRemainder.quote("0/1",3,1,1);
        var second=ShopSaleRemainder.quote(first.remainder(),2,1,1);
        assertEquals(0,second.money()); assertEquals("5/6",second.remainder());
        var last=ShopSaleRemainder.quote(second.remainder(),6,1,1); assertEquals(1,last.money()); assertEquals("0/1",last.remainder());
    }
    @Test void malformedOrNoncanonicalRemaindersAreNeverAccepted() {
        for(String value:Arrays.asList(null,"","1/0","1/1","2/4","-1/2","01/2","0/2","1.5/2","0/01","1/"+"1".repeat(5000))) assertFalse(ShopSaleRemainder.valid(value),String.valueOf(value));
        assertThrows(IllegalArgumentException.class,()->ShopSaleRemainder.quote("0/1",0,1,1));
        assertThrows(IllegalArgumentException.class,()->ShopSaleRemainder.quote("0/1",64,0,1));
    }
}
