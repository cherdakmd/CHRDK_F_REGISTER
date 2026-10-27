package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.logging.Logger;

class TreasureBookInfoTest {
    @Test void reportReadsCurrentNormalizedSnapshotNotCopiedDefaults() {
        var yaml = new YamlConfiguration(); yaml.set("specials.enchantments.chance-percent.rare", 17.5);
        yaml.set("specials.enchantments.max-level.rare", 2);
        var cfg = TreasuresConfig.parse(yaml, Logger.getAnonymousLogger());
        var info = new TreasureBookInfo(() -> cfg).snapshot();
        var rare = info.tiers().stream().filter(tier -> tier.id().equals("rare")).findFirst().orElseThrow();
        assertEquals(17.5, rare.chancePercent()); assertEquals(2, rare.maxLevel());
        assertThrows(UnsupportedOperationException.class, () -> info.tiers().clear());
        yaml.set("specials.enchantments.enabled", false);
        var disabled = TreasuresConfig.parse(yaml, Logger.getAnonymousLogger());
        assertFalse(new TreasureBookInfo(() -> disabled).snapshot().enabled());
    }
}
