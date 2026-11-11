package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Границы стартового комплекта: то, что легко сломать будущей правкой. */
final class StarterKitContractTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    /** Только исполняемый код: в Javadoc эти названия встречаются как пояснение. */
    private static String code(String path) throws IOException {
        return read(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    void configAndMessageAreShippedWithTheCore() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("starter-kit:"));
        assertTrue(config.contains("bank-diamonds: 1000"));
        assertTrue(config.contains("inventory-diamonds: 32"));
        assertTrue(config.contains("include-cursed-artifacts: true"));
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("\"starter-kit\""));
        assertTrue(messages.contains("\"granted\""));
        assertTrue(messages.contains("%balance%"));
        assertTrue(messages.contains("%diamonds%"));
    }

    @Test
    void starterKitIsWiredIntoThePluginLifecycle() throws IOException {
        String plugin = read("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java");
        assertTrue(plugin.contains("private StarterKitService starterKitService;"));
        assertTrue(plugin.contains("registerEvents(starterKitService, this)"));
        assertTrue(plugin.contains("starterKitService.start()"));
        assertTrue(plugin.contains("starterKitService.reload(StarterKitSettings.parse(getConfig()))"));
        assertTrue(plugin.contains("starterKitService.shutdown()"));
        assertTrue(plugin.contains("public StarterKitService starterKit()"));
    }

    @Test
    void grantNeverTouchesDropsDailyCapsOrNms() throws IOException {
        String sources = code("src/main/java/ru/cherdakmd/chrdkregister/StarterKitService.java")
                + code("src/main/java/ru/cherdakmd/chrdkregister/StarterKitPlan.java")
                + code("src/main/java/ru/cherdakmd/chrdkregister/StarterKitStore.java")
                + code("src/main/java/ru/cherdakmd/chrdkregister/StarterKitSettings.java");
        assertFalse(sources.contains("DiamondDrops"), "стартовый подарок — не добыча");
        assertFalse(sources.contains("MoneyLoot"), "стартовый подарок — не MoneyLoot");
        assertFalse(sources.contains("dailyCap"), "дневной лимит Skills не тратится");
        assertFalse(sources.contains("callEvent("));
        assertFalse(sources.contains("net.minecraft"));
        assertFalse(sources.contains("createExplosion("));
        assertTrue(sources.contains("BankStore") || sources.contains("bankStore().deposit"));
        assertTrue(sources.contains("ChrdkArtifactsApi"));
        assertTrue(sources.contains("starter-kits.json"));
        assertTrue(sources.contains("PlayerAuthCompletedEvent"),
                "выдача только после завершения авторизации");
    }

    @Test
    void publicApiVersionIsNotBumpedByTheNewFeature() throws IOException {
        assertTrue(read("src/main/java/ru/cherdakmd/chrdkregister/api/ChrdkRegisterApi.java")
                .contains("int API_VERSION = 43;"));
        String settings = read("src/main/java/ru/cherdakmd/chrdkregister/StarterKitSettings.java");
        assertTrue(settings.contains("defaults()"),
                "отсутствие секции в чужом config.yml не выключает функцию");
    }
}
