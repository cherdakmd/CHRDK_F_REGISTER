package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Контракты безопасности модуля: то, что легко сломать будущей правкой. */
final class DisasterContractTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String sources() throws IOException {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void descriptorWiresCoreClaimsConfigSchemaAndCommands() throws IOException {
        String plugin = read("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("name: CHRDK_Disasters"));
        assertTrue(plugin.contains("api-version: '26.3'"));
        assertTrue(plugin.contains("depend: [CHRDK_Core]"));
        assertTrue(plugin.contains("softdepend: [CHRDK_Claims, CHRDK_Skills, PlaceholderAPI]"));
        assertTrue(plugin.contains("chrdkdisasters.bypass"));
        assertTrue(read("../settings.gradle").contains("include 'disasters'"));
        assertTrue(read("../wiki/config.yml").contains("- disasters"));
    }

    @Test
    void configAndMessagesAreSelfContained() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("enabled: false"));
        assertTrue(config.contains("earthquake: false"));
        assertTrue(config.contains("respect-claims: true"));
        assertTrue(config.contains("restore-after-seconds: 600"));
        String messages = read("src/main/resources/messages.yml");
        for (String key : new String[]{"start", "storm-hint", "status-enabled",
                "status-disabled", "admin-usage", "admin-started", "admin-blocked",
                "admin-stopped", "admin-restored", "admin-not-found", "admin-on",
                "admin-off", "reloaded", "unknown-kind", "no-permission",
                "player-only", "kind-dust-storm", "kind-meteor-shower",
                "kind-earthquake"}) {
            assertTrue(messages.contains("\"" + key + "\""), "нет сообщения " + key);
        }
    }

    @Test
    void noNmsNoExplosionsNoFakeBlockBreaks() throws IOException {
        String sources = sources();
        assertFalse(sources.contains("net.minecraft"), "NMS запрещён");
        assertFalse(sources.contains("getHandle"), "рефлексия в NMS запрещена");
        assertFalse(sources.contains("callEvent("), "катастрофа не порождает Bukkit-события");
        assertFalse(sources.contains("breakNaturally("), "нет добычи ради катастрофы");
        assertFalse(sources.contains("createExplosion("), "взрывы Bukkit не нужны");
        assertTrue(sources.contains("setType(Material.AIR, false)"),
                "единственная точка изменения мира с отключённой физикой");
        assertTrue(sources.contains("instanceof InventoryHolder"),
                "хранилища игроков не трогаем");
    }

    @Test
    void claimsSoftDependencyNeverLoadsItsClassesWhenAbsent() throws IOException {
        String protection = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterProtection.java");
        assertFalse(protection.contains("ru.cherdakmd.chrdkclaims"),
                "защита не должна резолвить классы софт-зависимости");
        assertFalse(protection.contains("instanceof ClaimsPlugin"));
        assertTrue(protection.contains("isPluginEnabled(ClaimsHook.PLUGIN)"));
        String hook = read("src/main/java/ru/cherdakmd/chrdkdisasters/ClaimsHook.java");
        assertTrue(hook.contains("class ClaimsHook"));
        assertTrue(hook.contains("instanceof ClaimsPlugin"));
        assertTrue(hook.contains("LinkageError"), "fail-closed при несовместимости");
    }

    @Test
    void usesSharedConfigSchemaAndMessages() throws IOException {
        String sources = sources();
        assertTrue(sources.contains("ConfigUpgrader.apply(this)"));
        assertTrue(sources.contains("Messages.load(this"));
        assertTrue(sources.contains("ClaimStore") || sources.contains("claimAt("));
        assertTrue(sources.contains("Europe/Moscow"));
        assertTrue(sources.contains("announceCooldownSeconds()"));
    }
}
