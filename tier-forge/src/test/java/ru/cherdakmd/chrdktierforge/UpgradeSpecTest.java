package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор уровней улучшений для админской выдачи (1.4.96). */
class UpgradeSpecTest {

    @Test
    void parsesPlainList() {
        UpgradeSpec.Result result = UpgradeSpec.parse("edge:3,crit:2");
        assertFalse(result.hasRejected());
        assertEquals(Map.of(
                ForgeUpgrade.byId("edge"), 3,
                ForgeUpgrade.byId("critical"), 2), result.upgrades());
    }

    @Test
    void clampsLevelsAndKeepsMaximumForRepeats() {
        UpgradeSpec.Result result = UpgradeSpec.parse("edge:99,EDGE:2,crit:0");
        assertFalse(result.hasRejected());
        assertEquals(5, result.upgrades().get(ForgeUpgrade.byId("edge")),
                "уровень зажат в maxLevel и повтор не понижает его");
        assertEquals(1, result.upgrades().get(ForgeUpgrade.byId("critical")),
                "нулевой уровень поднимается до I");
    }

    @Test
    void rejectsUnknownAndMalformedTokens() {
        UpgradeSpec.Result result = UpgradeSpec.parse("edge:2,unknown:1,edge-x,crit:many");
        assertEquals(1, result.upgrades().size(), "корректный токен всё равно разобран");
        assertEquals(3, result.rejected().size());
        assertTrue(result.rejected().contains("unknown:1"));
        assertTrue(result.rejected().contains("edge-x"));
        assertTrue(result.rejected().contains("crit:many"));
    }

    @Test
    void blankAndDashMeanEmptySpec() {
        assertTrue(UpgradeSpec.parse(null).isEmpty());
        assertTrue(UpgradeSpec.parse("  ").isEmpty());
        assertTrue(UpgradeSpec.parse("-").isEmpty());
        assertFalse(UpgradeSpec.parse("-").hasRejected(), "прочерк — не опечатка");

        UpgradeSpec.Result parsed = UpgradeSpec.parse("edge:1");
        assertEquals("Кромка I", UpgradeSpec.describe(parsed.upgrades()), "список для сообщения");
        assertEquals("", UpgradeSpec.describe(Map.of()));
    }
}
