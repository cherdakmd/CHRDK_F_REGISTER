package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Аукцион снят как продукт; общий банк и его legacy-schema остаются совместимыми. */
class AuctionRemovalTest {
    @Test void moduleCommandsPermissionsAndUpdateTransportAreGone() throws Exception {
        assertFalse(Files.exists(Path.of("auction")));
        assertFalse(Files.exists(Path.of("wiki/auction.md")));
        assertFalse(Files.readString(Path.of("settings.gradle")).contains("include 'auction'"));
        assertEquals(19,PluginUpdateService.MODULES.size());
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Auction"));
        for(String command:new String[]{"ah","auction","auctionadmin","chrdkauction:ah"})assertTrue(RetiredAddons.command(command),command);
        assertTrue(RetiredAddons.plugin("CHRDK_Auction"));
        assertTrue(RetiredAddons.permission("chrdkauction.use"));
        String bootstrap=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/LuckPermsBootstrap.java"));
        assertFalse(bootstrap.contains("chrdkauction."));
        String api=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChrdkRegisterApi.java"));
        assertFalse(api.contains("commitAuctionPayment"));
        assertFalse(api.contains("importLegacyAuction"));
    }
    @Test void menusAndMessagesDoNotAdvertiseRemovedAuction() throws Exception {
        String config=Files.readString(Path.of("src/main/resources/config.yml"));
        assertFalse(config.contains("CHRDK_Auction"));
        assertFalse(config.contains("chrdkauction."));
        assertFalse(config.contains("/auction"));
        String messages=Files.readString(Path.of("src/main/resources/messages.yml"));
        assertFalse(messages.contains("Аукцион: /ah"));
    }
}
