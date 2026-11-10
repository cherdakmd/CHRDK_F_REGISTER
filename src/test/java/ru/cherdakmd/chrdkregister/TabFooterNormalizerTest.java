package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Рантайм-склейка старого footer без перезаписи config.yml владельца. */
class TabFooterNormalizerTest {

    private static final String OLD_STATUS = "<gold>%chrdksocial_status%</gold>";
    private static final String OLD_COUNTERS =
            "<gray>Привязка <green>%chrdksocial_link%</green> <dark_gray>│</dark_gray> "
                    + "Проход <aqua>%chrdksocial_access%</aqua></gray>";

    @Test
    void twoLegacySocialLinesBecomeOneWholeLine() {
        List<String> out = TabFooterNormalizer.normalizeFooter(List.of(
                "", OLD_STATUS, OLD_COUNTERS, "<gray>%auth_hint%</gray>", ""));
        assertEquals(1, out.stream().filter(line -> line.contains("%chrdksocial_tab%")).count());
        assertFalse(out.stream().anyMatch(line -> line.contains("%chrdksocial_status%")
                || line.contains("%chrdksocial_link%") || line.contains("%chrdksocial_access%")));
        assertTrue(out.contains("<gray>%auth_hint%</gray>"), "остальные строки сохраняются");
        assertEquals("", out.get(0));
        assertEquals("", out.get(out.size() - 1));
    }

    @Test
    void canonicalLineWinsAndDuplicateSocialLinesAreDropped() {
        assertEquals(List.of("%chrdksocial_tab%"), TabFooterNormalizer.normalizeFooter(
                List.of("%chrdksocial_tab%", OLD_STATUS, "%chrdksocial_tab%")));
        assertEquals(List.of("%chrdksocial_tab%"),
                TabFooterNormalizer.normalizeFooter(List.of("%chrdksocial_tab%", "%chrdksocial_tab%")));
        assertEquals(List.of("%chrdksocial_tab%"), TabFooterNormalizer.normalizeFooter(
                TabFooterNormalizer.normalizeFooter(List.of(OLD_STATUS, OLD_COUNTERS))));
    }

    @Test
    void onlyStandaloneHintLineIsRecognized() {
        assertTrue(TabFooterNormalizer.isAuthHintOnlyLine("<gray>%auth_hint%</gray>"));
        assertTrue(TabFooterNormalizer.isAuthHintOnlyLine("&7%auth_hint%"));
        assertFalse(TabFooterNormalizer.isAuthHintOnlyLine(
                "<gray>%auth_hint% │ 2FA: %security%</gray>"));
        assertFalse(TabFooterNormalizer.isAuthHintOnlyLine("<gray>Всего варнов: %tab_warns%</gray>"));
    }

    @Test
    void legacyInGameHintIsRedundantButUsefulHintStays() {
        assertTrue(TabFooterNormalizer.isRedundantAuthenticatedHint(
                new PresentationContext("", "в игре", "Вы в игре. Исследуйте мир", "включена", "")));
        assertFalse(TabFooterNormalizer.isRedundantAuthenticatedHint(
                new PresentationContext("", "в игре", "Откройте /menu", "включена", "")));
        assertFalse(TabFooterNormalizer.isRedundantAuthenticatedHint(
                new PresentationContext("", "вход", "Вы в игре", "включена", "")));
    }

    @Test
    void unresolvedSocialPlaceholderBecomesHiddenLine() {
        String hidden = TabFooterNormalizer.hideUnresolvedSocialLine("%chrdksocial_tab%");
        assertEquals(TabFooterNormalizer.HIDE_LINE, hidden);
        assertEquals("обычная строка",
                TabFooterNormalizer.hideUnresolvedSocialLine("обычная строка"));
        assertEquals(TabFooterNormalizer.HIDE_LINE,
                TabListService.resolveExternalPlaceholders("%chrdksocial_tab%", null));
    }
}
