package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Подпись стримера: жирным становится ник, а не тег каталога; метка не ломает разметку. */
class StreamerTagsTest {

    @Test
    void boldAppliesToNickOnly() {
        assertEquals("<gold>[Меценат] </gold><bold>Steve</bold>",
                StreamerTags.boldName("<gold>[Меценат] </gold>Steve", "Steve"));
    }

    @Test
    void markupAddsBracketTagAfterNick() {
        assertEquals("<bold>Steve</bold> <red>[СТРИМ]</red>",
                StreamerTags.markup("Steve", "Steve", "СТРИМ", "red"));
    }

    @Test
    void markupKeepsDecoratedPrefix() {
        assertEquals("<gold>[Меценат] </gold><bold>Steve</bold> <red>[СТРИМ]</red>",
                StreamerTags.markup("<gold>[Меценат] </gold>Steve", "Steve", "СТРИМ", "red"));
    }

    @Test
    void missingNickFallsBackToWholeName() {
        assertEquals("<bold><gold>[Меценат] </gold>Steve</bold> <red>[СТРИМ]</red>",
                StreamerTags.markup("<gold>[Меценат] </gold>Steve", "", "СТРИМ", "red"));
    }

    @Test
    void labelIsSanitized() {
        assertEquals("СТРИМ", StreamerTags.safeLabel(null));
        assertEquals("СТРИМ", StreamerTags.safeLabel("<red>  </red>"));
        assertEquals("LIVE", StreamerTags.safeLabel("LIVE <red>"));
        assertTrue(StreamerTags.safeLabel("ОЧЕНЬДЛИННОЕИМЯМЕТКИ").length() <= 16);
    }

    @Test
    void unsafeColorFallsBackToRed() {
        assertEquals("<bold>Steve</bold> <red>[СТРИМ]</red>",
                StreamerTags.markup("Steve", "Steve", "СТРИМ", "<script>"));
        assertEquals("red", StreamerTags.blinkColor(true, "red; click", "dark_gray"));
        assertEquals("<bold>Steve</bold> <#ff8800>[СТРИМ]</#ff8800>",
                StreamerTags.markup("Steve", "Steve", "СТРИМ", "#FF8800"));
        assertEquals("dark_gray", StreamerTags.safeColor("dark_gray"));
        assertEquals("red", StreamerTags.safeColor("notacolor"));
    }

    @Test
    void blinkAlternatesColors() {
        assertEquals("red", StreamerTags.blinkColor(true, "red", "dark_gray"));
        assertEquals("dark_gray", StreamerTags.blinkColor(false, "red", "dark_gray"));
        assertEquals("red", StreamerTags.blinkColor(true, "", ""));
        assertEquals("dark_gray", StreamerTags.blinkColor(false, "", ""));
    }
}
