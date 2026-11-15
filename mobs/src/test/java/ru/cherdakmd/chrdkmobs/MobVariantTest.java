package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Редкие варианты мобов: проклятые, тлеющие, всратые и кринжовые —
 * своя тема способностей, своё имя и тематический лут.
 */
class MobVariantTest {

    @Test
    void everyVariantHasThemedPool() {
        assertTrue(MobVariant.CURSED.pool().contains(EliteAbilities.LIFESTEAL),
                "проклятые тянутся к жизни");
        assertTrue(MobVariant.CURSED.pool().contains(EliteAbilities.WITHER_TOUCH),
                "проклятые усушают");
        assertTrue(MobVariant.CURSED.pool().contains(EliteAbilities.EXPLOSIVE_DEATH),
                "проклятые взрываются смертью");
        assertTrue(MobVariant.SMOLDERING.pool().contains(EliteAbilities.FIRE_TOUCH),
                "тлеющие жгут");
        assertTrue(MobVariant.SMOLDERING.pool().contains(EliteAbilities.EXPLOSIVE_DEATH),
                "тлеющие взрываются смертью");
        assertTrue(MobVariant.GROSS.pool().contains(EliteAbilities.VENOM),
                "всрaтые травят ядом");
        assertTrue(MobVariant.GROSS.pool().contains(EliteAbilities.WEB_TOUCH),
                "всрaтые плетут паутину");
        assertTrue(MobVariant.CRINGE.pool().contains(EliteAbilities.BLINK),
                "кринжовые хаотичны");
        assertTrue(MobVariant.CRINGE.pool().contains(EliteAbilities.PULL),
                "кринжовые притягивают");
        assertTrue(MobVariant.CRINGE.pool().contains(EliteAbilities.LAUNCH),
                "кринжовые подбрасывают");
        assertTrue(MobVariant.CRINGE.pool().contains(EliteAbilities.LIGHTNING_TOUCH),
                "кринжовые бьют разрядом");
        for (MobVariant variant : MobVariant.values()) {
            assertTrue(variant.pool().size() >= 3, variant + ": пула хватает на набор");
        }
    }

    @Test
    void idsRoundTrip() {
        for (MobVariant variant : MobVariant.values()) {
            assertEquals(variant, MobVariant.byId(variant.id()), variant + " находится по id");
            assertEquals("variant-" + variant.id(), variant.lootId(), "лут привязан к id");
            assertEquals("variant-" + variant.id(), variant.titleKey(), "имя в messages.yml");
        }
        assertNull(MobVariant.byId("unknown"));
        assertNull(MobVariant.byId(null));
    }

    @Test
    void abilitiesPickedFromPoolOnly() {
        Random random = new Random(42);
        for (MobVariant variant : MobVariant.values()) {
            for (int attempt = 0; attempt < 32; attempt++) {
                List<EliteAbilities> picked = EliteAbilities.pickFrom(
                        variant.pool(), random, 1, 5);
                assertTrue(variant.pool().containsAll(picked),
                        variant + ": набор только из тематического пула");
                assertTrue(picked.size() <= variant.pool().size());
            }
        }
    }
}
