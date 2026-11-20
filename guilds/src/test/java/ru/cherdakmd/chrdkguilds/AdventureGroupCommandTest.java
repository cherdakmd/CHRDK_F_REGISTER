package ru.cherdakmd.chrdkguilds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AdventureGroupCommandTest {
    @Test void directCommandUsesSameArgumentsAsGuildRoute() {
        assertArrayEquals(new String[]{"squad"}, GuildsCommandHandler.partyArguments(new String[]{}));
        assertArrayEquals(new String[]{"squad", "invite", "Alex"},
                GuildsCommandHandler.partyArguments(new String[]{"invite", "Alex"}));
        for (String action : new String[]{"accept", "decline", "leave", "info"}) {
            assertArrayEquals(new String[]{"squad", action},
                    GuildsCommandHandler.partyArguments(new String[]{action}));
        }
    }

    @Test void commandBelongsToGuildPluginAndUsesExistingPermission() {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("chrdkguilds.use", yaml.getString("commands.party.permission"));
        } catch (java.io.IOException e) {
            fail(e);
        }
    }
}
