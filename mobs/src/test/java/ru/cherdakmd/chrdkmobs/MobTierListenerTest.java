package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Базовое имя моба: бирка игрока, русское название из mob-names, иначе ванильное.
 */
class MobTierListenerTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private static Messages messages(String yaml) {
        return Messages.from(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), null, LOGGER);
    }

    @Test
    void russianNameFromDictionary() {
        Messages messages = messages("""
                messages:
                  mob-names:
                    PILLAGER: Разбойник
                    ZOMBIE: Зомби
                """);
        assertEquals("Разбойник",
                MobTierListener.resolveBaseName(messages, "PILLAGER", null, "Pillager"));
        assertEquals("Зомби",
                MobTierListener.resolveBaseName(messages, "ZOMBIE", null, "Zombie"));
    }

    @Test
    void nameTagWinsOverDictionary() {
        Messages messages = messages("""
                messages:
                  mob-names:
                    PILLAGER: Разбойник
                """);
        assertEquals("Босс",
                MobTierListener.resolveBaseName(messages, "PILLAGER", "Босс", "Pillager"));
    }

    @Test
    void blankNameTagFallsThroughToDictionary() {
        Messages messages = messages("""
                messages:
                  mob-names:
                    PILLAGER: Разбойник
                """);
        assertEquals("Разбойник",
                MobTierListener.resolveBaseName(messages, "PILLAGER", "  ", "Pillager"));
    }

    @Test
    void unknownTypeFallsBackToVanillaName() {
        Messages messages = messages("""
                messages:
                  mob-names:
                    ZOMBIE: Зомби
                """);
        assertEquals("Axolotl",
                MobTierListener.resolveBaseName(messages, "AXOLOTL", null, "Axolotl"));
    }

    @Test
    void withoutMessagesFallsBackToVanillaName() {
        assertEquals("Pillager",
                MobTierListener.resolveBaseName(null, "PILLAGER", null, "Pillager"));
    }

    @Test
    void stockDictionaryCoversAllHostileTypes() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/messages.yml"), StandardCharsets.UTF_8);
        Messages stock = messages(yaml);
        // Все враждебные мобы, которых тирует MobTierListener (org.bukkit.entity.Enemy).
        for (String type : new String[]{"BLAZE", "BOGGED", "BREEZE", "CAVE_SPIDER", "CREEPER",
                "CREAKING", "DROWNED", "ELDER_GUARDIAN", "ENDERMITE", "EVOKER", "GHAST",
                "GUARDIAN", "HUSK", "MAGMA_CUBE", "PHANTOM", "PILLAGER", "PIGLIN_BRUTE",
                "RAVAGER", "SHULKER", "SILVERFISH", "SKELETON", "SLIME", "SPIDER", "STRAY",
                "VEX", "VINDICATOR", "WARDEN", "WITCH", "WITHER_SKELETON", "ZOGLIN",
                "ZOMBIE", "ZOMBIE_VILLAGER"}) {
            String name = MobTierListener.resolveBaseName(stock, type, null, "fallback");
            assertNotEquals("fallback", name, "нет русского названия для " + type);
        }
    }
}
