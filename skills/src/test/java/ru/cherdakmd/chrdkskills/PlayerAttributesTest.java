package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Атрибуты персонажа: команда распределения, эффекты и связь с тирами мобов.
 * Очки за уровни навыков, ручное распределение, давление тира и бонусы.
 */
class PlayerAttributesTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void attributesAreDistributedByCommand() throws IOException {
        String commands = read("src/main/java/ru/cherdakmd/chrdkskills/SkillCommandHandler.java");
        assertTrue(commands.contains("handleAttributes"), "команда /skills attrs распределяет очки");
        assertTrue(commands.contains("\"attrs-reset-done\""), "полный сброс распределения есть");
        assertTrue(commands.contains("PlayerAttribute.byId"), "атрибут узнаётся по имени и id");
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("attrs-view:"), "сводка атрибутов показывается игроку");
        assertTrue(messages.contains("attrs-short:"), "нехватка свободных очков объясняется");
    }

    @Test
    void attributesDriveEffectsAndTierInteraction() throws IOException {
        String effects = read("src/main/java/ru/cherdakmd/chrdkskills/SkillEffects.java");
        assertTrue(effects.contains("chrdkskills_attr_health"), "стойкость даёт здоровье");
        assertTrue(effects.contains("chrdkskills_attr_attack_speed"), "ловкость ускоряет атаку");
        String listener = read("src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java");
        assertTrue(listener.contains("AttributeMath.meleeFactor"), "сила и хитрость усиливают ближний бой");
        assertTrue(listener.contains("AttributeMath.pressure"), "недобор атрибутов зоны давит штрафом");
        String api = read("src/main/java/ru/cherdakmd/chrdkskills/ChrdkSkillsApi.java");
        assertTrue(api.contains("attributeSum"), "ранг атрибутов доступен другим аддонам");
        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        assertTrue(service.contains("AttributeMath.xpFactor"), "мудрость ускоряет опыт навыков");
    }
}
