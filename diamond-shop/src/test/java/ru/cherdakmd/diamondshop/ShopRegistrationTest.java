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
