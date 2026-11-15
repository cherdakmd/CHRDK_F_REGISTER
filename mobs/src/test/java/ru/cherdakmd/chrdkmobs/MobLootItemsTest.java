package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лут мобов: эссенции навыков и тематические сущности вариантов.
 */
class MobLootItemsTest {

    @Test
    void kindsAreFoundById() {
        assertEquals(MobLootItems.Kind.ESSENCE_COMBAT, MobLootItems.Kind.byId("essence-combat"));
        assertEquals(MobLootItems.Kind.VARIANT_CRINGE, MobLootItems.Kind.byId("variant-cringe"));
        assertNull(MobLootItems.Kind.byId("essence-unknown"));
        assertNull(MobLootItems.Kind.byId(null));
    }

    @Test
    void variantsMapToTheirLoot() {
        for (MobVariant variant : MobVariant.values()) {
            MobLootItems.Kind kind = MobLootItems.forVariant(variant);
            assertNotNull(kind, variant + " имеет тематический лут");
            assertEquals(variant.lootId(), kind.id);
            assertTrue(kind.variant(), "лут варианта — сущность с эффектом");
        }
    }

    @Test
    void essencesArePlainXpMaterials() {
        for (MobLootItems.Kind kind : new MobLootItems.Kind[]{
                MobLootItems.Kind.ESSENCE_COMBAT, MobLootItems.Kind.ESSENCE_GATHER,
                MobLootItems.Kind.ESSENCE_CRAFT}) {
            assertTrue(!kind.variant(), kind.id + " — обычная эссенция");
            assertTrue(kind.skills.size() >= 2, kind.id + " покрывает группу навыков");
        }
    }

    @Test
    void dropCountsStayInRange() {
        assertEquals(3, MobLootItems.count(3, 3));
        for (int attempt = 0; attempt < 64; attempt++) {
            int count = MobLootItems.count(2, 4);
            assertTrue(count >= 2 && count <= 4, "количество в диапазоне тира");
            assertTrue(MobLootItems.count(5, 1) >= 1, "перепутанные границы нормализуются");
        }
    }
}
