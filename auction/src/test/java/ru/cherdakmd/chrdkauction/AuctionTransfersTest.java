package ru.cherdakmd.chrdkauction;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class AuctionTransfersTest {
    @TempDir Path dir;
    final UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID(); final Logger log=Logger.getAnonymousLogger();
    AuctionStore store() throws Exception { var s=new AuctionStore(dir.resolve("auction"),log); s.load(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),log); b.load(); return b; }
    long listing(AuctionStore s) throws Exception { return s.prepareListing(seller,"Seller","AQI=",100,5,2000,1000,10,500,"before","after"); }
    AuctionPayment prepare(boolean cash) throws Exception {
        var s=store(); long lot=listing(s); s.complete(); s.enableBank(); var b=bank(); b.importLegacyAuction(s.market(),seller,0); b.importLegacyAuction(s.market(),buyer,0); if(!cash) b.deposit(buyer,"Buyer",100); b.flush();
        var p=new AuctionPayment(UUID.randomUUID(),s.market(),s.nextBankSequence(),lot,buyer,seller,100,5,cash?0:100,cash?108:0,cash?8:0,AuctionPayment.hash("before",cash?"after":"before"));
        s.prepareBankBuy(p,1500,"before",cash?"after":"before"); return p;
    }
    static class Bag implements InventoryTransfer.Inventory {
        final UUID owner; String state="before",token,durableToken; int applies,saves; boolean failSave;
        Bag(UUID owner) { this.owner=owner; }
        public UUID owner() { return owner; } public boolean available() { return true; }
        public String witness() { return token; } public boolean matches(String expected) { return state.equals(expected); }
        public void apply(String after,String nonce) throws IOException { applies++; state=after; token=nonce; checkpoint(); }
        public void checkpoint() throws IOException { saves++; if(failSave) throw new IOException("saveData refused"); durableToken=token; }
    }
    @Test void committedCashNeedsDurableNonceBeforeParcelPublication() throws Exception {
        var p=prepare(true); assertTrue(bank().settleAuction(p)); var bag=new Bag(buyer);
        assertEquals(AuctionRecovery.Decision.APPLY_AFTER,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertNull(store().pending()); assertEquals(1,store().mail(buyer).size()); assertEquals(p.transaction().toString(),bag.durableToken); assertEquals(8,bank().balance(buyer)); assertEquals(95,bank().balance(seller));
    }
    @Test void afterSnapshotAloneCannotMakeAnAuctionPurchaseComplete() throws Exception {
        var p=prepare(true); bank().settleAuction(p); var bag=new Bag(buyer); bag.state="after";
        assertEquals(AuctionRecovery.Decision.CONFLICT,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertNotNull(store().pending()); assertTrue(store().mail(buyer).isEmpty()); assertEquals(0,bag.applies);
    }
    @Test void unsavedInMemoryNonceCannotClosePendingAndRetryNeverDebitsCashTwice() throws Exception {
        var p=prepare(true); bank().settleAuction(p); var bag=new Bag(buyer); bag.failSave=true;
        assertThrows(IOException.class,()->AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertThrows(IOException.class,()->AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertNotNull(store().pending()); assertNull(bag.durableToken);
        bag.failSave=false; bag.state="legitimate current inventory"; assertEquals(AuctionRecovery.Decision.COMPLETE,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertEquals(1,bag.applies); assertEquals("legitimate current inventory",bag.state); assertEquals(1,store().mail(buyer).size());
    }
    @Test void bankOnlyCompletesOfflineWithoutInspectingStorage() throws Exception {
        var p=prepare(false); bank().settleAuction(p); assertEquals(AuctionRecovery.Decision.COMPLETE,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),null)); assertEquals(1,store().mail(buyer).size()); assertEquals(95,bank().balance(seller));
    }
    @Test void missingReceiptRollsBackOnlyUntouchedInventory() throws Exception {
        var p=prepare(true); var bag=new Bag(buyer); assertEquals(AuctionRecovery.Decision.ROLLBACK,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertNull(store().pending()); assertNotNull(store().lot(p.lot())); assertEquals(0,bag.applies);
    }
    @Test void missingReceiptWithNonceCannotBeTreatedAsUnpaid() throws Exception {
        var p=prepare(true); var bag=new Bag(buyer); bag.token=p.transaction().toString(); assertEquals(AuctionRecovery.Decision.CONFLICT,AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),bag)); assertNotNull(store().pending());
    }
    @Test void listingIntentRetainsTokenAcrossRestartAndPublishesOnlyAfterNativeSave() throws Exception {
        var s=store(); long id=listing(s); UUID token=s.pending().transferId(); assertNotNull(token); assertEquals(token,store().pending().transferId()); var bag=new Bag(seller);
        assertEquals(InventoryTransfer.Decision.APPLIED,AuctionTransfers.item(store(),bag)); assertNotNull(store().lot(id)); assertNull(store().pending()); assertEquals(token.toString(),bag.durableToken);
    }
    @Test void itemClaimRetriesCheckpointWithoutReissuingTheParcel() throws Exception {
        var p=prepare(false); bank().settleAuction(p); AuctionTransfers.bank(store(),bank().auctionPaymentStatus(p),null); var s=store(); s.prepareItemClaim(buyer,p.lot(),"before","after"); var bag=new Bag(buyer); bag.failSave=true;
        assertThrows(IOException.class,()->AuctionTransfers.item(store(),bag)); assertEquals(1,store().mail(buyer).size()); bag.failSave=false; bag.state="current inventory";
        assertEquals(InventoryTransfer.Decision.APPLIED,AuctionTransfers.item(store(),bag)); assertTrue(store().mail(buyer).isEmpty()); assertEquals(1,bag.applies); assertEquals("current inventory",bag.state);
    }
    @Test void legacyPendingWithoutATransferNonceIsPreservedForManualReconciliation() throws Exception {
        var s=store(); listing(s); var file=dir.resolve("auction/market.json"); var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); json.getAsJsonObject("pending").remove("transferId"); Files.writeString(file,json.toString()); String original=Files.readString(file);
        assertEquals(InventoryTransfer.Decision.CONFLICT,AuctionTransfers.item(store(),new Bag(seller))); assertNotNull(store().pending()); assertEquals(original,Files.readString(file));
    }
    @Test void runtimeAfterSourceRenameStopsStaleWriterAndLeavesPublishedListingDurable() throws Exception {
        var s=store(); long id=listing(s); var failed=new AuctionStore(dir.resolve("auction"),log,()->{throw new IllegalStateException("after replace");}); failed.load(); var bag=new Bag(seller);
        assertThrows(IllegalStateException.class,()->AuctionTransfers.item(failed,bag)); assertFalse(failed.healthy()); assertNotNull(store().lot(id)); assertNull(store().pending()); assertEquals(1,bag.applies);
    }
    @Test void productionUsesProofExecutorForCashListingAndClaimWithDisjointRootGuardNamespace() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkauction/AuctionService.java"));
        assertTrue(code.contains("AuctionTransfers.bank(store,status")); assertTrue(code.contains("AuctionTransfers.item(store,inventory(p,\"auction-items\"))")); assertTrue(code.contains("new NamespacedKey(\"chrdk_inventory\",source)")); assertFalse(code.contains("p.saveData()")); assertFalse(code.contains("RecoveryDecision.of("));
    }
}
