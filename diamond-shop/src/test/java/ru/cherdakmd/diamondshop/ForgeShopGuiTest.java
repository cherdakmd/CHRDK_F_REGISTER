package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;
import static org.junit.jupiter.api.Assertions.*;

class ForgeShopGuiTest {
    @Test void rendersOnlyEightForgeConsumablesAndNoSkillPotions() {
        var visible = ForgeMarketCatalog.SKUS.stream()
                .filter(s -> !ForgeShopService.isPotionSku(s.id())).toList();
        assertEquals(8, visible.size());
        // skill_xp_* — только валидация старых грантов в whitelist; витрина
        // их не показывает, покупка зелий живёт в меню /skills.
        assertEquals(10, ForgeMarketCatalog.SKUS.stream()
                .filter(s -> ForgeShopService.isPotionSku(s.id())).count());
        int first = ShopLayout.FIRST_ITEM_SLOT + 9;
        for (int index = 0; index < visible.size(); index++) {
            assertEquals(index, ForgeShopGui.offerIndex(first + index, visible.size()));
        }
        assertEquals("name", visible.get(7).id());
        assertEquals(-1, ForgeShopGui.offerIndex(first + 8, visible.size()));
    }

    @Test void navigationAndOutOfCatalogSlotsNeverSelectAnotherOffer() {
        int first = ShopLayout.FIRST_ITEM_SLOT + 9;
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_CLOSE, 8));
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_FORGE, 8));
        assertEquals(-1, ForgeShopGui.offerIndex(ShopLayout.SLOT_BALANCE, 8));
        assertEquals(-1, ForgeShopGui.offerIndex(first - 1, 8));
        assertEquals(-1, ForgeShopGui.offerIndex(first + 8, 8));
        assertEquals(-1, ForgeShopGui.offerIndex(first + 7, 7));
        assertEquals(-1, ForgeShopGui.offerIndex(first, 0));
    }

    @Test void potionMenuCommandsPricesAndTextsAreRemoved() throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/SkillPotionShopGui.java")));
        try (var paths = Files.walk(Path.of("src/main"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(path);
                assertFalse(text.contains("potionShopGui"), path.toString());
                assertFalse(text.contains("potions.shop"), path.toString());
                if (!path.toString().endsWith("ForgeShopService.java")) {
                    assertFalse(text.contains("skill_xp_"), path.toString());
                }
            }
        }
        String service = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/ForgeShopService.java"));
        assertTrue(service.contains("if (isPotionSku(sku.id())) continue;"));
        assertTrue(service.contains("if (isPotionSku(offer.sku())) return fail"));
    }
}
