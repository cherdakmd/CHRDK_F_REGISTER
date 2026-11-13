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
                "event.setCancelled(true)","sameInventory(player,expected)","plugin.quantities().open"}) assertTrue(gui.contains(guard),guard);
        assertTrue(gui.contains("expected.getHolder()!=null&&current.getHolder()==expected.getHolder()"));
        assertFalse(gui.contains("getTopInventory()==expected"));
        String plugin=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/DiamondShopPlugin.java"));
        assertTrue(plugin.contains("new ShopMarketGui(this)"));
        assertFalse(plugin.contains("new ShopGui(this)"));
    }

    @Test void homeSectionsUseHolderSafeSectionNavigationWithoutBooks() throws Exception {
        String gui=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopMarketGui.java"));
        assertTrue(gui.contains("HOME_SECTIONS = {10,11,12,13,14,15,16}"));
        assertTrue(gui.contains("if(slot==HOME_SECTIONS[i]){reopen(player,sections.get(i),0);return;}"));
        // Книги чар сняты с продажи (2.0.5): их разделов в каталоге нет.
        for (ShopCategory category : ShopCategory.values()) {
            assertFalse(category.id().startsWith("books-"), category.id());
        }
        assertEquals("misc", ShopCategory.values()[ShopCategory.values().length - 1].id());
    }
}
