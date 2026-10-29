package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TreasureFindNotificationsTest {
    private static final Logger LOGGER = Logger.getLogger("TreasureFindNotificationsTest");

    @Test void missingKeyIsSilentEvenWithAllLegacySpamSettingsEnabled() {
        var yaml = new YamlConfiguration();
        yaml.set("feedback.broadcast-legendary", true);
        yaml.set("feedback.broadcast-mythical", true);
        yaml.set("feedback.log-finds", true);
        yaml.set("feedback.show-loot", true);
        var config = TreasuresConfig.parse(yaml, LOGGER);
        assertFalse(config.findNotifications());
        assertTrue(config.broadcastLegendary()); // Старые настройки сохранены, но общий запрет важнее.
        assertTrue(config.logFinds());
        assertTrue(config.enabled());
        assertTrue(config.effects().actionbar());
        assertTrue(config.effects().titles());
        assertTrue(config.effects().fireworks());
    }

    @Test void shippedConfigIsSilentAndExplicitOptInIsRequired() throws Exception {
        var yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        var quiet = TreasuresConfig.parse(yaml, LOGGER);
        assertFalse(quiet.findNotifications());
        yaml.set("feedback.find-notifications", true);
        var loud = TreasuresConfig.parse(yaml, LOGGER);
        assertTrue(loud.findNotifications());
        assertEquals(quiet.chance(TreasureTier.COMMON), loud.chance(TreasureTier.COMMON));
        assertEquals(quiet.itemChance(TreasureTier.MYTHICAL), loud.itemChance(TreasureTier.MYTHICAL));
        assertEquals(quiet.drops(), loud.drops());
        assertEquals(quiet.experience(), loud.experience());
        assertEquals(quiet.streakBonusEvery(), loud.streakBonusEvery());
        assertEquals(quiet.rankReward(TreasureRank.LEGEND), loud.rankReward(TreasureRank.LEGEND));
    }

    @Test void mainNotificationGateIsAfterVisualsButBeforePersonalBroadcastAndLog() throws Exception {
        String source = source("TreasureListener");
        String celebrate = source.substring(source.indexOf("    void celebrate("), source.indexOf("    /** Строки об особых"));
        int gate = celebrate.indexOf("if (!plugin.config().findNotifications()) {\n            return;");
        assertTrue(gate > celebrate.indexOf("plugin.showcase().show(player, found)"));
        assertTrue(gate < celebrate.indexOf("plugin.messages().send(player, \"treasure-found-named\""));
        assertTrue(gate < celebrate.indexOf("specials(player, found)"));
        assertTrue(gate < celebrate.indexOf("\"shards-earned\""));
        assertTrue(gate < celebrate.indexOf("\"treasure-broadcast-named\""));
        assertTrue(gate < celebrate.indexOf("logFind(player, found, summary)"));
        assertTrue(source.contains("if (!plugin.config().findNotifications() || !plugin.config().logFinds())"));
    }

    @Test void groundMapAndStreakCannotBypassTheGate() throws Exception {
        String drops = source("TreasureDrops");
        assertTrue(drops.contains("if (plugin.config() != null && plugin.config().findNotifications()"));
        assertTrue(drops.indexOf("findNotifications()") < drops.indexOf("\"drops-landed\""));
        assertTrue(drops.contains("effects(world, center, player, key)"));
        String map = source("TreasureMapService");
        String reveal = map.substring(map.indexOf("    private TreasureService.Found reveal("), map.indexOf("    /* --------------------------------- подсказки"));
        assertTrue(reveal.contains("if (plugin.config().findNotifications()"));
        assertTrue(reveal.indexOf("findNotifications()") < reveal.indexOf("\"map-found\""));
        assertTrue(reveal.contains("plugin.listener().celebrate(player, block.getLocation(), found)"));
        String service = source("TreasureService");
        String bonus=service.substring(service.indexOf("private List<TreasureLoot.LootStack> grantStreakBonus"),service.indexOf("private void checkRank"));
        assertTrue(bonus.contains("payload.add(itemStack)"));
        assertFalse(bonus.contains("messages().send"));
        assertFalse(source("TreasureListener").contains("\"double-ore\""));
    }

    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdktreasures/" + name + ".java"));
    }
}
