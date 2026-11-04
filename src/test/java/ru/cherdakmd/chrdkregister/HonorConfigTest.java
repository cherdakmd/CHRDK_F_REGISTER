package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

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
}
