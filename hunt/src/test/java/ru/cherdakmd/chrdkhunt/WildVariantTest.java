package ru.cherdakmd.chrdkhunt;

import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WildVariantTest {
    @Test void catalogueHasExactlySixDistinctVanillaKinds() {
        assertEquals(6, WildVariant.values().length);
        for (var v : WildVariant.values()) { assertSame(v, WildVariant.byId(v.id)); assertNotNull(v.icon); }
        assertNull(WildVariant.byId("boss")); assertNull(WildVariant.byId(null));
    }
    @Test void desertSnowAndForestRequireTheCorrectBaseSpecies() {
        assertSame(WildVariant.ASH, WildVariant.habitat(EntityType.HUSK, World.Environment.NORMAL, "desert"));
        assertNull(WildVariant.habitat(EntityType.ZOMBIE, World.Environment.NORMAL, "desert"));
        assertSame(WildVariant.FROST, WildVariant.habitat(EntityType.STRAY, World.Environment.NORMAL, "snowy_plains"));
        assertSame(WildVariant.THICKET, WildVariant.habitat(EntityType.SPIDER, World.Environment.NORMAL, "dark_forest"));
        assertNull(WildVariant.habitat(EntityType.CAVE_SPIDER, World.Environment.NORMAL, "dark_forest"));
    }
    @Test void swampSubstitutionDoesNotAcceptZombiesElsewhere() {
        assertSame(WildVariant.BOG, WildVariant.habitat(EntityType.ZOMBIE, World.Environment.NORMAL, "swamp"));
        assertSame(WildVariant.BOG, WildVariant.habitat(EntityType.DROWNED, World.Environment.NORMAL, "mangrove_swamp"));
        assertNull(WildVariant.habitat(EntityType.DROWNED, World.Environment.NORMAL, "river"));
    }
    @Test void netherAndEndCannotLeakIntoOtherDimensions() {
        assertSame(WildVariant.CRIMSON, WildVariant.habitat(EntityType.HOGLIN, World.Environment.NETHER, "crimson_forest"));
        assertNull(WildVariant.habitat(EntityType.HOGLIN, World.Environment.NORMAL, "crimson_forest"));
        assertSame(WildVariant.RIFT, WildVariant.habitat(EntityType.ENDERMAN, World.Environment.THE_END, "end_highlands"));
        assertNull(WildVariant.habitat(EntityType.ENDERMAN, World.Environment.NORMAL, "plains"));
        assertNull(WildVariant.habitat(EntityType.ENDERMAN, World.Environment.THE_END, "the_void"));
    }
}
