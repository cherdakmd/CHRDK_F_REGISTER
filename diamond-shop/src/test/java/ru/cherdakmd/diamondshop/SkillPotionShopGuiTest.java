package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;
import static org.junit.jupiter.api.Assertions.*;

class SkillPotionShopGuiTest {
    @Test void separateMenuHasExactlyTenPotionSlots() {
        var potions = ForgeMarketCatalog.SKUS.stream().filter(s -> ForgeShopService.isPotionSku(s.id())).toList();
        assertEquals(10, potions.size());
        for (int index = 0; index < potions.size(); index++) {
            assertEquals(index, SkillPotionShopGui.offerIndex(SkillPotionShopGui.FIRST_OFFER_SLOT + index, 10));
        }
        assertEquals(9, SkillPotionShopGui.offerIndex(36, 10));
        assertEquals("skill_xp_enchanting", potions.get(9).id());
    }

    @Test void forgeItemsAndNavigationCannotLeakIntoPotionMenu() {
        assertFalse(ForgeShopService.isPotionSku("ascend"));
        assertTrue(ForgeShopService.isPotionSku("skill_xp_mining"));
        assertFalse(ForgeShopService.isPotionSku(null));
        assertEquals(-1, SkillPotionShopGui.offerIndex(37, 10));
        assertEquals(-1, SkillPotionShopGui.offerIndex(ShopLayout.SLOT_PREV, 10));
        assertEquals(-1, SkillPotionShopGui.offerIndex(ShopLayout.SLOT_FORGE, 10));
        assertEquals(-1, SkillPotionShopGui.offerIndex(27, 0));
    }
}
