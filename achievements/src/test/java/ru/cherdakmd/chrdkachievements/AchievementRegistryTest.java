package ru.cherdakmd.chrdkachievements;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementRegistryTest {

    @Test
    void catalogueHasSeventyTwoEighteenPerCategory() {
        assertEquals(AchievementRegistry.EXPECTED_SIZE, AchievementRegistry.size());
        assertEquals(72, AchievementRegistry.size());
        for (AchievementDef.Category category : AchievementDef.Category.values()) {
            assertEquals(AchievementRegistry.PER_CATEGORY,
                    AchievementRegistry.byCategory(category).size(),
                    "в разделе " + category.id() + " должно быть ровно 18 достижений");
        }
    }

    @Test
    void idsAreUniqueAndLookupIsCaseInsensitive() {
        Set<String> ids = new HashSet<>();
        for (AchievementDef def : AchievementRegistry.all()) {
            assertTrue(ids.add(def.id()), "дубль ида: " + def.id());
            assertNotNull(def.metric(), "у " + def.id() + " нет метрики");
            assertTrue(def.goal() > 0, "у " + def.id() + " нулевой порог");
            assertTrue(def.passHours() >= 0 && def.passHours() <= 24, "награда не больше суток");
            assertTrue(def.diamonds() >= 0 && def.diamonds() <= 576);
        }
        assertNotNull(AchievementRegistry.byId("VETERAN"));
        assertNotNull(AchievementRegistry.byId("  legenda "));
        assertNull(AchievementRegistry.byId("nesushchestvuyushchee"));
        assertNull(AchievementRegistry.byId(null));
    }

    @Test
    void legendKeepsItsHistoricalGoalAndMasterNeedsEverything() {
        AchievementDef legend = AchievementRegistry.byId("legenda");
        assertNotNull(legend);
        assertEquals(AchievementMetric.ACHIEVEMENTS, legend.metric());
        assertEquals(AchievementRegistry.LEGEND_GOAL, legend.goal());
        assertEquals(47, legend.goal(), "рубеж «Легенды» не растёт вместе с каталогом");

        AchievementDef master = AchievementRegistry.byId("legenda-legend");
        assertNotNull(master);
        assertEquals(AchievementMetric.ACHIEVEMENTS, master.metric());
        assertEquals(AchievementRegistry.EXPECTED_SIZE - 1, master.goal());
        assertEquals(71, master.goal(), "«Легенда легенд» требует все остальные достижения");
    }

    @Test
    void onlyThreeAchievementsGrantBadges() {
        int badges = 0;
        for (AchievementDef def : AchievementRegistry.all()) {
            if (def.badge() != null) {
                badges++;
                assertTrue(def.id().equals("veteran") || def.id().equals("legenda")
                                || def.id().equals("legenda-legend"),
                        "неожиданный бейдж у " + def.id());
            }
        }
        assertEquals(3, badges);
    }

    @Test
    void everyAchievementHasNameAndDescriptionInMessages() {
        YamlConfiguration messages = loadMessages();
        for (AchievementDef def : AchievementRegistry.all()) {
            assertTrue(messages.isString("messages.ach." + def.id() + ".name"),
                    "нет имени для " + def.id());
            assertTrue(messages.isString("messages.ach." + def.id() + ".desc"),
                    "нет описания для " + def.id());
        }
        for (String badge : new String[]{"veteran", "legenda", "legenda-legend"}) {
            assertTrue(messages.isString("messages.badge." + badge),
                    "нет текста бейджа " + badge);
        }
    }

    @Test
    void thresholdsAreMonotonicInsideCategories() {
        // Внутри раздела одинаковые метрики растут: 100 → 1 000 → 5 000 и т.п.
        for (AchievementDef.Category category : AchievementDef.Category.values()) {
            var byMetric = new java.util.HashMap<AchievementMetric, Long>();
            for (AchievementDef def : AchievementRegistry.byCategory(category)) {
                Long previous = byMetric.put(def.metric(), def.goal());
                if (previous != null) {
                    assertTrue(def.goal() > previous,
                            "пороги " + def.metric().id() + " должны расти: " + category.id());
                }
            }
        }
    }

    private static YamlConfiguration loadMessages() {
        try (InputStream stream = AchievementRegistryTest.class
                .getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml не найден в ресурсах");
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
