package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Регрессии реального форматтера плитки цвета и статусов ограниченных меток. */
class LabelsStyleMenuServiceTest {
    private Messages messages() throws Exception {
        try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/messages.yml"))) {
            return Messages.from(stream, null, Logger.getAnonymousLogger());
        }
    }

    @Test
    void colorTileNeverContainsComponentDebugRepresentation() throws Exception {
        Messages messages = messages();
        var color = new LabelPalette.Color("aqua", "<aqua>Бирюзовый</aqua>", "<aqua>%name%</aqua>", 1, "");
        assertEquals("Бирюзовый · выбран", plain(LabelsStyleMenuService.colorName(messages, color, true, true)));
        assertEquals("Бирюзовый · закрыт", plain(LabelsStyleMenuService.colorName(messages, color, false, false)));
        assertEquals("Бирюзовый · закрыт", plain(LabelsStyleMenuService.colorName(messages, color, false, true)),
                "недоступный сохранённый цвет не помечается выбранным после понижения ранга");
        assertEquals("Бирюзовый", plain(LabelsStyleMenuService.colorName(messages, color, true, false)).strip());
    }

    @Test
    void statusesDistinguishOverflowHiddenAndUnavailable(@TempDir Path dir) throws Exception {
        var prefs = new LabelPreferences(dir, Logger.getAnonymousLogger());
        prefs.load();
        var rank = LabelPreferences.Label.RANK;
        var guild = LabelPreferences.Label.GUILDS;
        var classes = LabelPreferences.Label.CLASSES;
        var selection = new LabelStyleService.Selection(
                List.of(new LabelStyleService.Decoration(rank, "Ранг")),
                List.of(new LabelStyleService.Decoration(guild, "Гильдия")), 1);
        Messages messages = messages();
        assertEquals(messages.raw("labels.value-on"), LabelsMenuService.stateOf(messages, prefs, "Steve", rank, selection).text());
        assertEquals(messages.raw("labels.value-waiting"), LabelsMenuService.stateOf(messages, prefs, "Steve", guild, selection).text());
        assertEquals(messages.raw("labels.value-unavailable"), LabelsMenuService.stateOf(messages, prefs, "Steve", classes, selection).text());
        prefs.toggle("Steve", guild);
        assertEquals(messages.raw("labels.value-off"), LabelsMenuService.stateOf(messages, prefs, "Steve", guild, selection).text());
        assertEquals(messages.raw("labels.value-on"), LabelsMenuService.stateOf(messages, prefs, "Steve", LabelPreferences.Label.HEADER, selection).text());
        assertEquals("После ника: 1 / 1 · не поместилось: 1", plain(LabelsMenuService.summary(messages, selection)));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }
}
