package ru.cherdakmd.chrdkhunt;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HuntReloadTest {
    @TempDir Path dir;
    private InputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void shippedShapesAndTextsLoadWithoutDependingOnLiveGuiRegistries() throws Exception {
        Path file = dir.resolve("config.yml");
        try (var resource = getClass().getResourceAsStream("/config.yml")) { Files.copy(resource, file); }
        // Полный HuntConfig использует Material.isItem — здесь проверяется весь YAML/schema,
        // настоящий parser проверен ниже на конфигурации без GUI-иконок и в обычном HuntConfigTest.
        var candidate = ConfigSnapshot.load(file, getClass().getResourceAsStream("/config.yml"), (yaml, log) -> yaml.getBoolean("general.enabled"),
                (yaml, log) -> Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, log));
        assertTrue(candidate.value()); assertFalse(candidate.yaml().getBoolean("mob-progression.group.enabled"));
        assertTrue(candidate.yaml().getBoolean("mob-progression.history.enabled"));
        assertTrue(candidate.messages().has("admin-reload-failed"));
    }
    @Test void rejectedCandidateKeepsHuntFlagsLimitsGroupAndHistoryInOneBundle() throws Exception {
        String defaults = "general: {enabled: true}\nlimits: {coins-per-day: 2000}\nmob-progression: {group: {enabled: false}, history: {enabled: true}}";
        Path file = Files.writeString(dir.resolve("config.yml"), "general: {enabled: false}\nlimits: {coins-per-day: 123}");
        Path text = Files.writeString(dir.resolve("messages.yml"), "{}");
        var old = ConfigSnapshot.load(file, bytes(defaults), HuntConfig::parse,
                (yaml, log) -> Messages.fromChecked(bytes("demo: {line: old}"), text, log));
        var current = new AtomicReference<>(old);
        Files.writeString(file, "general: {enabled: true}\nlimits: {coins-per-day: 999}\nmob-progression: {group: {enabled: true}, history: {enabled: false}}");
        Files.writeString(text, "demo: {line: 12}");
        assertThrows(InvalidConfigurationException.class, () -> current.set(ConfigSnapshot.load(file, bytes(defaults), HuntConfig::parse,
                (yaml, log) -> Messages.fromChecked(bytes("demo: {line: new}"), text, log))));
        assertSame(old, current.get()); assertFalse(current.get().value().enabled()); assertEquals(123, current.get().value().coinsPerDay());
        assertFalse(current.get().yaml().getBoolean("mob-progression.group.enabled"));
        assertTrue(current.get().yaml().getBoolean("mob-progression.history.enabled"));
        assertEquals("old", current.get().messages().raw("line"));
    }
    @Test void pluginDoesNotClearHistoryMobQueuesOrMenusBeforeCommit() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntPlugin.java"));
        String reload = source.substring(source.indexOf("public boolean tryReloadHunt()"), source.indexOf("private void registerCommands()"));
        int commit = reload.indexOf("settings = candidate;");
        assertTrue(reload.indexOf("return false;") < commit);
        for (String cleanup : new String[]{"historyTracker.clear()", "progression.reload()", "gui.closeAll()", "shopGui.closeAll()", "trophyGui.closeAll()", "bestiaryGui.closeAll()"})
            assertTrue(commit < reload.indexOf(cleanup), cleanup);
        assertFalse(reload.contains("reloadConfig();")); assertTrue(reload.contains("Messages.loadChecked"));
        assertTrue(source.contains("return current == null ? super.getConfig() : current.yaml();"));
        assertTrue(source.contains("return settings.messages();")); assertTrue(source.contains("return settings.value();"));
        String command = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntCommandHandler.java"));
        assertTrue(command.contains("if (plugin.tryReloadHunt())")); assertTrue(command.contains("\"admin-reload-failed\""));
    }
}
