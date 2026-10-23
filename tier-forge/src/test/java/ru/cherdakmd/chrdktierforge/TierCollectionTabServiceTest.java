package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Правило «кто рисует имя в табе» и снятие разметки (1.4.152). */
class TierCollectionTabServiceTest {

    @Test
    void moduleRendersOnlyWhenCoreLeavesNamesAlone() {
        assertEquals(TierCollectionTabService.Mode.MODULE,
                TierCollectionTabService.decide(true, true, ""));
        assertEquals(TierCollectionTabService.Mode.MODULE,
                TierCollectionTabService.decide(true, true, null));
        assertEquals(TierCollectionTabService.Mode.MODULE,
                TierCollectionTabService.decide(true, true, "   "));
    }

    @Test
    void coreWithTaggedNameAlreadyCarriesTitle() {
        assertEquals(TierCollectionTabService.Mode.CORE,
                TierCollectionTabService.decide(true, true, "%player_tagged%"));
        assertEquals(TierCollectionTabService.Mode.CORE,
                TierCollectionTabService.decide(true, true,
                        "<gray>%player_tagged%</gray> <dark_gray>%ping% мс</dark_gray>"));
    }

    @Test
    void coreWithoutTaggedNameGetsWarningMode() {
        assertEquals(TierCollectionTabService.Mode.CORE_WITHOUT_TITLES,
                TierCollectionTabService.decide(true, true, "%player%"));
        assertEquals(TierCollectionTabService.Mode.CORE_WITHOUT_TITLES,
                TierCollectionTabService.decide(true, true, "<white>%player%</white>"));
    }

    @Test
    void switchesTurnEverythingOff() {
        assertEquals(TierCollectionTabService.Mode.OFF,
                TierCollectionTabService.decide(false, true, ""));
        assertEquals(TierCollectionTabService.Mode.OFF,
                TierCollectionTabService.decide(true, false, ""));
        assertEquals(TierCollectionTabService.Mode.OFF,
                TierCollectionTabService.decide(false, false, "%player%"));
    }

    @Test
    void tagsAreStrippedForEmptinessCheck() {
        assertEquals("Кузнец", TierCollectionTabService.stripTags("<gold>Кузнец</gold>"));
        assertEquals("Легенда кузни",
                TierCollectionTabService.stripTags("<light_purple><bold>Легенда кузни</bold></light_purple>"));
        assertEquals("", TierCollectionTabService.stripTags("<gold></gold>"));
        assertEquals("", TierCollectionTabService.stripTags(null));
        assertEquals("", TierCollectionTabService.stripTags(""));
    }
}
