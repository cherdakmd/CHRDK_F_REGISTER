package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class ShopBankExchangeTest {
    @TempDir Path dir;
    BankStore store() { return new BankStore(dir, Logger.getLogger("ShopBankExchangeTest")); }
    @Test void migrationAndReceiptSurviveRestartAndReplayWithoutDoubleCredit() {
        var s=store(); var id=UUID.randomUUID(); s.deposit(id,"Alex",7);
        assertTrue(s.importLegacyShop(id,13)); assertEquals(20,s.balance(id));
        assertTrue(s.importLegacyShop(id,13)); assertEquals(20,s.balance(id));
        assertTrue(s.withdraw(id,10)); s.saveNow();
        var reloaded=store(); reloaded.load(); assertTrue(reloaded.importLegacyShop(id,13));
        assertEquals(10,reloaded.balance(id)); assertTrue(reloaded.account(id).legacyShopImported);
        assertEquals(13,reloaded.account(id).legacyShopAmount); assertEquals(13,reloaded.account(id).copy().legacyShopAmount);
        assertEquals("Alex",reloaded.account(id).name);
    }
    @Test void capBlocksWholeImportAndCanBeRetriedWithoutDiscardingAnyRemainder() {
        var s=store(); var id=UUID.randomUUID(); s.deposit(id,"Alex",BankStore.MAX_BALANCE-2);
        assertFalse(s.importLegacyShop(id,3)); assertFalse(s.account(id).legacyShopImported);
        s.withdraw(id,1); assertTrue(s.importLegacyShop(id,3)); assertEquals(BankStore.MAX_BALANCE,s.balance(id));
        assertFalse(s.importLegacyShop(null,1)); assertFalse(s.importLegacyShop(UUID.randomUUID(),-1));
        assertFalse(s.importLegacyShop(UUID.randomUUID(),BankStore.MAX_BALANCE+1));
    }
    @Test void inventoryFailureOrInvalidMoneyNeverChangesBalance() {
        var s=store(); var id=UUID.randomUUID(); s.deposit(id,"Alex",7); var calls=new AtomicInteger();
        assertFalse(s.exchange(id,"Alex",8,0,calls::incrementAndGet)); assertEquals(0,calls.get());
        assertFalse(s.exchange(id,"Alex",0,BankStore.MAX_BALANCE,calls::incrementAndGet)); assertEquals(0,calls.get());
        assertThrows(IllegalStateException.class,()->s.exchange(id,"Alex",7,0,()->{throw new IllegalStateException("inventory rejected");}));
        assertEquals(7,s.balance(id)); assertFalse(s.exchange(id,"Alex",-1,0,calls::incrementAndGet));
        assertTrue(s.exchange(id,"Alex",7,6,calls::incrementAndGet)); assertEquals(6,s.balance(id)); assertEquals(1,calls.get());
    }
    @Test void corruptBankCannotBeUsedForNewImportOrExchangeAndIsNotOverwritten() throws Exception {
        Files.writeString(dir.resolve("bank.json"), "broken-json"); var s=store(); s.load();
        assertFalse(s.available()); assertFalse(s.importLegacyShop(UUID.randomUUID(),1));
        assertFalse(s.exchange(UUID.randomUUID(),"Alex",0,1,()->fail("must not commit")));
        s.saveNow(); assertEquals("broken-json",Files.readString(dir.resolve("bank.json")));
    }
    @Test void apiStorageSnapshotAndLifecycleGuardsAreWired() throws Exception {
        var s=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        for(var guard:List.of("isPrimaryThread()", "expected.length != 36", "replacement.length != 36", "Arrays.equals(expected", "GameMode.SURVIVAL", "!isAuthenticated(player)", "setStorageContents(original)", "bank.exchange(")) assertTrue(s.contains(guard),guard);
    }
}
