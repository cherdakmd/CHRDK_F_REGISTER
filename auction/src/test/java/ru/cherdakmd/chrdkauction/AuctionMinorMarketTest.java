package ru.cherdakmd.chrdkauction;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AuctionMinorMarketTest {
    @TempDir Path dir;
    @Test void oldLotsAreScaledOnceAtMigrationNotOnEveryRestart()throws Exception{
        var s=new AuctionStore(dir,Logger.getAnonymousLogger());s.enableBank();
        var seller=UUID.randomUUID();s.prepareListing(seller,"Seller",ItemCodec.encode(new ItemStack(Material.STONE)),500,5,Long.MAX_VALUE,1,10,10,"before","after");
        s.complete();s.enableMarket();var lot=s.lots().getFirst();assertEquals(50000,lot.price());assertEquals(2500,lot.fee());
        var restarted=new AuctionStore(dir,Logger.getAnonymousLogger());restarted.load();restarted.enableMarket();assertEquals(50000,restarted.lots().getFirst().price());
    }
    @Test void bankFirstPurchaseTakesOnlyPlainDiamondsAndReturnsExactChange(){
        var before=new ItemStack[36];before[0]=new ItemStack(Material.DIAMOND,2);before[1]=new ItemStack(Material.DIAMOND_ORE,64);
        var plan=AuctionWallet.planMinor(before,125,100,10_000);assertNotNull(plan);
        assertEquals(100,plan.debit());assertEquals(100,plan.cash());assertEquals(75,plan.change());
        assertEquals(1,plan.after()[0].getAmount());assertEquals(64,plan.after()[1].getAmount());
        assertNull(AuctionWallet.planMinor(before,126,100,10_000));
    }
    @Test void minorReceiptFreezesRateAndRejectsTampering(){
        var p=new AuctionPayment(UUID.randomUUID(),UUID.randomUUID(),1,1,UUID.randomUUID(),UUID.randomUUID(),125,2,100,100,75,"a".repeat(64),true,10_000);
        assertTrue(p.valid());assertEquals(10_000,p.rateBps());
        assertFalse(new AuctionPayment(p.transaction(),p.market(),p.sequence(),p.lot(),p.buyer(),p.seller(),p.price(),p.fee(),p.debit(),p.cash(),p.change(),p.inventoryHash(),true,1).valid());
    }
}
