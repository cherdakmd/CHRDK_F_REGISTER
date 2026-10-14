package ru.cherdakmd.chrdkauction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class AuctionStoreTest {
    @TempDir Path dir;
    private final UUID seller=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID buyer=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String ITEM=Base64.getEncoder().encodeToString(new byte[]{1,2,3});
    private AuctionStore load() throws IOException { var s=new AuctionStore(dir,Logger.getAnonymousLogger());s.load();return s; }
    private long listing(AuctionStore s,int price) throws IOException {
        long id=s.prepareListing(seller,"Seller",ITEM,price,5,2000,1000,10,100,"with item","empty hand");s.complete();return id;
    }
    @Test void ceilingFeeAndBounds() {
        assertEquals(1,AuctionStore.fee(20,5));assertEquals(2,AuctionStore.fee(21,5));
        assertEquals(5,AuctionStore.fee(100,5));assertEquals(0,AuctionStore.fee(20,0));
        assertEquals(50000,AuctionStore.fee(1000000,5));
        assertThrows(IllegalArgumentException.class,()->AuctionStore.fee(-1,5));
        assertThrows(IllegalArgumentException.class,()->AuctionStore.fee(Integer.MAX_VALUE,5));
        assertThrows(IllegalArgumentException.class,()->AuctionStore.fee(20,51));
    }
    @Test void escrowListingPublishesOnlyAfterExternalTransfer() throws Exception {
        var s=load();long id=s.prepareListing(seller,"Seller",ITEM,100,5,2000,1000,10,100,"before","after");
        assertNull(s.lot(id));assertNotNull(s.pending());
        var restarted=load();assertEquals("before",restarted.pending().before());assertEquals("after",restarted.pending().after());
        assertEquals(seller,restarted.pending().player());restarted.complete();
        assertEquals(100,load().lot(id).price());assertNull(load().pending());
    }
    @Test void abandonedListingRollsBackWithoutLosingSequence() throws Exception {
        var s=load();s.prepareListing(seller,"Seller",ITEM,100,5,2000,1000,10,100,"before","after");
        var restarted=load();restarted.rollback();assertTrue(load().lots().isEmpty());
        assertEquals(1,listing(load(),100));
    }
    @Test void buySettlesItemAndSellerMoneyExactlyOnceAndSurvivesRestart() throws Exception {
        var s=load();long id=listing(s,101);
        s.prepareBuy(buyer,id,1500,"diamonds","paid");
        assertNotNull(s.lot(id));assertEquals(0,s.balance(seller));assertTrue(s.mail(buyer).isEmpty());
        var restarted=load();restarted.complete();
        var settled=load();assertNull(settled.lot(id));assertEquals(95,settled.balance(seller));
        assertEquals(ITEM,settled.mail(buyer).getFirst().item());
        assertThrows(IllegalArgumentException.class,()->settled.prepareBuy(buyer,id,1501,"diamonds","paid"));
        assertThrows(IllegalStateException.class,settled::complete);
    }
    @Test void pendingTransferBlocksAllOtherTransitions() throws Exception {
        var s=load();long id=listing(s,100);s.prepareBuy(buyer,id,1500,"before","after");
        assertThrows(IllegalStateException.class,()->s.cancel(seller,id));
        assertThrows(IllegalStateException.class,()->s.expire(5000));
        assertThrows(IllegalStateException.class,()->s.prepareMoneyClaim(seller,1,"a","b"));
        assertThrows(IllegalStateException.class,()->s.prepareBuy(UUID.randomUUID(),id,1500,"a","b"));
    }
    @Test void invalidPurchasesDoNotDebitOrRemoveAnything() throws Exception {
        var s=load();long id=listing(s,100);
        assertThrows(IllegalArgumentException.class,()->s.prepareBuy(seller,id,1500,"a","b"));
        assertThrows(IllegalArgumentException.class,()->s.prepareBuy(buyer,id,2000,"a","b"));
        assertThrows(IllegalArgumentException.class,()->s.prepareBuy(buyer,999,1500,"a","b"));
        assertNotNull(s.lot(id));assertNull(s.pending());assertEquals(0,s.balance(seller));
    }
    @Test void cancelAndExpirationReturnItemsOnceWithoutFee() throws Exception {
        var s=load();long first=listing(s,100), second=listing(s,200);
        assertThrows(IllegalArgumentException.class,()->s.cancel(buyer,first));
        s.cancel(seller,first);s.expire(2000);s.expire(3000);
        assertTrue(s.lots().isEmpty());assertEquals(2,load().mail(seller).size());assertEquals(0,s.balance(seller));
        assertEquals(Set.of(first,second),new HashSet<>(s.mail(seller).stream().map(AuctionStore.Parcel::id).toList()));
    }
    @Test void claimsAreOwnedPartialAndDurable() throws Exception {
        var s=load();long id=listing(s,100);s.prepareBuy(buyer,id,1500,"d","p");s.complete();
        assertThrows(IllegalArgumentException.class,()->s.prepareItemClaim(seller,id,"a","b"));
        s.prepareItemClaim(buyer,id,"a","b");s.rollback();assertEquals(1,load().mail(buyer).size());
        s.prepareItemClaim(buyer,id,"a","b");s.complete();assertTrue(load().mail(buyer).isEmpty());
        s.prepareMoneyClaim(seller,64,"a","b");s.complete();assertEquals(31,load().balance(seller));
        s.prepareMoneyClaim(seller,31,"a","b");s.complete();assertEquals(0,load().balance(seller));
        final var end=s;
        assertThrows(IllegalArgumentException.class,()->end.prepareMoneyClaim(seller,1,"a","b"));
    }
    @Test void failedPrepareDoesNotConsumeItemOrPublishJournal() throws Exception {
        var s=load();Files.createDirectory(dir.resolve("market.json.tmp"));
        assertThrows(IOException.class,()->s.prepareListing(seller,"Seller",ITEM,100,5,2000,1000,10,100,"a","b"));
        assertNull(s.pending());assertTrue(s.lots().isEmpty());
    }
    @Test void failedFinalCommitKeepsJournalForRecovery() throws Exception {
        var s=load();long id=listing(s,100);s.prepareBuy(buyer,id,1500,"before","after");
        Files.createDirectory(dir.resolve("market.json.tmp"));
        assertThrows(IOException.class,s::complete);
        assertNotNull(s.pending());assertNotNull(load().pending());assertNotNull(s.lot(id));
        Files.delete(dir.resolve("market.json.tmp"));load().complete();assertNull(load().lot(id));assertEquals(95,load().balance(seller));
    }
    @Test void corruptLedgerNeverResetsEconomy() throws Exception {
        for(String text:List.of("broken","null","{}","{\"schema\":999,\"state\":{}}","{\"schema\":1,\"state\":{}}")) {
            Files.writeString(dir.resolve("market.json"),text);
            assertThrows(IOException.class,this::load);assertEquals(text,Files.readString(dir.resolve("market.json")));
        }
    }
    @Test void frozenFeeLimitsAndPayloadBounds() throws Exception {
        var s=load();long id=listing(s,21);assertEquals(2,s.lot(id).fee());
        assertThrows(IllegalArgumentException.class,()->s.prepareListing(seller,"Seller",ITEM,100,5,2000,1000,1,100,"a","b"));
        assertThrows(IllegalArgumentException.class,()->s.prepareListing(buyer,"Buyer","x".repeat(90001),100,5,2000,1000,10,100,"a","b"));
        assertNull(s.pending());assertEquals(1,s.lots().size());
    }
}
