package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор секции {@code ranks} (1.4.167): умолчания, оформление, приветственный ранг. */
class RankSettingsTest {

    private static RankSettings parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return RankSettings.from(configuration);
    }

    @Test
    void defaultsBuildFiveTierGuildLadder() {
        RankSettings defaults = RankSettings.defaults();
        assertTrue(defaults.enabled());
        assertEquals(5, defaults.maxLevel());
        assertEquals("Странник", defaults.name(1));
        assertEquals("Следопыт", defaults.name(2));
        assertEquals("Хранитель", defaults.name(3));
        assertEquals("Меценат", defaults.name(4));
        assertEquals("Покровитель", defaults.name(5));
        assertEquals("✦", defaults.badge(1));
        assertEquals("⚜", defaults.badge(5));
        assertEquals(3, defaults.welcomeDays(), "приветственный ранг — три дня");
        assertEquals(1, defaults.welcomeLevel());
    }

    @Test
    void bonusesGrowWithTierAndRespectContractCeilings() throws Exception {
        RankSettings defaults = RankSettings.defaults();
        assertTrue(defaults.bonus(0).empty(), "без ранга бонусов нет");
        assertEquals(10, defaults.bonus(1).xpPercent());
        assertEquals(50, defaults.bonus(5).xpPercent());
        assertEquals(25, defaults.bonus(5).dropPercent());
        assertEquals(10, defaults.bonus(5).extraSkill());
        assertEquals(1.5, defaults.bonus(5).xpFactor(), 0.0001);
        assertEquals(50, defaults.bonus(9).xpPercent(), "выше пятой ступени — значения пятой");

        RankSettings greedy = parse("""
                ranks:
                  bonuses:
                    xp-percent: [0, 100, 300]
                    drop-percent: [0, 90]
                    skill-levels: [0, 40]
                """);
        assertEquals(50, greedy.bonus(3).xpPercent(), "потолок контракта 50 %");
        assertEquals(25, greedy.bonus(2).dropPercent(), "потолок контракта 25 %");
        assertEquals(10, greedy.bonus(2).extraSkill(), "потолок контракта +10 уровней");
    }

    @Test
    void brokenBonusTableFallsBackToDefaults() throws Exception {
        RankSettings settings = parse("""
                ranks:
                  bonuses:
                    xp-percent: ["много", 0]
                """);
        assertEquals(10, settings.bonus(1).xpPercent(), "таблица без чисел — умолчания");
        assertEquals(5, settings.bonus(1).dropPercent(), "остальные таблицы тоже умолчания");
    }

    @Test
    void decorateWrapsNameInRankColour() {
        RankSettings settings = RankSettings.defaults();
        assertEquals("<gray>Имя</gray>", settings.decorate(1, "Имя"));
        assertEquals("<green>Имя</green>", settings.decorate(2, "Имя"));
        assertEquals("Имя", settings.decorate(0, "Имя"), "без ранга ник не трогаем");
        assertEquals("", settings.decorate(1, null));
    }

    @Test
    void joinLinesFillPlayerPlaceholder() {
        RankSettings settings = RankSettings.defaults();
        assertEquals("", settings.joinLine(1, "Имя"), "у Странника нет строки входа");
        assertTrue(settings.joinLine(3, "Имя").contains("Хранитель"));
        assertTrue(settings.joinLine(3, "Имя").contains("Имя"));
        assertEquals("", settings.joinLine(0, "Имя"));
    }

    @Test
    void missingSectionFallsBackToDefaults() throws Exception {
        RankSettings settings = parse("");
        assertEquals(5, settings.maxLevel());
        assertTrue(settings.enabled());
    }

    @Test
    void customTiersOverrideDefaultsAndSkipBrokenLevels() throws Exception {
        RankSettings settings = parse("""
                ranks:
                  enabled: false
                  show-name-in-chat: true
                  welcome:
                    enabled: false
                    level: 2
                    days: 3
                  levels:
                    - level: 1
                      id: rookie
                      name: "Новичок гильдии"
                      format: "<white>%name%</white>"
                      badge: "*"
                    - level: 9
                      id: broken
                      name: "Слишком высоко"
                    - level: 1
                      id: duplicate
                      name: "Дубль"
                """);
        assertFalse(settings.enabled());
        assertTrue(settings.showNameInChat());
        assertFalse(settings.welcomeEnabled());
        assertEquals(3, settings.welcomeDays());
        assertEquals(2, settings.welcomeLevel());
        assertEquals(1, settings.maxLevel(), "ступень 9 отброшена");
        assertEquals("Новичок гильдии", settings.name(1), "дубль уровня не перекрывает первую запись");
        assertEquals("<white>Имя</white>", settings.decorate(1, "Имя"));
        assertNull(settings.tier(2));
    }
}
