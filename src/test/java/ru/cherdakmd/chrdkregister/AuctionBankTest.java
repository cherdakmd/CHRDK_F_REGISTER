package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class AuctionBankTest {
    @TempDir Path dir;
    final UUID market=UUID.randomUUID(), buyer=UUID.randomUUID(), seller=UUID.randomUUID();
    BankStore load() { var b=new BankStore(dir,Logger.getAnonymousLogger()); b.load(); return b; }
    BankStore actors() {
        var b=load(); assertTrue(b.importLegacyAuction(market,buyer,0)); assertTrue(b.importLegacyAuction(market,seller,0)); return b;
    }
    AuctionPayment payment(long seq, long debit, long cash, long change) {
        return new AuctionPayment(UUID.randomUUID(),market,seq,seq,buyer,seller,100,5,debit,cash,change,AuctionPayment.hash("before","after"));
    }
    @Test void durableOnceImportSurvivesRestartAndSpendingAndRejectsOtherLineage() {
        var b=load(); assertTrue(b.importLegacyAuction(market,seller,70));
        b=load(); assertEquals(70,b.balance(seller)); assertTrue(b.withdraw(seller,50)); b.saveNow(); b=load();
        assertTrue(b.importLegacyAuction(market,seller,70)); assertEquals(20,b.balance(seller));
        assertFalse(b.importLegacyAuction(UUID.randomUUID(),seller,70)); assertFalse(b.importLegacyAuction(market,seller,71));
    }
    @Test void importCapRetainsClaimWithoutWritingReceipt() {
        var b=load(); b.deposit(seller,"Seller",BankStore.MAX_BALANCE);
        assertFalse(b.importLegacyAuction(market,seller,10)); assertTrue(b.withdraw(seller,10));
        assertTrue(b.importLegacyAuction(market,seller,10)); assertEquals(BankStore.MAX_BALANCE,load().balance(seller));
    }
    @Test void bankOnlySettlementAndReceiptAreOneRestartedFileAndRepeatIsNoop() {
        var b=actors(); b.deposit(buyer,"Buyer",150); var p=payment(1,100,0,0);
        assertEquals(AuctionPayment.Status.MISSING,b.auctionPaymentStatus(p)); assertTrue(b.settleAuction(p));
        b=load(); assertEquals(AuctionPayment.Status.COMMITTED,b.auctionPaymentStatus(p)); assertEquals(50,b.balance(buyer)); assertEquals(95,b.balance(seller));
        assertTrue(b.settleAuction(p)); assertEquals(95,b.balance(seller));
    }
    @Test void physicalOnlyAndMixedPaymentCreditSellerAndChangeWithoutPhysicalBankDebit() {
        var b=actors(); var p=payment(1,0,108,8); assertTrue(b.settleAuction(p));
        assertEquals(8,load().balance(buyer)); assertEquals(95,load().balance(seller));
        b=load(); b.deposit(buyer,"Buyer",42); var mixed=payment(2,50,54,4); assertTrue(b.settleAuction(mixed));
        assertEquals(4,load().balance(buyer)); assertEquals(190,load().balance(seller));
    }
    @Test void sellerCapAndBuyerShortageDoNotCommitOrDebit() {
        var b=actors(); b.deposit(buyer,"Buyer",100); b.deposit(seller,"Seller",BankStore.MAX_BALANCE); var p=payment(1,100,0,0);
        assertFalse(b.settleAuction(p)); assertEquals(100,b.balance(buyer)); assertEquals(AuctionPayment.Status.MISSING,b.auctionPaymentStatus(p));
        assertTrue(b.withdraw(seller,95)); assertTrue(b.withdraw(buyer,1)); assertFalse(b.settleAuction(p)); assertEquals(99,b.balance(buyer));
    }
    @Test void buyerChangeCapRefusesWholeSettlement() {
        var b=actors(); b.deposit(buyer,"Buyer",BankStore.MAX_BALANCE); var p=payment(1,0,108,8);
        assertFalse(b.settleAuction(p)); assertEquals(0,b.balance(seller)); assertEquals(BankStore.MAX_BALANCE,b.balance(buyer));
    }
    @Test void highWaterRejectsOldDifferentAndSkippedPaymentsAfterRestart() {
        var b=actors(); var first=payment(1,0,100,0); assertTrue(b.settleAuction(first));
        assertEquals(AuctionPayment.Status.CONFLICT,b.auctionPaymentStatus(payment(1,0,100,0)));
        assertEquals(AuctionPayment.Status.CONFLICT,b.auctionPaymentStatus(payment(3,0,100,0)));
        var second=payment(2,0,100,0); assertTrue(b.settleAuction(second)); b=load();
        assertEquals(AuctionPayment.Status.CONFLICT,b.auctionPaymentStatus(first)); assertFalse(b.settleAuction(first));
        assertEquals(190,b.balance(seller)); assertEquals(AuctionPayment.Status.COMMITTED,b.auctionPaymentStatus(second));
    }
    @Test void importIoFailureDoesNotAcknowledgeOrAlterBalanceAndQuarantines() throws Exception {
        var b=load(); Files.createDirectories(dir.resolve("bank.json.tmp"));
        assertFalse(b.importLegacyAuction(market,seller,70)); assertFalse(b.available()); assertEquals(0,b.balance(seller));
        assertEquals(AuctionPayment.Status.UNAVAILABLE,b.auctionPaymentStatus(payment(1,0,100,0)));
    }
    @Test void settlementIoFailurePreservesPreviousFileAndReturnsUnknownUntilReload() throws Exception {
        var b=actors(); b.deposit(buyer,"Buyer",100); b.saveNow(); var p=payment(1,100,0,0);
        String original=Files.readString(b.file()); Files.createDirectory(dir.resolve("bank.json.tmp"));
        assertFalse(b.settleAuction(p)); assertEquals(100,b.balance(buyer)); assertEquals(original,Files.readString(b.file()));
        assertEquals(AuctionPayment.Status.UNAVAILABLE,b.auctionPaymentStatus(p));
        b.saveNow(); assertEquals(original,Files.readString(b.file())); assertEquals(AuctionPayment.Status.MISSING,load().auctionPaymentStatus(p));
    }
    @Test void ordinaryAsyncSaveKeepsReceiptsAndOtherMigrationFields() throws Exception {
        var b=actors(); assertTrue(b.importLegacyShop(buyer,7)); assertTrue(b.importLegacyClaims(seller,"Seller",9));
        var p=payment(1,0,100,0); assertTrue(b.settleAuction(p)); b.deposit(buyer,"Buyer",2); b.saveNow(); b=load();
        assertEquals(AuctionPayment.Status.COMMITTED,b.auctionPaymentStatus(p));
        assertTrue(b.importLegacyShop(buyer,7)); assertEquals(9,b.balance(buyer));
        assertTrue(b.importLegacyClaims(seller,"Seller",9)); assertEquals(104,b.balance(seller));
    }
    @Test void absentOrIncompleteBankNeverMeansUnpaidPreparedTransaction() throws Exception {
        assertEquals(AuctionPayment.Status.CONFLICT,load().auctionPaymentStatus(payment(1,0,100,0)));
        var b=actors(); b.saveNow(); var json=com.google.gson.JsonParser.parseString(Files.readString(b.file())).getAsJsonObject();
        json.remove("auctionImports"); Files.writeString(b.file(),json.toString());
        assertFalse(load().available());
    }
    @Test void malformedPaymentIsRejected() {
        var b=actors(); var good=payment(1,0,100,0);
        assertTrue(good.valid());
        var bad=new AuctionPayment(good.transaction(),market,1,1,buyer,buyer,100,5,0,100,0,good.inventoryHash());
        assertFalse(bad.valid()); assertFalse(b.settleAuction(bad));
        assertNotEquals(AuctionPayment.hash("a","b"),AuctionPayment.hash("ab",""));
    }
    @Test void exceptionAfterRenameIsUnknownNotMissingAndReloadFindsCommittedReceipt() throws Exception {
        var b=actors(); b.deposit(buyer,"Buyer",100); b.saveNow(); var p=payment(1,100,0,0);
        var ambiguous=new BankStore(dir,Logger.getAnonymousLogger(),()->{throw new java.io.IOException("after rename");}); ambiguous.load();
        assertFalse(ambiguous.settleAuction(p)); assertEquals(AuctionPayment.Status.UNAVAILABLE,ambiguous.auctionPaymentStatus(p));
        assertEquals(100,ambiguous.balance(buyer)); assertEquals(-1,ambiguous.deposit(buyer,"Buyer",1)); assertFalse(ambiguous.withdraw(buyer,1));
        assertEquals(AuctionPayment.Status.COMMITTED,load().auctionPaymentStatus(p));
        assertEquals(0,load().balance(buyer)); assertEquals(95,load().balance(seller));
        ambiguous.saveNow(); assertEquals(AuctionPayment.Status.COMMITTED,load().auctionPaymentStatus(p));
    }

}
