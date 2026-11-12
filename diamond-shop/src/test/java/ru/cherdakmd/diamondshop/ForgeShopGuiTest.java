package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;
import static org.junit.jupiter.api.Assertions.*;

class ForgeShopGuiTest {
    @Test void rendersAndAcceptsClicksForAllForgeConsumablesAndSkillPotions() {
        assertEquals(18, ForgeMarketCatalog.SKUS.size());
        assertEquals(10, ForgeMarketCatalog.SKUS.stream().filter(s -> s.id().startsWith("skill_xp_")).count());
        int first = ShopLayout.FIRST_ITEM_SLOT + 9;
        for (int index = 0; index < ForgeMarketCatalog.SKUS.size(); index++) {
            assertEquals(index, ForgeShopGui.offerIndex(first + index, 18));
        }
        // Десятое зелье находится в последнем товарном слоте второго ряда.
        assertEquals(17, ForgeShopGui.offerIndex(44, 18));
        assertEquals("skill_xp_enchanting", ForgeMarketCatalog.SKUS.get(17).id());
    }

    @Test void navigationAndOutOfCatalogSlotsNeverSelectAnotherOffer() {
        int first = ShopLayout.FIRST_ITEM_SLOT + 9;
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_CLOSE, 18));
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_FORGE, 18));
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_BALANCE, 18));
        assertEquals(-1, ForgeShopGui.offerIndex(first - 1, 18));
        assertEquals(-1, ForgeShopGui.offerIndex(first + 18, 18));
        assertEquals(-1, ForgeShopGui.offerIndex(first + 17, 17));
        assertEquals(-1, ForgeShopGui.offerIndex(first, 0));
    }
}
