package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BankPreviewTest {
    @TempDir Path dir;
    private BankStore bank() {var s=new BankStore(dir,Logger.getAnonymousLogger());s.load();return s;}
    @Test void previewMatchesActualWholeFloorTaxWithoutChangingAccountsOrSnapshot() {
        var s=bank();var from=UUID.randomUUID();var to=UUID.randomUUID();s.deposit(from,"From",1000);
        for(long amount:new long[]{1,49,50,99,100,1000}) for(int percent:new int[]{-1,0,2,50,51}) {
            String before=s.snapshotJson();int count=s.accountCount();
            var q=s.previewTransfer(from,to,amount,percent);
            assertEquals(BankStore.Status.OK,q.status());assertEquals(amount*Math.clamp(percent,0,50)/100,q.tax());
            assertEquals(amount-q.tax(),q.credited());assertEquals(before,s.snapshotJson());assertEquals(count,s.accountCount());
        }
        var quote=s.previewTransfer(from,to,100,2);
        var actual=s.transfer(from,"From",to,"To",100,2);
        assertEquals(quote,actual);assertEquals(2,actual.tax());assertEquals(98,actual.credited());
        var stats=s.personalStats(from);assertEquals(100,stats.sent());assertEquals(900,stats.balance());
        var h=s.health();assertEquals(2,h.accounts());assertEquals(998,h.total());assertEquals(998,h.topTen());
    }
    @Test void invalidPreviewAndUnknownStatisticsAreReadOnly() {
        var s=bank();var from=UUID.randomUUID();var to=UUID.randomUUID();String before=s.snapshotJson();
        assertEquals(BankStore.Status.UNKNOWN_PLAYER,s.previewTransfer(from,null,1,2).status());
        assertEquals(BankStore.Status.SELF_TRANSFER,s.previewTransfer(from,from,1,2).status());
        assertEquals(BankStore.Status.INVALID_AMOUNT,s.previewTransfer(from,to,0,2).status());
        assertEquals(BankStore.Status.INVALID_AMOUNT,s.previewTransfer(from,to,Long.MAX_VALUE,2).status());
        assertEquals(BankStore.Status.NOT_ENOUGH,s.previewTransfer(from,to,1,2).status());
        assertEquals(0,s.personalStats(from).balance());assertEquals(0,s.health().total());assertEquals(before,s.snapshotJson());
    }
    @Test void previewDoesNotReserveFundsOrOverrideRecipientCap() {
        var s=bank();var from=UUID.randomUUID();var to=UUID.randomUUID();s.deposit(from,"From",100);
        assertEquals(BankStore.Status.OK,s.previewTransfer(from,to,100,2).status());assertTrue(s.withdraw(from,100));
        assertEquals(BankStore.Status.NOT_ENOUGH,s.transfer(from,"From",to,"To",100,2).status());
        s.deposit(from,"From",100);s.deposit(to,"To",BankStore.MAX_BALANCE);
        String before=s.snapshotJson();assertEquals(BankStore.Status.LIMIT_EXCEEDED,s.previewTransfer(from,to,100,2).status());assertEquals(before,s.snapshotJson());
    }
    @Test void monetaryParserDoesNotRoundFractionsClampOverflowOrAcceptScientificNotation() {
        assertEquals(1500,BankService.parseAmount("1.5k"));assertEquals(1000,BankService.parseAmount("1 000"));
        assertEquals(BankStore.MAX_BALANCE,BankService.parseAmount("1000m"));
        for(String invalid:new String[]{"0.5","1.0011k","1001m","1e9","NaN","Infinity","99999999999999999999999999","1e-1000000000"})
            assertEquals(-1,BankService.parseAmount(invalid),invalid);
    }
}
