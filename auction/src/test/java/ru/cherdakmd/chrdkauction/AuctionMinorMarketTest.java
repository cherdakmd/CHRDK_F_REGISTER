package ru.cherdakmd.chrdkauction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.api.AuctionTender;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AuctionMinorMarketTest {
    @TempDir Path dir;
    @Test void oldLotsAreScaledOnceAtMigrationNotOnEveryRestart()throws Exception{
        var s=new AuctionStore(dir,Logger.getAnonymousLogger());s.enableBank();
        var seller=UUID.randomUUID();s.prepareListing(seller,"Seller",java.util.Base64.getEncoder().encodeToString(new byte[]{1,2,3}),500,5,Long.MAX_VALUE,1,10,10,"before","after");
        s.complete();s.enableMarket();var lot=s.lots().getFirst();assertEquals(50000,lot.price());assertEquals(2500,lot.fee());
        var restarted=new AuctionStore(dir,Logger.getAnonymousLogger());restarted.load();restarted.enableMarket();assertEquals(50000,restarted.lots().getFirst().price());
    }
    @Test void bankFirstPurchaseTakesOnlyPlainDiamondsAndReturnsExactChange(){
        int[] counts=new int[36],values=new int[36];counts[0]=2;values[0]=1;
        var plan=AuctionTender.planMinor(125,100,10_000,counts,values);assertNotNull(plan);
        assertEquals(100,plan.debit());assertEquals(100,plan.cash());assertEquals(75,plan.change());assertEquals(1,plan.remaining()[0]);
        assertNull(AuctionTender.planMinor(326,100,10_000,counts,values));
    }
    @Test void minorReceiptFreezesRateAndRejectsTampering(){
        var p=new AuctionPayment(UUID.randomUUID(),UUID.randomUUID(),1,1,UUID.randomUUID(),UUID.randomUUID(),125,2,100,100,75,"a".repeat(64),true,10_000);
        assertTrue(p.valid());assertEquals(10_000,p.rateBps());
        assertFalse(new AuctionPayment(p.transaction(),p.market(),p.sequence(),p.lot(),p.buyer(),p.seller(),p.price(),p.fee(),p.debit(),p.cash(),p.change(),p.inventoryHash(),true,1).valid());
    }
}
