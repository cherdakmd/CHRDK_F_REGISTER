package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Блок honors.* в config.yml: терпимый разбор и границы причины. */
class HonorConfigTest {

    @Test
    void defaultsAreQuietAndExplainable() {
        HonorConfig config = HonorConfig.DEFAULT;
        assertTrue(config.enabled());
        assertFalse(config.announceGrant(), "реестр достаточен без чат-шума");
        assertTrue(config.showInTab());
        assertEquals(160, config.maxReasonLength());
        assertTrue(config.defaultReason().contains("выслугу"));
        assertSame(HonorConfig.DEFAULT, HonorConfig.fromConfig(null));
    }

    @Test
    void configValuesAreClampedAndBlanksFallBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("honors.enabled", false);
        yaml.set("honors.announce-grant", true);
        yaml.set("honors.show-in-tab", false);
        yaml.set("honors.max-reason-length", 99999);
        yaml.set("honors.default-reason", "   ");
        HonorConfig config = HonorConfig.fromConfig(yaml);
        assertFalse(config.enabled());
        assertTrue(config.announceGrant());
        assertFalse(config.showInTab());
        assertEquals(500, config.maxReasonLength());
        assertEquals(HonorConfig.DEFAULT_REASON, config.defaultReason());

        yaml.set("honors.max-reason-length", 1);
        assertEquals(20, HonorConfig.fromConfig(yaml).maxReasonLength());
    }

    @Test
    void initialGrantsAreParsedAndSanitised() {
        assertTrue(HonorConfig.DEFAULT.initialGrants().isEmpty());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("honors.initial-grants", List.of(
                Map.of("player", "StaveLime", "honor", "old-citrus",
                        "reason", "За выслугу лет: знакомы много лет"),
                Map.of("player", "%username%", "honor", "old-citrus"),
                Map.of("player", "Alex", "honor", "Old Citrus!", "reason", "  ")));
        HonorConfig config = HonorConfig.fromConfig(yaml);
        assertEquals(2, config.initialGrants().size(), "невалидный ник отброшен");
        HonorConfig.InitialGrant first = config.initialGrants().get(0);
        assertEquals("StaveLime", first.player());
        assertEquals("old-citrus", first.honorId());
        assertTrue(first.reason().contains("выслугу"));
        HonorConfig.InitialGrant second = config.initialGrants().get(1);
        assertEquals("Alex", second.player());
        assertEquals("old-citrus", second.honorId(), "id нормализуется");
        assertEquals(HonorConfig.DEFAULT_REASON, second.reason(), "пустая причина — по умолчанию");
    }

    @Test
    void stockConfigGrantsCitrusToStaveLime() {
        java.nio.file.Path path = java.nio.file.Path.of("src/main/resources/config.yml");
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
            List<HonorConfig.InitialGrant> grants = HonorConfig.fromConfig(yaml).initialGrants();
            assertEquals(1, grants.size());
            assertEquals("StaveLime", grants.get(0).player());
            assertEquals("old-citrus", grants.get(0).honorId());
            assertTrue(grants.get(0).reason().contains("выслугу лет"));
        } catch (Exception error) {
            fail("config.yml должен читаться как Bukkit YAML: " + error);
        }
    }
}
