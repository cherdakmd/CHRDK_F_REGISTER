package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звание рядом с ником (1.4.166): порог, выбор игрока и режим таба.
 *
 * <p>Без сервера: правило показа — чистая функция, а режим таба считается по
 * строке формата ядра. Именно эти две вещи решают, увидит ли игрок своё звание
 * в чате и в списке игроков.</p>
 */
class TreasureRankTagsTest {

    @Test
    void autoFollowsServerDefault() {
        assertFalse(TreasureRankTags.shows(true, false, TreasureRank.SEEKER,
                TreasureRank.LEGEND, TreasureRankTags.Choice.AUTO), "авто при default=false");
        assertTrue(TreasureRankTags.shows(true, true, TreasureRank.SEEKER,
                TreasureRank.LEGEND, null), "не выбирал — работает умолчание");
    }

    @Test
    void playerChoiceWinsOverServerDefault() {
        assertTrue(TreasureRankTags.shows(true, false, TreasureRank.SEEKER,
                TreasureRank.LEGEND, TreasureRankTags.Choice.ON), "игрок включил сам");
        assertFalse(TreasureRankTags.shows(true, true, TreasureRank.SEEKER,
                TreasureRank.MASTER, TreasureRankTags.Choice.OFF), "игрок скрыл сам");
    }

    @Test
    void rankBelowThresholdIsNeverShown() {
        for (TreasureRankTags.Choice choice : TreasureRankTags.Choice.values()) {
            assertFalse(TreasureRankTags.shows(true, true, TreasureRank.SEEKER,
                    TreasureRank.NOVICE, choice), "новичок не получает звание: " + choice);
            assertTrue(TreasureRankTags.shows(true, true, TreasureRank.SEEKER,
                    TreasureRank.SEEKER, TreasureRankTags.Choice.ON), "порог включается");
        }
    }

    @Test
    void disabledSwitchHidesEverything() {
        for (TreasureRankTags.Choice choice : TreasureRankTags.Choice.values()) {
            assertFalse(TreasureRankTags.shows(false, true, TreasureRank.NOVICE,
                    TreasureRank.LEGEND, choice), "выключатель главнее выбора: " + choice);
        }
        assertFalse(TreasureRankTags.shows(true, true, TreasureRank.SEEKER, null,
                TreasureRankTags.Choice.ON), "без звания показывать нечего");
    }

    @Test
    void choiceCyclesThroughThreeStates() {
        assertEquals(TreasureRankTags.Choice.ON, TreasureRankTags.Choice.AUTO.next());
        assertEquals(TreasureRankTags.Choice.OFF, TreasureRankTags.Choice.ON.next());
        assertEquals(TreasureRankTags.Choice.AUTO, TreasureRankTags.Choice.OFF.next());
        assertEquals("auto", TreasureRankTags.Choice.AUTO.id());
    }

    @Test
    void choiceParsingIsForgiving() {
        assertEquals(TreasureRankTags.Choice.OFF, TreasureRankTags.Choice.byId(" OFF "));
        assertEquals(TreasureRankTags.Choice.ON, TreasureRankTags.Choice.byId("On"));
        assertNull(TreasureRankTags.Choice.byId("нет-такого"));
        assertNull(TreasureRankTags.Choice.byId("  "));
        assertNull(TreasureRankTags.Choice.byId(null));
    }

    @Test
    void tabModeDependsOnCoreFormat() {
        assertEquals(TreasureRankTagService.Mode.MODULE,
                TreasureRankTagService.decide(true, true, ""), "пустой формат ядра — рисуем сами");
        assertEquals(TreasureRankTagService.Mode.CORE,
                TreasureRankTagService.decide(true, true, "<white>%player_tagged%</white>"));
        assertEquals(TreasureRankTagService.Mode.CORE_WITHOUT_TAG,
                TreasureRankTagService.decide(true, true, "<white>%player%</white>"));
        assertEquals(TreasureRankTagService.Mode.OFF,
                TreasureRankTagService.decide(false, true, ""));
        assertEquals(TreasureRankTagService.Mode.OFF,
                TreasureRankTagService.decide(true, false, ""));
        assertEquals(TreasureRankTagService.Mode.MODULE,
                TreasureRankTagService.decide(true, true, null));
    }

    @Test
    void defaultsShowBadgeFromSeeker() {
        TreasuresConfig.RankTag defaults = TreasuresConfig.defaultRankTag();
        assertTrue(defaults.enabled());
        assertTrue(defaults.defaultShown());
        assertTrue(defaults.tab());
        assertEquals(TreasureRank.SEEKER, defaults.minRank());
        assertEquals(60, defaults.tabRefreshTicks());
    }

    @Test
    void shippedConfigMatchesDefaultsAndIsClamped() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var stream = TreasureRankTagsTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml не найден в ресурсах");
            try (var reader = new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                shipped = YamlConfiguration.loadConfiguration(reader);
            }
        }
        TreasuresConfig parsed = TreasuresConfig.parse(shipped, java.util.logging.Logger
                .getLogger("TreasureRankTagsTest"));
        assertEquals(TreasuresConfig.defaultRankTag(), parsed.rankTag(),
                "секция rank-tag в config.yml разошлась с умолчаниями кода");

        YamlConfiguration broken = new YamlConfiguration();
        broken.set("rank-tag.min-rank", "нет-такого");
        broken.set("rank-tag.tab.refresh-ticks", 1);
        TreasuresConfig.RankTag fixed = TreasuresConfig.parse(broken,
                java.util.logging.Logger.getLogger("TreasureRankTagsTest")).rankTag();
        assertEquals(TreasureRank.SEEKER, fixed.minRank(), "битый порог → «Искатель»");
        assertEquals(20, fixed.tabRefreshTicks(), "период не чаще раза в секунду");

        broken.set("rank-tag.tab.refresh-ticks", 99_999);
        assertEquals(1200, TreasuresConfig.parse(broken,
                java.util.logging.Logger.getLogger("TreasureRankTagsTest"))
                .rankTag().tabRefreshTicks());
    }

    @Test
    void settingsHonourRankAndChoice() {
        TreasuresConfig.RankTag settings = new TreasuresConfig.RankTag(
                true, true, TreasureRank.TREASURE_HUNTER, true, 60);
        assertFalse(settings.shows(TreasureRank.SEEKER, TreasureRankTags.Choice.ON),
                "ниже порога даже «включено» не помогает");
        assertTrue(settings.shows(TreasureRank.TREASURE_HUNTER, TreasureRankTags.Choice.AUTO));
        assertFalse(settings.shows(TreasureRank.LEGEND, TreasureRankTags.Choice.OFF));
    }
}
