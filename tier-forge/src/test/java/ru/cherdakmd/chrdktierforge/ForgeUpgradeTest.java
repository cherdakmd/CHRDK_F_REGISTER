package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Реестр кузнечных улучшений (1.4.55): состав, уникальность оплат, потолки,
 * подбор по материалу, форматирование лора и римские уровни.
 */
class ForgeUpgradeTest {

    private static final Set<String> REPAIR_MATERIALS = Set.of(
            "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "NETHERITE_INGOT", "LEATHER",
            "TURTLE_SCUTE", "PHANTOM_MEMBRANE", "COBBLESTONE", "CHAIN");

    @Test
    void registryHasFifteenUniqueUpgrades() {
        assertEquals(15, ForgeUpgrade.ALL.size());
        Set<String> ids = new HashSet<>();
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            assertTrue(ids.add(def.id()), "дубликат id: " + def.id());
            assertFalse(def.displayName().isBlank(), "пустое имя у " + def.id());
            assertTrue(def.perLevel() > 0, "бесполезный шаг у " + def.id());
            assertTrue(def.maxLevel() >= 1 && def.maxLevel() <= 10, "странный потолок у " + def.id());
        }
        assertEquals(15, ids.size());
    }

    @Test
    void defaultMaterialsUniqueAndSafeForAnvil() {
        Set<String> materials = new HashSet<>();
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            assertTrue(materials.add(def.material()),
                    "два улучшения делят материал " + def.material() + " — неоднозначность на наковальне");
            assertFalse(REPAIR_MATERIALS.contains(def.material()),
                    def.material() + " коллидирует с ванильной починкой (" + def.id() + ")");
            assertNotNull(org.bukkit.Material.matchMaterial(def.material()),
                    "неизвестный Bukkit материал " + def.material() + " (" + def.id() + ")");
        }
        assertFalse(materials.contains("AMETHYST_SHARD"),
                "материал улучшения не должен совпадать с катализатором переката");
    }

    @Test
    void materialLookupRespectsTargetAndOverrides() {
        ForgeUpgrade.Def edge = ForgeUpgrade.forMaterial("quartz", ItemKind.SWORD, null);
        assertNotNull(edge);
        assertEquals("edge", edge.id());
        // броне кварц не по зубам (мечевое улучшение)
        assertNull(ForgeUpgrade.forMaterial("QUARTZ", ItemKind.HELMET, null));
        // «Закалка» универсальна
        assertEquals("tempered", ForgeUpgrade.forMaterial("PRISMARINE_CRYSTALS", ItemKind.PICKAXE, null).id());
        // топор — и оружие, и инструмент: ему доступны обе ветки
        assertEquals("edge", ForgeUpgrade.forMaterial("QUARTZ", ItemKind.AXE, null).id());
        assertEquals("quickpick", ForgeUpgrade.forMaterial("REDSTONE", ItemKind.AXE, null).id());
        // переопределение конфигом
        assertEquals("edge", ForgeUpgrade.forMaterial("GLOWSTONE_DUST", ItemKind.SWORD,
                Map.of("edge", "GLOWSTONE_DUST")).id());
        assertNull(ForgeUpgrade.forMaterial("QUARTZ", ItemKind.SWORD,
                Map.of("edge", "GLOWSTONE_DUST")));
        // левые материалы не распознаются
        assertNull(ForgeUpgrade.forMaterial("APPLE", ItemKind.SWORD, null));
    }

    @Test
    void describeFormatsPerLevelNumbers() {
        ForgeUpgrade.Def edge = ForgeUpgrade.byId("edge");
        assertEquals("+6% урона", ForgeUpgrade.describe(edge, 3));
        assertEquals("+4.5% скорости", ForgeUpgrade.describe(
                ForgeUpgrade.byId("lightfoot"), 3));
        assertEquals("+2❤ к здоровью", ForgeUpgrade.describe(
                ForgeUpgrade.byId("vitality"), 4));
        assertEquals("вампиризм 6% урона", ForgeUpgrade.describe(
                ForgeUpgrade.byId("lifesteal"), 3));
    }

    @Test
    void romanCoversAllRegistryLevels() {
        assertEquals("I", ForgeUpgrade.roman(1));
        assertEquals("III", ForgeUpgrade.roman(3));
        assertEquals("IV", ForgeUpgrade.roman(4));
        assertEquals("V", ForgeUpgrade.roman(5));
        assertEquals("X", ForgeUpgrade.roman(10));
        assertEquals("11", ForgeUpgrade.roman(11));
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            assertFalse(ForgeUpgrade.roman(def.maxLevel()).isBlank());
        }
    }
}
