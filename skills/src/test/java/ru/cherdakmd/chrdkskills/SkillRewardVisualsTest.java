package ru.cherdakmd.chrdkskills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillRewardVisualsTest {
    @Test void stockRewardsHaveUniqueOptInModelsAndConfiguredRewardsAreNotReinterpreted() throws Exception {
        var cfg = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        var rows = cfg.getMapList("rebirth.unique-items"); assertEquals(3, rows.size());
        var seen = new HashSet<NamespacedKey>();
        for (var row : rows) {
            var item = new SkillsConfig.UniqueItem((String)row.get("id"), (String)row.get("material"), (String)row.get("name"),
                    ((List<?>) row.get("lore")).stream().map(String::valueOf).toList());
            var key = SkillRewardVisuals.model(item); assertNotNull(key); assertTrue(seen.add(key));
            assertEquals("chrdk_skills:rebirth/" + item.id(), key.toString());
            var meta = mock(ItemMeta.class); SkillRewardVisuals.applyNew(meta, item, false); verifyNoInteractions(meta);
            SkillRewardVisuals.applyNew(meta, item, true); verify(meta).setItemModel(key);
            assertNull(SkillRewardVisuals.model(new SkillsConfig.UniqueItem(item.id(), item.material(), item.name() + "!", item.lore())));
            assertNull(SkillRewardVisuals.model(new SkillsConfig.UniqueItem(item.id(), "DIRT", item.name(), item.lore())));
            assertNull(SkillRewardVisuals.model(new SkillsConfig.UniqueItem(item.id(), item.material(), item.name(), List.of("other"))));
        }
        assertNull(SkillRewardVisuals.model(new SkillsConfig.UniqueItem("custom", "NETHER_STAR", "custom", List.of())));
        var config = Files.readString(Path.of("src/main/resources/config.yml")); assertTrue(config.contains("rebirth-items: false"));
        var service = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java"));
        assertTrue(service.contains("SkillRewardVisuals.applyNew(meta, item, plugin.getConfig().getBoolean(\"textures.rebirth-items\", false))"));
    }
    @Test void externalModelsAndCustomDataAreNeverOverwritten() {
        var item = new SkillsConfig.UniqueItem("nether_star", "NETHER_STAR", "Звезда возрождения", List.of("Свет на месте сгоревшей жизни."));
        var meta = mock(ItemMeta.class); when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "thing"));
        SkillRewardVisuals.applyNew(meta, item, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        SkillRewardVisuals.applyNew(meta, item, true); verify(meta, never()).setItemModel(any());
    }
}
