package ru.cherdakmd.chrdklottery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class LotteryBankTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    LotteryStore source() throws Exception { var s=new LotteryStore(dir,Logger.getAnonymousLogger()); s.load(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    @Test void migrateOnlyUnpaidPrizeNotPotOrHistoryAndFutureRoundsAddOnlyDelta() throws Exception {
        var s=source(); s.purchase(owner,"Alex",1,100); s.draw(100,80,new Random()); s.debitPrize(owner,30);
        s.purchase(owner,"Alex",1,10); s.enableBank(); var id=s.incomeId(); assertEquals(50,s.entitlementOf(owner)); assertEquals(10,s.pot());
        var b=bank(); assertTrue(b.registerIncomeSource("lottery",id)); assertTrue(b.creditEntitlement("lottery",id,owner.toString(),owner,50));
        assertEquals(50,bank().balance(owner)); var locked=s; assertThrows(IllegalStateException.class,()->locked.debitPrize(owner,1));
        s.draw(200,80,new Random()); assertEquals(58,source().entitlementOf(owner));
        assertTrue(bank().creditEntitlement("lottery",id,owner.toString(),owner,source().entitlementOf(owner))); assertEquals(58,bank().balance(owner));
        assertTrue(bank().creditEntitlement("lottery",id,owner.toString(),owner,source().entitlementOf(owner))); assertEquals(58,bank().balance(owner));
    }
    @Test void capRetainsPrizeAndDoesNotRepeatDrawOrConsumePotTwice() throws Exception {
        var s=source(); s.enableBank(); s.purchase(owner,"Alex",1,100); s.draw(100,80,new Random());
        var b=bank(); b.registerIncomeSource("lottery",s.incomeId()); b.deposit(owner,"Alex",BankStore.MAX_BALANCE); b.saveNow();
        assertFalse(b.creditEntitlement("lottery",s.incomeId(),owner.toString(),owner,80)); assertEquals(80,source().entitlementOf(owner));
        assertEquals(0,source().pot()); assertTrue(source().ticketsSnapshot().isEmpty());
        b.withdraw(owner,80); assertTrue(b.creditEntitlement("lottery",s.incomeId(),owner.toString(),owner,80)); assertEquals(BankStore.MAX_BALANCE,bank().balance(owner));
    }
    @Test void metadataSurvivesPurchaseDrawAndBoundaryAndMissingMarkerFailsClosed() throws Exception {
        var s=source(); s.enableBank(); var id=s.incomeId(); s.markBankRegistered(); s.purchase(owner,"Alex",1,10); s.markDrawnNow(10); s.draw(20,80,new Random());
        assertEquals(id,source().incomeId()); assertTrue(source().bankRegistered());
        Path file=dir.resolve("data.json"); var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject(); json.remove("bankRegistered");
        Files.writeString(file,json.toString()); assertThrows(java.io.IOException.class,this::source);
    }
    @Test void productionPrizeNeverDebitsSourceOrGivesInventoryItems() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdklottery/LotteryService.java"));
        String claim=source.substring(source.indexOf("public void claimPrize"),source.indexOf("private void fail("));
        assertTrue(claim.contains("creditEntitlement")); assertFalse(claim.contains("debitPrize"));
        assertFalse(claim.contains("addItem")); assertFalse(claim.contains("DiamondWallet.give"));
    }

}
