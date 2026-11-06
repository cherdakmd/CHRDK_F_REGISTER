package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** В сообщество уходит только безопасный ник и обрезанный нейтральный текст. */
class SocialEventTextTest {

    @Test
    void playerNameIsSanitizedAndTruncated() {
        assertEquals("Steve", SocialEventText.safePlayer("Steve"));
        assertEquals("redStevered", SocialEventText.safePlayer("<red>Steve</red>"));
        assertEquals("tab_warns", SocialEventText.safePlayer("%tab_warns%"));
        assertEquals("", SocialEventText.safePlayer(null));
        assertEquals("", SocialEventText.safePlayer("   "));
        assertEquals("ABCDEFGHIJKLMNOP",
                SocialEventText.safePlayer("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
    }

    @Test
    void longTextIsTrimmedButNeverEmpty() {
        assertEquals("💀 Steve погиб", SocialEventText.limit("  💀 Steve погиб  ", 220));
        assertEquals("", SocialEventText.limit(null, 220));
        assertEquals("", SocialEventText.limit("   ", 220));
        assertEquals("abc…", SocialEventText.limit("abcdef", 4));
        assertEquals("abcdef", SocialEventText.limit("abcdef", 0), "нулевой лимит = не обрезаем");
    }

    @Test
    void privateFieldsAreNeverPartOfTheTemplate() {
        // Шаблон смерти содержит только ник: ни причины, ни координат, ни UUID.
        String text = "💀 %player% погиб на сервере CHERDAK."
                .replace("%player%", SocialEventText.safePlayer("Steve"));
        assertFalse(text.contains("%"));
        assertFalse(text.contains("<"));
        assertFalse(text.contains("uid"));
        assertFalse(text.contains("x="));
    }
}
