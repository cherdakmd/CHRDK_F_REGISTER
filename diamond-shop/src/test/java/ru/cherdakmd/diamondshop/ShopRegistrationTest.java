package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShopRegistrationTest {
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
