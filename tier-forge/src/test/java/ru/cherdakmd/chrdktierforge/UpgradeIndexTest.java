package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Битовая карта улучшений (1.4.82): 85 свойств в два long-слова. */
class UpgradeIndexTest {

    @Test
    void wordsCoverAllEightyFiveUpgrades() {
        assertEquals(2, UpgradeIndex.WORDS);
        assertEquals(85, ForgeUpgrade.ALL.size());
        assertTrue(UpgradeIndex.WORDS * 64 >= ForgeUpgrade.ALL.size());
    }

    @Test
    void bitRoundTripAcrossWordBoundary() {
        long[] words = UpgradeIndex.empty();
        assertTrue(UpgradeIndex.set(words, 0));
        assertTrue(UpgradeIndex.set(words, 63));
        assertTrue(UpgradeIndex.set(words, 64));
        assertTrue(UpgradeIndex.set(words, 84));

        assertTrue(UpgradeIndex.has(words, 0));
        assertTrue(UpgradeIndex.has(words, 63));
        assertTrue(UpgradeIndex.has(words, 64));
        assertTrue(UpgradeIndex.has(words, 84));
        assertFalse(UpgradeIndex.has(words, 1));
        assertFalse(UpgradeIndex.has(words, 62));
        assertFalse(UpgradeIndex.has(words, 65));
        assertTrue(UpgradeIndex.any(words));
    }

    @Test
    void outOfRangeBitsAreIgnored() {
        long[] words = UpgradeIndex.empty();
        assertFalse(UpgradeIndex.set(words, -1));
        assertFalse(UpgradeIndex.set(words, ForgeUpgrade.ALL.size()));
        assertFalse(UpgradeIndex.set(null, 0));
        assertFalse(UpgradeIndex.has(words, -1));
        assertFalse(UpgradeIndex.has(words, ForgeUpgrade.ALL.size()));
        assertFalse(UpgradeIndex.any(words));
    }

    @Test
    void shortOrNullArraysAreSafe() {
        assertFalse(UpgradeIndex.has(new long[1], 64), "старый/урезанный массив не должен падать");
        assertFalse(UpgradeIndex.has(null, 0));
        assertFalse(UpgradeIndex.any(null));
        assertTrue(UpgradeIndex.any(new long[]{0L, 1L}));
    }
}
