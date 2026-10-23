package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Выбор титула за закрытые разделы коллекции (1.4.150). */
class TierCollectionTitlesTest {

    private static TierCollectionBook.Progress progress(int tiers, int properties, int affixes) {
        return new TierCollectionBook.Progress(tiers, properties, affixes);
    }

    @Test
    void noTitleWhileNothingIsClosed() {
        assertNull(TierCollectionTitles.best(progress(6, 104, 29)));
        assertNull(TierCollectionTitles.best(TierCollectionBook.Progress.EMPTY));
        assertNull(TierCollectionTitles.best(null));
    }

    @Test
    void tiersAloneAlreadyGiveTitle() {
        assertEquals("tiers", TierCollectionTitles.best(
                progress(TierDef.LADDER.size(), 0, 0)));
    }

    @Test
    void strongerSectionWins() {
        assertEquals("affixes", TierCollectionTitles.best(progress(
                TierDef.LADDER.size(), 0, TierCollectionConfig.AFFIX_TOTAL)));
        assertEquals("properties", TierCollectionTitles.best(progress(
                0, TierCollectionConfig.PROPERTY_TOTAL, TierCollectionConfig.AFFIX_TOTAL)),
                "свойства весомее аффиксов, пока не закрыта вся кузня");
        assertEquals("all", TierCollectionTitles.best(progress(
                TierCollectionBook.TIER_TOTAL, TierCollectionConfig.PROPERTY_TOTAL,
                TierCollectionConfig.AFFIX_TOTAL)));
    }

    @Test
    void nextIsTheClosestSection() {
        assertEquals("tiers", TierCollectionTitles.next(progress(6, 10, 20)),
                "6 из 7 ближе, чем 20 из 30 и 10 из 105");
        assertEquals("affixes", TierCollectionTitles.next(progress(2, 10, 29)));
        assertEquals("properties", TierCollectionTitles.next(progress(1, 104, 3)));
    }

    @Test
    void nextSkipsClosedSections() {
        assertEquals("affixes", TierCollectionTitles.next(progress(
                TierDef.LADDER.size(), 5, 29)));
        assertEquals("all", TierCollectionTitles.next(progress(
                TierCollectionBook.TIER_TOTAL, TierCollectionConfig.PROPERTY_TOTAL,
                TierCollectionConfig.AFFIX_TOTAL - 1)));
        assertNull(TierCollectionTitles.next(progress(
                TierCollectionBook.TIER_TOTAL, TierCollectionConfig.PROPERTY_TOTAL,
                TierCollectionConfig.AFFIX_TOTAL)), "всё закрыто — подсказки нет");
    }

    @Test
    void equalRatioPrefersTheSmallerThreshold() {
        // 1/7 и 15/105 — одинаковая доля; новичку полезнее цель из семи.
        assertEquals("tiers", TierCollectionTitles.next(progress(1, 15, 0)));
    }

    @Test
    void choiceIsNormalisedAndValidated() {
        assertNull(TierCollectionTitles.normalizeChoice(null));
        assertNull(TierCollectionTitles.normalizeChoice(" "));
        assertNull(TierCollectionTitles.normalizeChoice("auto"));
        assertNull(TierCollectionTitles.normalizeChoice("AUTO"));
        assertEquals("off", TierCollectionTitles.normalizeChoice("off"));
        assertEquals("all", TierCollectionTitles.normalizeChoice("ALL"));
        assertEquals("affixes", TierCollectionTitles.normalizeChoice(" affixes "));
        assertNull(TierCollectionTitles.normalizeChoice("mythic"), "битое значение — авто");
        assertTrue(TierCollectionTitles.isSection("properties"));
        assertFalse(TierCollectionTitles.isSection("auto"));
        assertFalse(TierCollectionTitles.isSection(null));
    }

    @Test
    void selectionRespectsChoice() {
        TierCollectionBook.Progress six = progress(6, 0, 0);
        assertEquals("tiers", TierCollectionTitles.selected(progress(7, 0, 0), null),
                "авто — лучший заработанный");
        assertNull(TierCollectionTitles.selected(progress(7, 0, 0), "off"),
                "off скрывает титул");
        assertEquals("tiers", TierCollectionTitles.selected(progress(7, 0, 0), "tiers"));
        assertEquals("tiers", TierCollectionTitles.selected(progress(
                        TierCollectionBook.TIER_TOTAL, TierCollectionConfig.PROPERTY_TOTAL,
                        TierCollectionConfig.AFFIX_TOTAL),
                "tiers"), "выбор игрока важнее автоматической «Легенды кузни»");
        assertNull(TierCollectionTitles.selected(six, "tiers"),
                "незакрытый раздел не надевается, но и титула ещё нет");
    }

    @Test
    void unearnedChoiceFallsBackToAuto() {
        // Раздел закрывался, но книгу откатили: выбор остался, заслуги нет —
        // показываем самый весомый из того, что действительно закрыто.
        TierCollectionBook.Progress onlyTiers = progress(TierDef.LADDER.size(), 0, 0);
        assertEquals("tiers", TierCollectionTitles.selected(onlyTiers, "all"));
        assertNull(TierCollectionTitles.selected(progress(3, 0, 0), "all"),
                "ничего не закрыто — титула нет вовсе");
    }

    @Test
    void nextHandlesEmptyBook() {
        String next = TierCollectionTitles.next(TierCollectionBook.Progress.EMPTY);
        assertEquals("tiers", next, "с нуля ближайшая цель — собрать все редкости");
        assertNull(TierCollectionTitles.next(null));
    }
}
