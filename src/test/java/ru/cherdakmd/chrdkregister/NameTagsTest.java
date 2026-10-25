package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Оформление ников от аддонов (API v6): порядок, замена по id и устойчивость к сбоям. */
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
