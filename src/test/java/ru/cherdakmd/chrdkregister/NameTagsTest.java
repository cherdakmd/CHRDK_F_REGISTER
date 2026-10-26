package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Оформление ников от аддонов (API v6): порядок, замена по id и устойчивость к
 * сбоям. С 1.4.186 — ещё и фильтр по id: выключенная игроком надпись просто не
 * применяется.
 */
class NameTagsTest {

    private static final Logger LOGGER = Logger.getLogger("NameTagsTest");

    @Test
    void providersApplyInRegistrationOrder() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("streams", (name, decorated) -> decorated + " [СТРИМ]");
        tags.register("extra", (name, decorated) -> "<bold>" + decorated + "</bold>");

        assertEquals("<bold>Steve [СТРИМ]</bold>", tags.decorate("Steve", "Steve"));
    }

    @Test
    void filterSkipsDisabledProviders() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("streams", (name, decorated) -> decorated + " [СТРИМ]");
        tags.register("guilds", (name, decorated) -> decorated + " <gray>[ГИ]</gray>");
        tags.register("achievements", (name, decorated) -> decorated + " ★");

        assertEquals("Steve ★", tags.decorate("Steve", "Steve",
                        id -> !id.equals("streams") && !id.equals("guilds")),
                "скрытые метки не попадают в ник, порядок остальных сохраняется");
        assertEquals("Steve", tags.decorate("Steve", "Steve", id -> false),
                "всё выключено — имя как есть");
        assertEquals("Steve [СТРИМ] <gray>[ГИ]</gray> ★",
                tags.decorate("Steve", "Steve", null),
                "без фильтра работает как раньше");
    }

    @Test
    void sameIdReplacesPreviousProvider() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("streams", (name, decorated) -> "первый");
        tags.register("streams", (name, decorated) -> "второй");

        assertEquals(List.of("streams"), tags.ids());
        assertEquals("второй", tags.decorate("Steve", "Steve"));
    }

    @Test
    void unregisterRestoresPlainName() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("streams", (name, decorated) -> decorated + " [СТРИМ]");
        assertTrue(tags.unregister("streams"));
        assertFalse(tags.unregister("streams"));

        assertEquals("Steve", tags.decorate("Steve", "Steve"));
    }

    @Test
    void brokenProviderDoesNotBreakChat() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("bad", (name, decorated) -> {
            throw new IllegalStateException("boom");
        });
        tags.register("good", (name, decorated) -> decorated + "!");

        assertEquals("Steve!", tags.decorate("Steve", "Steve"),
                "сбой одного провайдера не отменяет остальные");
    }

    @Test
    void nullProviderAndBlankIdAreIgnored() {
        NameTags tags = new NameTags(LOGGER);
        tags.register(null, (name, decorated) -> "x");
        tags.register("  ", (name, decorated) -> "x");
        tags.register("ok", null);

        assertEquals(List.of(), tags.ids());
        assertEquals("Steve", tags.decorate("Steve", "Steve"));
    }

    @Test
    void providerMayKeepNameUnchanged() {
        NameTags tags = new NameTags(LOGGER);
        tags.register("streams", (name, decorated) -> null);

        assertEquals("Steve", tags.decorate("Steve", "Steve"));
    }
}
