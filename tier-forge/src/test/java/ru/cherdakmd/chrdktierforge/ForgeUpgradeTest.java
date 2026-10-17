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
 * Реестр кузнечных улучшений (1.4.55 + 1.4.56): состав (50 видов),
 * уникальность оплат, потолки, подбор по материалу, форматирование лора
 * и римские уровни.
 */
class ForgeUpgradeTest {

    // Материалы ванильной наковальни-починки СЕМЕЙСТВ, которые проходят тир-фильтр.
    // (Кожа/булыжник/цепь фиксируем про запас. Мембрана фантома официально чинит
    // элитру, но элитра не попадает в тир-систему — поэтому мембрану «Вееру» разрешаем.)
    private static final Set<String> REPAIR_MATERIALS = Set.of(
            "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "NETHERITE_INGOT", "LEATHER",
            "TURTLE_SCUTE", "COBBLESTONE", "CHAIN");

    @Test
    void registryHasEightyFiveUniqueUpgrades() {
        assertEquals(85, ForgeUpgrade.ALL.size());
        Set<String> ids = new HashSet<>();
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            assertTrue(ids.add(def.id()), "дубликат id: " + def.id());
            assertFalse(def.displayName().isBlank(), "пустое имя у " + def.id());
            assertTrue(def.perLevel() > 0, "бесполезный шаг у " + def.id());
            assertTrue(def.maxLevel() >= 1 && def.maxLevel() <= 10, "странный потолок у " + def.id());
        }
        assertEquals(85, ids.size());
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
        assertEquals("crusher", ForgeUpgrade.forMaterial("FLINT", ItemKind.SWORD,
                Map.of("crusher", "FLINT")).id());
        assertNull(ForgeUpgrade.forMaterial("QUARTZ", ItemKind.SWORD,
                Map.of("edge", "GLOWSTONE_DUST")));
        // вторая ковка: новые материалы встают в свои ветки
        assertEquals("igniter", ForgeUpgrade.forMaterial("FLINT_AND_STEEL", ItemKind.SWORD, null).id());
        assertEquals("unbending", ForgeUpgrade.forMaterial("ANCIENT_DEBRIS", ItemKind.CHESTPLATE, null).id());
        assertEquals("hewer", ForgeUpgrade.forMaterial("RAIL", ItemKind.PICKAXE, null).id());
        assertEquals("soulbind", ForgeUpgrade.forMaterial("NETHER_STAR", ItemKind.BOOTS, null).id());
        assertNull(ForgeUpgrade.forMaterial("PUFFERFISH", ItemKind.SWORD, null),
                "Жабры — броневое свойство, оружию не положено");
        // третья ковка (1.4.57)
        assertEquals("storm", ForgeUpgrade.forMaterial("LIGHTNING_ROD", ItemKind.SWORD, null).id());
        assertEquals("plated", ForgeUpgrade.forMaterial("COPPER_BLOCK", ItemKind.HELMET, null).id());
        assertEquals("sailor", ForgeUpgrade.forMaterial("HEART_OF_THE_SEA", ItemKind.PICKAXE, null).id());
        assertEquals("scholar", ForgeUpgrade.forMaterial("PAPER", ItemKind.PICKAXE, null).id());
        assertNull(ForgeUpgrade.forMaterial("LIGHTNING_ROD", ItemKind.HELMET, null),
                "Громовержец — оружейное свойство, броне не положен");
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
        // вторая ковка: абсолютные, процентные и текстовые описания
        assertEquals("+1 к урону", ForgeUpgrade.describe(ForgeUpgrade.byId("sharpen"), 4));
        assertEquals("+40% отпора отбросу", ForgeUpgrade.describe(ForgeUpgrade.byId("unbending"), 4));
        assertEquals("+3 м мягкости падения", ForgeUpgrade.describe(ForgeUpgrade.byId("feather"), 3));
        assertEquals("+2 удачи", ForgeUpgrade.describe(ForgeUpgrade.byId("charm"), 4));
        assertEquals("не выпадает при смерти", ForgeUpgrade.describe(ForgeUpgrade.byId("soulbind"), 1));
        assertEquals("ночное зрение (пассивно)", ForgeUpgrade.describe(ForgeUpgrade.byId("lantern"), 1));
        // третья ковка
        assertEquals("-12% гравитации", ForgeUpgrade.describe(ForgeUpgrade.byId("gliding"), 2));
        assertEquals("+2 к прочности брони", ForgeUpgrade.describe(ForgeUpgrade.byId("plated"), 4));
        assertEquals("+20% лёгкости ходьбы", ForgeUpgrade.describe(ForgeUpgrade.byId("hikers"), 2));
        assertEquals("24% ударить громом (+1.5❤)", ForgeUpgrade.describe(
                ForgeUpgrade.byId("storm"), 3));
        assertEquals("скорость вне боя (после 5 с тишины)", ForgeUpgrade.describe(
                ForgeUpgrade.byId("breeze"), 1));
        // у описаний не должно быть угловых скобок — MiniMessage распарсил бы их как теги
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            String line = ForgeUpgrade.describe(def, Math.min(3, def.maxLevel()));
            assertFalse(line.contains("<") || line.contains(">"),
                    "угловые скобки в описании " + def.id() + ": " + line);
        }
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
