package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Счётчик варнов в TAB виден всем и не остаётся сырым %tab_ токеном. */
class ModerationTabTest {

    @Test
    void shortContextCarriesWarnCounterForMessagesFill() {
        var context = new TabListService.TabContext("Alex", 1, 20, 20.0, 5, "world", "", "", 0);
        Object[] values = context.placeholders();
        String found = null;
        for (int i = 0; i + 1 < values.length; i++) {
            if ("tab_warns".equals(values[i])) found = String.valueOf(values[i + 1]);
        }
        assertEquals("0/5", found, "даже без ModerationService подвал не показывает сырой токен");
        String publicFrame = null;
        for (int i = 0; i + 1 < values.length; i++) {
            if ("tab_warns_public".equals(values[i])) publicFrame = String.valueOf(values[i + 1]);
        }
        assertEquals("", publicFrame, "публичный кадр по умолчанию пуст");
    }

    @Test
    void warnsAreMaskedForPapiAndRegisteredInExpansion() {
        assertTrue(TabPlaceholderIntegration.isTabKey("warns"));
        assertTrue(TabPlaceholderIntegration.isTabKey("warns_public"));
        assertFalse(TabPlaceholderIntegration.isTabKey("warns_foreign"));
        String masked = TabListService.maskTabTokens("%tab_warns% %tab_warns_public%");
        assertFalse(masked.contains("%"), "оба кадра защищены от PAPI");
        assertEquals("%tab_warns% %tab_warns_public%", TabListService.unmaskTabTokens(masked));
    }

    @Test
    void publicFrameIsTheSameForEveryoneAndKeepsReasonsPrivate() {
        ModerationLedger ledger = new ModerationLedger();
        for (int i = 0; i < 3; i++) ledger.issueWarn("Ivan", "Mod", "секретная причина", 10L + i);
        for (int i = 0; i < 2; i++) ledger.issueWarn("Petr", "Mod", "другая причина", 20L + i);
        var off = new ModerationConfig(true, 5, "", true, false, 3, 500, java.util.List.of());
        var on = new ModerationConfig(true, 5, "", true, true, 3, 500, java.util.List.of());
        var disabled = new ModerationService(null, ledger, null, off);
        var service = new ModerationService(null, ledger, null, on);
        assertEquals("", disabled.warnsPublicText(), "флаг выключен — кадра нет");
        var globallyOff = new ModerationConfig(false, 5, "", true, true, 3, 500,
                java.util.List.of());
        assertEquals("", new ModerationService(null, ledger, null, globallyOff)
                        .warnsPublicText(), "moderation.enabled=false глушит и публичный кадр");
        String frame = service.warnsPublicText();
        assertEquals("⚠ Ivan 3/5, Petr 2/5", frame);
        assertFalse(frame.contains("секретная"), "причины не уходят в TAB");
        assertFalse(frame.contains("Mod"), "авторы выдачи не уходят в TAB");

        var expansion = new TabPlaceholderIntegration("test", null, null, null, service);
        assertEquals(frame, expansion.onRequest(null, "warns_public"),
                "голограмма и оффлайн-запрос видят тот же кадр");
        assertEquals("3/5", service.warnText("Ivan"), "личный счётчик не отменён");
        assertEquals("0/5", service.warnText("Alex"));
    }

    @Test
    void stockFooterShowsWarnsToEveryone() throws Exception {
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        String footer = String.join("\n", config.getStringList("tablist.footer"));
        assertTrue(footer.contains("%tab_warns%"), "в подвале TAB нет личного счётчика варнов");
        assertTrue(footer.contains("%tab_warns_public%"), "в подвале TAB нет публичного кадра");
        assertTrue(config.getBoolean("moderation.enabled"));
        assertEquals(5, config.getInt("moderation.warn-limit"));
        assertTrue(config.getBoolean("moderation.warns-in-tab"));
        assertFalse(config.getBoolean("moderation.warns-public-in-tab"));
        assertEquals(3, config.getInt("moderation.warns-public-limit"));
    }
}
