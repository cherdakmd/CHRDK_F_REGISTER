package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import java.util.UUID;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShopRegistrationTest {
    @TempDir Path dir;
    @Test void realBankRestoresSameMarketButNeverReplacesConflict() {
        UUID market=UUID.randomUUID();var log=Logger.getAnonymousLogger();
        var empty=new BankStore(dir.resolve("empty"),log);empty.load();
        assertTrue(ShopRegistration.canRestore(0,false));
        assertTrue(empty.registerShop(market));
        assertTrue(empty.incomeSourceAvailable("diamond-shop",market));
        assertFalse(empty.registerShop(UUID.randomUUID()));
        assertTrue(empty.incomeSourceAvailable("diamond-shop",market));
    }
    @Test void pristineLocalStoreAdoptsExistingPristineCoreMarket() throws Exception {
        var log=Logger.getAnonymousLogger();var bank=new BankStore(dir.resolve("core"),log);bank.load();
        UUID existing=UUID.randomUUID();assertTrue(bank.registerShop(existing));assertEquals(0,bank.shopSequence(existing));
        String before=bank.snapshotJson();
        var store=new ShopStore(dir.resolve("shop"),log);store.load();assertTrue(store.ensureMarket());
        UUID regenerated=store.market();assertNotEquals(existing,regenerated);
        assertTrue(ShopRegistration.canAdopt(store.sequence(),store.pending()!=null,bank.shopSequence(existing)));
        assertTrue(store.adoptMarket(existing));
        var reopened=new ShopStore(dir.resolve("shop"),log);reopened.load();
        assertEquals(existing,reopened.market());assertTrue(reopened.registered());assertEquals(0,reopened.sequence());
        assertEquals(before,bank.snapshotJson());assertFalse(bank.registerShop(regenerated));
    }
    @Test void adoptionRequiresZeroSequenceOnBothSides() {
        assertTrue(ShopRegistration.canAdopt(0,false,0));
        assertFalse(ShopRegistration.canAdopt(0,false,-1));
        assertFalse(ShopRegistration.canAdopt(0,false,1));
        assertFalse(ShopRegistration.canAdopt(1,false,0));
        assertFalse(ShopRegistration.canAdopt(0,true,0));
    }
    @Test void lostCoreLineageCanBeRestoredOnlyBeforeAnyTrade() {
        assertTrue(ShopRegistration.canRestore(0,false));
        assertFalse(ShopRegistration.canRestore(0,true));
        assertFalse(ShopRegistration.canRestore(1,false));
        assertFalse(ShopRegistration.canRestore(-1,false));
        assertFalse(ShopRegistration.canRestore(Long.MAX_VALUE,false));
    }
    @Test void rebindKeepsMarketUuidAndNeverClearsJournal() throws Exception {
        String service=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        assertTrue(service.contains("core.registerShop(store.market())&&store.markRegistered()"));
        assertFalse(service.contains("store.stop"));
        assertFalse(service.contains("cancelUnaccepted"));
        assertFalse(service.contains("pending=null"));
    }
}
