package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Контракт новой витрины: навигация не становится второй экономикой. */
class ShopMarketGuiTest {
    @Test void oldShopIsReplacedByHomeAndMinorCatalog() throws Exception {
        Path root=Path.of("src/main/java/ru/cherdakmd/diamondshop");
        assertFalse(Files.exists(root.resolve("ShopGui.java")));
        String gui=Files.readString(root.resolve("ShopMarketGui.java"));
        assertTrue(gui.contains("market.home.title"));
        assertTrue(gui.contains("HOME_SELL_ALL"));
        assertTrue(gui.contains("ShopMoney.text(offer.ask())"));
        assertTrue(gui.contains("ShopMoney.text(offer.bid())"));
        assertFalse(gui.contains("TradeAmounts.quote"));
        assertFalse(gui.contains("scaleMinor"));
        assertFalse(gui.contains("store.prepare"));
        assertFalse(gui.contains("commitShopExchange"));
    }
    @Test void stalePricesAndClicksRemainFailClosed() throws Exception {
        String gui=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopMarketGui.java"));
        for(String guard:new String[]{"holder.service!=service","offer.ask()!=service.askPrice(item)","offer.bid()!=service.bidPrice(item)",
                "event.setCancelled(true)","getTopInventory()==expected","plugin.quantities().open"}) assertTrue(gui.contains(guard),guard);
        String plugin=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/DiamondShopPlugin.java"));
        assertTrue(plugin.contains("new ShopMarketGui(this)"));
        assertFalse(plugin.contains("new ShopGui(this)"));
    }
}
