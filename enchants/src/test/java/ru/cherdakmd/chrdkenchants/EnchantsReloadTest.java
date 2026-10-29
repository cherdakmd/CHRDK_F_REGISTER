package ru.cherdakmd.chrdkenchants;

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

class EnchantsReloadTest {
    @TempDir Path dir;
    private InputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void shippedConfigAndTextsAreAcceptedAsACompleteCandidate() throws Exception {
        Path file = dir.resolve("config.yml");
        try (var resource = getClass().getResourceAsStream("/config.yml")) { Files.copy(resource, file); }
        var candidate = ConfigSnapshot.load(file, getClass().getResourceAsStream("/config.yml"), EnchantsConfig::parse,
                (yaml, log) -> Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, log));
        assertEquals(3, candidate.value().maxPerItem());
        assertTrue(candidate.value().isEnabled("provoking_strike"));
        assertTrue(candidate.value().isEnabled("signal_guard"));
        assertTrue(candidate.messages().has("admin-reload-failed"));
    }
    @Test void failedMessageReloadKeepsOldEnabledEffectsRawSwitchAndRulesTogether() throws Exception {
        Path file = Files.writeString(dir.resolve("config.yml"), "pve-effects: {enabled: false}\nrules: {max-per-item: 2}\n");
        Path messages = Files.writeString(dir.resolve("messages.yml"), "{}");
        String defaults = "pve-effects: {enabled: true}\nrules: {max-per-item: 3}\n";
        var old = ConfigSnapshot.load(file, bytes(defaults), EnchantsConfig::parse,
                (yaml, log) -> Messages.fromChecked(bytes("demo: {line: old}"), messages, log));
        var current = new AtomicReference<>(old);
        Files.writeString(file, "pve-effects: {enabled: true}\nrules: {max-per-item: 7}");
        Files.writeString(messages, "demo: [broken");
        assertThrows(InvalidConfigurationException.class, () -> current.set(ConfigSnapshot.load(file, bytes(defaults), EnchantsConfig::parse,
                (yaml, log) -> Messages.fromChecked(bytes("demo: {line: new}"), messages, log))));
        assertSame(old, current.get()); assertEquals(2, current.get().value().maxPerItem());
        assertFalse(current.get().yaml().getBoolean("pve-effects.enabled"));
        assertFalse(current.get().value().isEnabled("signal_guard")); assertEquals("old", current.get().messages().raw("line"));
    }
    @Test void pluginCommitsBeforeGenerationChangesCleanupAndSuccessReply() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsPlugin.java"));
        String reload = source.substring(source.indexOf("public boolean tryReloadEnchantsConfig()"), source.indexOf("public ChrdkRegisterApi coreApi()"));
        assertTrue(reload.indexOf("return false;") < reload.indexOf("settings = candidate;"));
        assertTrue(reload.indexOf("settings = candidate;") < reload.indexOf("sourceGeneration++"));
        assertTrue(reload.indexOf("sourceGeneration++") < reload.indexOf("table.clear()"));
        assertTrue(reload.contains("Messages.loadChecked")); assertFalse(reload.contains("reloadConfig();"));
        assertTrue(source.contains("return current == null ? super.getConfig() : current.yaml();"));
        assertTrue(source.contains("return settings.messages();")); assertTrue(source.contains("return settings.value();"));
        String command = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantCommandHandler.java"));
        assertTrue(command.contains("if (plugin.tryReloadEnchantsConfig())")); assertTrue(command.contains("\"admin-reload-failed\""));
    }
}
