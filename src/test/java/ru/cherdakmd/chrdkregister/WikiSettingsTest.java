package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ссылка на вики: адрес попадает внутрь MiniMessage-тега, поэтому проверяется
 * строже обычного текста из конфига. Тесты фиксируют три вещи: битый адрес
 * выключает ссылку, слаг не может вывести за пределы сайта, а страница
 * собирается только из разрешённых символов.
 */
class WikiSettingsTest {

    @Test
    void stockUrlIsUsableAndKeepsTrailingSlash() {
        WikiSettings wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of());
        assertTrue(wiki.usable());
        assertEquals("https://cherdakmd.github.io/CHRDK_F_REGISTER/", wiki.url());
        assertEquals("https://cherdakmd.github.io/CHRDK_F_REGISTER/ranks.html", wiki.pageUrl("ranks"));
        assertEquals(wiki.url(), wiki.pageUrl(""), "без слага — главная");
    }

    @Test
    void missingTrailingSlashIsAddedOnce() {
        WikiSettings wiki = WikiSettings.of(true, "https://example.org/wiki", List.of());
        assertEquals("https://example.org/wiki/", wiki.url());
        // Двойного слэша быть не должно, иначе адрес страницы станет //ranks.html.
        assertEquals("https://example.org/wiki/ranks.html", wiki.pageUrl("ranks"));
    }

    @Test
    void brokenUrlDisablesTheLinkInsteadOfShowingGarbage() {
        for (String broken : List.of("", "   ", "javascript:alert(1)", "ftp://example.org/",
                "https://", "https://example.org/a b", "https://example.org/'quote'",
                "https://example.org/<tag>", "https://example.org/back\\slash",
                "example.org/wiki")) {
            WikiSettings wiki = WikiSettings.of(true, broken, List.of());
            assertFalse(wiki.usable(), "должен отказать: " + broken);
            assertEquals("", wiki.url(), "пустой адрес: " + broken);
            assertEquals("", wiki.pageUrl("ranks"), "адрес страницы: " + broken);
        }
    }

    @Test
    void httpIsAllowedForLocalTestingButSchemeIsRequired() {
        assertTrue(WikiSettings.of(true, "http://127.0.0.1:8000/", List.of()).usable());
        assertFalse(WikiSettings.of(true, "//example.org/wiki", List.of()).usable());
    }

    @Test
    void disabledFlagWinsOverAValidUrl() {
        WikiSettings wiki = WikiSettings.of(false, WikiSettings.DEFAULT_URL, List.of());
        assertFalse(wiki.usable());
        assertEquals("", wiki.pageUrl("ranks"));
    }

    @Test
    void slugCannotEscapeTheSite() {
        WikiSettings wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of());
        for (String bad : List.of("../secret", "ranks/extra", "ranks.html", "РАНГИ", "ranks?x=1",
                "ranks#top", "-ranks", "ranks-", "a".repeat(WikiSettings.MAX_SLUG_LENGTH + 1))) {
            assertFalse(WikiSettings.validSlug(bad), "слаг отклонён: " + bad);
            assertEquals(wiki.url(), wiki.pageUrl(bad), "битый слаг ведёт на главную: " + bad);
        }
        for (String good : List.of("ranks", "start", "menu", "daily-rewards", "faq")) {
            assertTrue(WikiSettings.validSlug(good), "слаг принят: " + good);
            assertEquals(wiki.url() + good + ".html", wiki.pageUrl(good));
        }
    }

    @Test
    void pagesAreNormalizedAndCapped() {
        WikiSettings wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL,
                List.of("RANKS", " ranks ", "плохо", "", "faq", "ranks", "-x"));
        assertEquals(List.of("ranks", "faq"), wiki.pages(), "регистр, повторы и мусор убраны");
        assertEquals(WikiSettings.MAX_PAGES,
                WikiSettings.of(true, WikiSettings.DEFAULT_URL,
                        java.util.stream.IntStream.range(0, 40).mapToObj(i -> "p" + i).toList()).pages().size());
    }

    @Test
    void emptyPagesFallBackToTheInGameShortlist() {
        assertEquals(WikiSettings.DEFAULT_PAGES, WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of()).pages());
        assertEquals(WikiSettings.DEFAULT_PAGES, WikiSettings.of(true, WikiSettings.DEFAULT_URL, null).pages());
    }
}
