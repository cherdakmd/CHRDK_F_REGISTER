package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class CheckedMessagesTest {
    @TempDir Path dir;
    private static final String DEFAULTS = "demo:\n  hello: '<green>Hello %name%</green>'\n  list: ['one', 'two']\n";
    private InputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private Messages load(String raw) throws Exception {
        Path file = Files.writeString(dir.resolve("messages.yml"), raw);
        return Messages.fromChecked(stream(DEFAULTS), file, Logger.getAnonymousLogger());
    }
    @Test void partialOverridesKeepIndividualDefaultsAndLegacyColors() throws Exception {
        Messages messages = load("demo:\n  hello: '&aПривет, %name%&r'\n");
        assertEquals("Привет, <red>Player", messages.plain("hello", "name", "<red>Player"));
        assertEquals(2, messages.getList("list").size());
    }
    @Test void badYamlAndWrongMessageTypesAreNotSilentlyFlattened() {
        for (String raw : new String[]{"demo: [broken", "demo: {hello: true}", "demo: {hello: 12}", "demo: {list: [one, 2]}", "demo: wrong"})
            assertThrows(InvalidConfigurationException.class, () -> load(raw), raw);
    }
    @Test void unknownOldKeysEmptyOverridesAndMultilineTextRemainValid() throws Exception {
        assertEquals("Hello X", load("{}").plain("hello", "name", "X"));
        Messages messages = load("demo:\n  old-key: old\n  list: |-\n    first\n    second\n");
        assertTrue(messages.has("old-key")); assertEquals(2, messages.getList("list").size());
    }
    @Test void flatOverridesRemainCompatibleWithSingleModuleEnvelope() throws Exception {
        assertEquals("Flat X", load("hello: 'Flat %name%'\n").plain("hello", "name", "X"));
    }
    @Test void missingOrUnreadableFilesAndResourcesDoNotCreateReplacements() {
        Path file = dir.resolve("missing.yml");
        assertThrows(IOException.class, () -> Messages.fromChecked(stream(DEFAULTS), file, Logger.getAnonymousLogger()));
        assertFalse(Files.exists(file));
        assertThrows(IOException.class, () -> Messages.fromChecked(null, null, Logger.getAnonymousLogger()));
        assertThrows(InvalidConfigurationException.class, () -> Messages.fromChecked(stream("[broken"), null, Logger.getAnonymousLogger()));
    }
    @Test void readOnlyPreparationDoesNotModifyCustomFile() throws Exception {
        String raw = "# owner comment\ndemo:\n  hello: Custom\n"; load(raw);
        assertEquals(raw, Files.readString(dir.resolve("messages.yml")));
    }
}
