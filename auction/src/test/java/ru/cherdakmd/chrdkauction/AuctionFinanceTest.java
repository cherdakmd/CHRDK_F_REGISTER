package ru.cherdakmd.chrdkauction;

import ru.cherdakmd.chrdkregister.api.AuctionTender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.BankStore;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class AuctionFinanceTest {
    @TempDir Path dir;
    final UUID seller=UUID.randomUUID(), buyer=UUID.randomUUID();
    AuctionStore market() throws Exception { var s=new AuctionStore(dir.resolve("auction"),Logger.getAnonymousLogger()); s.load(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    long list(AuctionStore s) throws Exception {
        long id=s.prepareListing(seller,"Seller",Base64.getEncoder().encodeToString(new byte[]{1,2}),100,5,2000,1000,10,500,"listing-before","listing-after");
        s.complete(); return id;
    }
    AuctionPayment intent(AuctionStore s, long id, boolean cash) {
        return new AuctionPayment(UUID.randomUUID(),s.market(),s.nextBankSequence(),id,buyer,seller,100,5,cash?0:100,cash?108:0,cash?8:0,
                AuctionPayment.hash(cash?"before":"same",cash?"after":"same"));
    }
    @Test void bankOnlyEqualSnapshotsRemainPendingOnRestartAndCannotUseLegacyComplete() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); UUID market=s.market(); var p=intent(s,id,false);
        s.prepareBankBuy(p,1500,"same","same"); s=market(); assertEquals(p,s.pending().payment()); assertEquals(market,s.market());
        var recovered=s; assertThrows(IllegalStateException.class,recovered::complete); assertThrows(IllegalStateException.class,recovered::rollback);
        s.completeBank(p); s=market(); assertNull(s.lot(id)); assertEquals(1,s.mail(buyer).size()); assertEquals(0,s.balance(seller));
        assertEquals(2,s.nextBankSequence()); assertEquals(market,s.market());
    }
    @Test void missingPaymentRollsBackWithoutAdvancingCounterAndRetryGetsNewIdentity() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var first=intent(s,id,false); s.prepareBankBuy(first,1500,"same","same");
        s=market(); s.rollbackBank(first); assertEquals(1,s.nextBankSequence()); assertNotNull(s.lot(id));
        var next=intent(s,id,false); assertNotEquals(first.transaction(),next.transaction()); s.prepareBankBuy(next,1500,"same","same"); s.completeBank(next);
        assertEquals(1,market().mail(buyer).size());
    }
    @Test void oldPendingBuyFinishesBeforeSchemaUpgradeAndLegacyProceedsAreFrozen() throws Exception {
        var s=market(); long id=list(s); s.prepareBuy(buyer,id,1500,"cash-before","cash-after"); s=market();
        assertFalse(s.bankMode()); var legacy=s; assertThrows(IllegalStateException.class,legacy::enableBank);
        s.complete(); s.enableBank(); assertEquals(95,s.balance(seller)); assertEquals(95,market().legacyMoney().get(seller));
        var frozen=s; assertThrows(IllegalStateException.class,()->frozen.prepareMoneyClaim(seller,95,"a","b"));
        var b=bank(); assertTrue(b.importLegacyAuction(s.market(),seller,95)); assertEquals(95,bank().balance(seller));
        assertTrue(bank().importLegacyAuction(s.market(),seller,95)); assertEquals(95,bank().balance(seller));
    }
    @Test void bankCommitThenCrashBeforeMarketCompleteRecoversOnceFromRealReceipt() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var b=bank();
        assertTrue(b.importLegacyAuction(s.market(),buyer,100)); assertTrue(b.importLegacyAuction(s.market(),seller,0));
        var p=intent(s,id,false); s.prepareBankBuy(p,1500,"same","same"); assertTrue(b.settleAuction(p));
        s=market(); b=bank(); assertEquals(AuctionRecovery.Decision.COMPLETE,AuctionRecovery.decide(b.auctionPaymentStatus(p),0,false,false));
        s.completeBank(p); assertEquals(1,market().mail(buyer).size()); assertEquals(0,bank().balance(buyer)); assertEquals(95,bank().balance(seller));
        assertTrue(bank().settleAuction(p)); assertEquals(95,bank().balance(seller));
    }
    @Test void crashAfterPrepareBeforeBankWriteRollsBackUsingMissingReceipt() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var b=bank();
        b.importLegacyAuction(s.market(),buyer,100); b.importLegacyAuction(s.market(),seller,0);
        var p=intent(s,id,false); s.prepareBankBuy(p,1500,"same","same"); s=market();
        assertEquals(AuctionRecovery.Decision.ROLLBACK,AuctionRecovery.decide(bank().auctionPaymentStatus(p),0,false,false));
        s.rollbackBank(p); assertNotNull(market().lot(id)); assertTrue(market().mail(buyer).isEmpty()); assertEquals(100,bank().balance(buyer));
    }
    @Test void ambiguousMarketWriteQuarantinesUntilRestartAndKeepsPending() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var p=intent(s,id,false); s.prepareBankBuy(p,1500,"same","same");
        Files.createDirectory(dir.resolve("auction/market.json.tmp")); var broken=s;
        assertThrows(java.io.IOException.class,()->broken.completeBank(p)); assertFalse(s.healthy()); assertNotNull(market().pending());
        assertThrows(IllegalStateException.class,()->broken.rollbackBank(p)); assertThrows(IllegalStateException.class,()->broken.expire(3000));
    }
    @Test void strippedOrTamperedPaymentAndMarketCounterAreRejected() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var p=intent(s,id,false); s.prepareBankBuy(p,1500,"same","same");
        Path file=dir.resolve("auction/market.json"); String original=Files.readString(file);
        for (String change:List.of("payment","market","bankSequence")) {
            var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject();
            if (change.equals("payment")) json.getAsJsonObject("pending").remove(change);
            else if (change.equals("market")) json.remove(change); else json.getAsJsonObject("state").remove(change);
            Files.writeString(file,json.toString()); assertThrows(java.io.IOException.class,this::market);
        }
        var tampered=com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        tampered.getAsJsonObject("pending").getAsJsonObject("payment").addProperty("fee",6);
        Files.writeString(file,tampered.toString()); assertThrows(java.io.IOException.class,this::market);
        Files.writeString(file,original); assertNotNull(market().pending().payment());
    }
    @Test void bankCashSnapshotRulesAndLotIdentityCannotBeWeakened() throws Exception {
        var s=market(); long id=list(s); s.enableBank(); var bankOnly=intent(s,id,false); var target=s;
        assertThrows(IllegalArgumentException.class,()->target.prepareBankBuy(bankOnly,1500,"before","after"));
        var cash=intent(s,id,true); assertThrows(IllegalArgumentException.class,()->target.prepareBankBuy(cash,1500,"same","same"));
        assertNull(market().pending());
    }
    @Test void tenderBankFirstThenSmallDenominationsAndBlockChange() {
        int[] n=new int[36],v=new int[36]; n[0]=3;v[0]=9;n[5]=2;v[5]=1;
        var bankOnly=AuctionTender.plan(20,20,n,v); assertEquals(20,bankOnly.debit()); assertEquals(0,bankOnly.cash()); assertArrayEquals(n,bankOnly.remaining());
        var mixed=AuctionTender.plan(20,10,n,v); assertEquals(10,mixed.debit()); assertEquals(11,mixed.cash()); assertEquals(1,mixed.change());
        assertEquals(0,mixed.remaining()[5]); assertEquals(2,mixed.remaining()[0]); assertEquals(3,n[0]);
        assertNull(AuctionTender.plan(100,0,n,v));
        assertNull(AuctionTender.plan(20,0,new int[37],new int[37]));
    }
    @Test void recoveryMatrixNeverGuessesPaymentFromEqualOrUnrelatedInventory() {
        for (boolean before:List.of(false,true)) for (boolean after:List.of(false,true)) {
            assertEquals(AuctionRecovery.Decision.COMPLETE,AuctionRecovery.decide(AuctionPayment.Status.COMMITTED,0,before,after));
            assertEquals(AuctionRecovery.Decision.ROLLBACK,AuctionRecovery.decide(AuctionPayment.Status.MISSING,0,before,after));
            assertEquals(AuctionRecovery.Decision.WAIT,AuctionRecovery.decide(AuctionPayment.Status.UNAVAILABLE,9,before,after));
            assertEquals(AuctionRecovery.Decision.CONFLICT,AuctionRecovery.decide(AuctionPayment.Status.CONFLICT,9,before,after));
        }
        assertEquals(AuctionRecovery.Decision.APPLY_AFTER,AuctionRecovery.decide(AuctionPayment.Status.COMMITTED,108,true,false));
        assertEquals(AuctionRecovery.Decision.COMPLETE,AuctionRecovery.decide(AuctionPayment.Status.COMMITTED,108,false,true));
        assertEquals(AuctionRecovery.Decision.CONFLICT,AuctionRecovery.decide(AuctionPayment.Status.COMMITTED,108,false,false));
        assertEquals(AuctionRecovery.Decision.CONFLICT,AuctionRecovery.decide(AuctionPayment.Status.MISSING,108,false,true));
    }
    @Test void productionPurchaseOrderingAndNoSecondSpendableWallet() throws Exception {
        String src=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkauction/AuctionService.java"));
        String buy=src.substring(src.indexOf("void buy("),src.indexOf("void cancel("));
        assertTrue(buy.indexOf("prepareBankBuy")<buy.indexOf("commitAuctionPayment"));
        assertTrue(buy.indexOf("commitAuctionPayment")<buy.indexOf("setStorageContents"));
        assertTrue(buy.indexOf("saveData()")<buy.indexOf("completeBank"));
        assertTrue(buy.contains("if (plan.cash() > 0)")); assertFalse(buy.contains("prepareBuy("));
        String money=src.substring(src.indexOf("void money("),src.indexOf("private void lockPlayer"));
        assertFalse(money.contains("prepareMoneyClaim")); assertFalse(money.contains("setStorageContents")); assertTrue(money.contains("bankBalance"));
    }
}
