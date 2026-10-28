package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimsBankTest {
    @TempDir Path dir;
    BankStore bank() { var b = new BankStore(dir, Logger.getAnonymousLogger()); b.load(); return b; }
    @Test void oncePerNicknameAcrossRestartAndDifferentUuid() throws Exception {
        var b = bank(); var id = UUID.randomUUID();
        assertTrue(b.importLegacyClaims(id,"Steve",37));
        assertTrue(b.importLegacyClaims(id,"STEVE",37)); assertEquals(37,b.balance(id));
        b.saveNow(); assertTrue(Files.readString(dir.resolve("bank.json")).contains("claimsImports"));
        b = bank(); assertTrue(b.importLegacyClaims(id,"steve",37)); assertEquals(37,b.balance(id));
        assertFalse(b.importLegacyClaims(UUID.randomUUID(),"Steve",37));
        assertTrue(b.importLegacyClaims(id,"steve",99)); assertEquals(37,b.balance(id),"изменённый старый файл не создаёт второй импорт");
    }
    @Test void capPostponesImportWithoutReceiptOrLoss() {
        var b = bank(); var id = UUID.randomUUID(); b.deposit(id,"Alex",BankStore.MAX_BALANCE-1);
        assertFalse(b.importLegacyClaims(id,"Alex",10)); assertEquals(BankStore.MAX_BALANCE-1,b.balance(id));
        assertTrue(b.withdraw(id,20)); assertTrue(b.importLegacyClaims(id,"Alex",10));
        assertEquals(BankStore.MAX_BALANCE-11,b.balance(id));
        assertFalse(b.importLegacyClaims(UUID.randomUUID(),"Huge",BankStore.MAX_BALANCE+1));
        assertFalse(b.importLegacyClaims(id,"Bad",-1));
    }
    @Test void sellerCapChecksBeforeBuyerDebitAndInventoryCommit() {
        var b=bank(); var buyer=UUID.randomUUID(); var seller=UUID.randomUUID();
        b.deposit(buyer,"Buyer",50); b.deposit(seller,"Seller",BankStore.MAX_BALANCE);
        var committed=new AtomicBoolean();
        assertFalse(b.exchangeAccounts(Map.of(buyer,-10L,seller,9L),()->committed.set(true)));
        assertFalse(committed.get()); assertEquals(50,b.balance(buyer)); assertEquals(BankStore.MAX_BALANCE,b.balance(seller));
        b.withdraw(seller,20);
        assertTrue(b.exchangeAccounts(Map.of(buyer,-10L,seller,9L),()->committed.set(true)));
        assertTrue(committed.get()); assertEquals(40,b.balance(buyer)); assertEquals(BankStore.MAX_BALANCE-11,b.balance(seller));
    }
    @Test void inventoryExceptionAndInvalidDeltaDoNotTouchAccounts() {
        var b=bank(); var id=UUID.randomUUID(); b.deposit(id,"Buyer",40);
        assertThrows(IllegalStateException.class,()->b.exchangeAccounts(Map.of(id,-10L),()->{throw new IllegalStateException("storage");}));
        assertEquals(40,b.balance(id));
        assertFalse(b.exchangeAccounts(Map.of(id,Long.MAX_VALUE),()->fail("не вызывать")));
        assertFalse(b.exchangeAccounts(Map.of(id,-41L),()->fail("не вызывать")));
    }
    @Test void brokenGlobalReceiptDisablesBankInsteadOfRepeatingImport() throws Exception {
        Files.writeString(dir.resolve("bank.json"),"{\"accounts\":{},\"claimsImports\":{\"Steve\":{\"player\":\""+UUID.randomUUID()+"\",\"amount\":10}}}");
        var b=bank(); assertFalse(b.available()); assertFalse(b.importLegacyClaims(UUID.randomUUID(),"Steve",10));
    }
    @Test void corruptBalanceAndMissingAccountsDoNotBecomeRealZeroForAutomaticRent() throws Exception {
        for(String json : List.of("{}", "{\"accounts\":{\"not-a-uuid\":{\"balance\":40}}}",
                "{\"accounts\":{\""+UUID.randomUUID()+"\":{\"balance\":-1}}}",
                "{\"accounts\":{\""+UUID.randomUUID()+"\":{\"balance\":1000000001}}}")) {
            Files.writeString(dir.resolve("bank.json"),json);
            var b=bank(); assertFalse(b.available()); b.saveNow();
            assertEquals(json,Files.readString(dir.resolve("bank.json")));
        }
    }

}
