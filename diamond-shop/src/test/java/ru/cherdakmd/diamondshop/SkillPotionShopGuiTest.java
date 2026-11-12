package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test void skillTokenMustMatchExactSku() {
        assertTrue(ForgeShopService.matchingSkillToken("skill_xp_mining", "mining"));
        assertTrue(ForgeShopService.matchingSkillToken("skill_xp_enchanting", "enchanting"));
        assertFalse(ForgeShopService.matchingSkillToken("skill_xp_mining", "combat"));
        assertFalse(ForgeShopService.matchingSkillToken("ascend", "mining"));
        assertFalse(ForgeShopService.matchingSkillToken("skill_xp_mining", null));
    }

    @Test void compatibilityKeepsSkillsKeyAndAddsCoreAliasBeforeEverySnapshot() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/ForgeShopService.java"));
        String method = source.split("private static ItemStack compatiblePrototype", 2)[1]
                .split("List<Offer> forgeOffers", 2)[0];
        assertTrue(method.contains("chrdk_skills:skill_xp_v1"));
        assertTrue(method.contains("chrdkskills:skill_xp_v1"));
        assertTrue(method.contains("prototype.clone()"));
        assertTrue(method.contains("matchingSkillToken(sku, token)"));
        assertTrue(method.contains("pdc.set(coreKey, PersistentDataType.STRING, token)"));
        assertFalse(method.contains("pdc.remove(skillsKey"));
        assertTrue(source.contains("compatiblePrototype(sku.id(), source.prototype(sku.id()))"));
        assertTrue(source.contains("compatiblePrototype(offer.sku(), source.prototype(offer.sku()))"));
    }

    @Test void emptyDiagnosticNamesEveryGateWithoutCreatingPotions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/ForgeShopService.java"));
        String diagnostic = source.split("private void diagnosePotions\\(\\)", 2)[1];
        assertTrue(source.contains("if (result.isEmpty()) diagnosePotions();"));
        assertTrue(diagnostic.contains("ForgeMaterialSource providers="));
        assertTrue(diagnostic.contains("available=\" + candidate.available"));
        assertTrue(diagnostic.contains("prototype=null"));
        assertTrue(diagnostic.contains("validGrant=\" + ForgeMarketCatalog.validTradeGrant"));
        assertTrue(diagnostic.contains("исключение диагностики"));
        assertFalse(diagnostic.contains("createPotion("));
    }
}
