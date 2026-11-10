package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.MineTreasureLootPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Политика слияния лута и её проводка по всем затронутым модулям без сервера. */
final class MineTreasureLootPolicyTest {

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void tableDetectionIsNamespaceBased() {
        assertTrue(MineTreasureLootPolicy.isMineTreasureTable("mt:chests/ancient_city"));
        assertTrue(MineTreasureLootPolicy.isMineTreasureTable("mine_treasure:chests/ancient_city"));
        assertTrue(MineTreasureLootPolicy.isMineTreasureTable(
                new NamespacedKey("mt", "chests/ancient_city")));
        assertFalse(MineTreasureLootPolicy.isMineTreasureTable("minecraft:chests/ancient_city"));
        assertFalse(MineTreasureLootPolicy.isMineTreasureTable("minecraft:blocks/stone"));
        assertFalse(MineTreasureLootPolicy.isMineTreasureTable((String) null));
        assertFalse(MineTreasureLootPolicy.isMineTreasureTable((NamespacedKey) null));
        assertFalse(MineTreasureLootPolicy.isMineTreasureTable((org.bukkit.loot.LootTable) null));
        assertEquals("chrdk:mine_treasure_item", MineTreasureLootPolicy.OWNED_ITEM.toString());
        assertTrue(MineTreasureLootPolicy.vanillaOnly(null).isEmpty());
    }

    @Test
    void allLootListenersUseTheSharedPolicy() throws IOException {
        String policy = source("src/main/java/ru/cherdakmd/chrdkregister/api/MineTreasureLootPolicy.java");
        assertTrue(policy.contains("new NamespacedKey(\"chrdk\", \"mine_treasure_item\")"));
        assertTrue(policy.contains("vanillaOnly(Collection<ItemStack> drops)"));

        String skills = source("skills/src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java");
        assertTrue(skills.contains("MineTreasureLootPolicy.vanillaOnly("));
        assertTrue(skills.contains("block.getDrops(player.getInventory().getItemInMainHand(), player)"));
        assertTrue(skills.contains("dropItemNaturally(dropAt, drop.clone())"));

        String enchants = source("enchants/src/main/java/ru/cherdakmd/chrdkenchants/ToolEffectsListener.java");
        assertTrue(enchants.contains("MineTreasureLootPolicy.isForeignDrop(stack)"));

        String forge = source("tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/ForgeUpgradeListener.java");
        assertTrue(forge.contains("MineTreasureLootPolicy.isForeignDrop(stack)"));

        String world = source("tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/TierWorldListener.java");
        assertTrue(world.contains("MineTreasureLootPolicy.isMineTreasureTable(event.getLootTable())"));

        String artifacts = source("artifacts/src/main/java/ru/cherdakmd/chrdkartifacts/ArtifactLootListener.java");
        assertTrue(artifacts.contains("mt:chests/..."));
        assertTrue(artifacts.contains("chestChancePercent(table.getKey().toString())"));

        String polarity = source("enchants/src/main/java/ru/cherdakmd/chrdkenchants/PolarityEffectsListener.java");
        assertTrue(polarity.contains("не разыгрывает таблицу лута заново"));
    }

    @Test
    void integrationArtifactAndWorkflowsStayAligned() throws IOException {
        String integration = source("src/main/java/ru/cherdakmd/chrdkregister/datapack/MineTreasureIntegration.java");
        assertTrue(integration.contains("DEFAULT_FILE_NAME = \"mine-treasure-1.6.5-ru.zip\""));
        assertTrue(integration.contains("MANAGER_METHOD = \"getDatapackManager\""));

        String config = source("src/main/resources/config.yml");
        assertTrue(config.contains("file-name: \"mine-treasure-1.6.5-ru.zip\""));
        assertTrue(config.contains("MineTreasureLootPolicy"));

        String ignore = source(".gitignore");
        assertFalse(ignore.contains("/datapacks/mine-treasure-ru/*.zip"));

        String build = source(".github/workflows/build.yml");
        assertTrue(build.contains("build_mine_treasure.py --check"));

        String release = source(".github/workflows/release.yml");
        assertTrue(release.contains("build_mine_treasure.py --check"));
        assertTrue(release.contains("datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip"));

        String installer = source("tools/datapacks/install_mine_treasure.py");
        String manifest = source("tools/datapacks/mine_treasure_manifest.json");
        assertTrue(installer.contains("d51346e81b32fda61e7d4ba7a9475289815857451f4bea8a69d45f61527f3ec6"));
        assertTrue(manifest.contains("d51346e81b32fda61e7d4ba7a9475289815857451f4bea8a69d45f61527f3ec6"));
        assertTrue(manifest.contains("\"pdc_markers\": 1507"));
    }
}
