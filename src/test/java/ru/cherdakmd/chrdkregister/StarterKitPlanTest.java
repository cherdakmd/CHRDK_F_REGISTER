package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Бросок комплекта проверяется без сервера: только строки, числа и Random. */
final class StarterKitPlanTest {

    private static final List<String> IDS =
            List.of("lantern", "compass", "mirror", "anchor", "feather");

    @Test
    void stockConfigCarriesTheRequestedAmounts() {
        var stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        StarterKitSettings settings = StarterKitSettings.parse(stock);
        assertTrue(settings.enabled());
        assertEquals(1000L, settings.bankDiamonds());
        assertEquals(32, settings.inventoryDiamonds());
        assertEquals(4, settings.armorPieces());
        assertEquals(3, settings.tools());
        assertEquals(2, settings.artifacts());
        assertTrue(settings.includeCursedArtifacts());
    }

    @Test
    void missingSectionFallsBackToSafeDefaults() {
        assertEquals(StarterKitSettings.defaults(),
                StarterKitSettings.parse(new YamlConfiguration()));
    }

    @Test
    void ownerTyposAreClamped() {
        var yaml = new YamlConfiguration();
        yaml.set("starter-kit.bank-diamonds", 9_999_999);
        yaml.set("starter-kit.inventory-diamonds", 999);
        yaml.set("starter-kit.armor-pieces", -5);
        yaml.set("starter-kit.tools", 99);
        yaml.set("starter-kit.artifacts", 99);
        StarterKitSettings settings = StarterKitSettings.parse(yaml);
        assertEquals(100_000L, settings.bankDiamonds());
        assertEquals(64, settings.inventoryDiamonds());
        assertEquals(0, settings.armorPieces());
        assertEquals(4, settings.tools());
        assertEquals(6, settings.artifacts());
    }

    @Test
    void everyRollIsBoundedAndUsesKnownMaterials() {
        Random dice = new Random(20261107L);
        Set<String> seenArmor = new HashSet<>();
        Set<String> seenTools = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            StarterKitPlan plan = StarterKitPlan.roll(
                    StarterKitSettings.defaults(), dice, IDS);
            assertEquals(1000L, plan.bankDiamonds());
            assertEquals(32, plan.inventoryDiamonds());
            assertEquals(4, plan.armor().size());
            assertEquals(3, plan.tools().size());
            assertEquals(1, plan.artifactIds().size(), "половина артефактов — из каталога");
            assertEquals(1, plan.vanityItems().size(), "половина — ванильные");
            assertEquals(10, plan.itemStacks());
            for (String armor : plan.armor()) {
                assertTrue(valid(armor, StarterKitPlan.ARMOR_TIERS,
                        StarterKitPlan.ARMOR_SLOTS), armor);
                seenArmor.add(armor);
            }
            for (String tool : plan.tools()) {
                assertTrue(valid(tool, StarterKitPlan.TOOL_TIERS,
                        StarterKitPlan.TOOL_KINDS), tool);
                seenTools.add(tool);
            }
            assertTrue(IDS.containsAll(plan.artifactIds()));
            assertTrue(StarterKitPlan.VANILLA_TREASURES.containsAll(plan.vanityItems()));
            assertFalse(plan.armor().contains("CHAINMAIL_PICKAXE"), "инструменты без кольчуги");
        }
        assertTrue(seenArmor.size() > 4, "броня действительно случайна: " + seenArmor.size());
        assertTrue(seenTools.size() > 4, "инструменты действительно случайны: " + seenTools.size());
    }

    @Test
    void withoutArtifactsAddonTheWholeShareIsVanilla() {
        StarterKitPlan plan = StarterKitPlan.roll(
                StarterKitSettings.defaults(), new Random(1L), List.of());
        assertTrue(plan.artifactIds().isEmpty());
        assertEquals(2, plan.vanityItems().size());
        assertEquals(1000L, plan.bankDiamonds());
    }

    private static boolean valid(String name, List<String> tiers, List<String> kinds) {
        for (String tier : tiers) {
            for (String kind : kinds) {
                if ((tier + "_" + kind).equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
