package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTextTest {

    @Test
    void liveTranslationWins() {
        assertEquals("Корень жизни", ArtifactText.orFallback("  Корень жизни  ", "koren-zhizni"));
        assertEquals("Опытные шары летят к карману.",
                ArtifactText.loreOrEmpty(" Опытные шары летят к карману. "));
    }

    @Test
    void blankNameFallsBackToReadableId() {
        assertEquals("Koren Zhizni", ArtifactText.orFallback("", "koren-zhizni"));
        assertEquals("Koren Zhizni", ArtifactText.orFallback(null, "koren-zhizni"));
        assertEquals("Koren Zhizni", ArtifactText.orFallback("   ", "koren-zhizni"));
    }

    @Test
    void humanizeHandlesSeparatorsAndCase() {
        assertEquals("Pero Feniksa", ArtifactText.humanize("pero-feniksa"));
        assertEquals("Klyuch Propasti", ArtifactText.humanize("klyuch_propasti"));
        assertEquals("Koltso Usmerti", ArtifactText.humanize("  koltso  usmerti  "));
        assertEquals("", ArtifactText.humanize(null));
        assertEquals("", ArtifactText.humanize("  "));
    }

    @Test
    void missingLoreMeansNoLoreLine() {
        assertEquals("", ArtifactText.loreOrEmpty(null));
        assertEquals("", ArtifactText.loreOrEmpty("   "));
        assertTrue(ArtifactText.loreOrEmpty("Тяжесть чужого горя в кармане.").startsWith("Тяжесть"));
    }

    @Test
    void romanLevelsCoverTheRegistry() {
        assertEquals("I", ActiveEffectsListener.roman(1));
        assertEquals("IV", ActiveEffectsListener.roman(4));
        assertEquals("X", ActiveEffectsListener.roman(10));
        assertEquals("12", ActiveEffectsListener.roman(12));
    }
}
