package ru.cherdakmd.chrdkgenerator;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Цены улучшений: шаги тира, уровни модулей, описания для сообщений. */
class GeneratorUpgradesTest {

    private static GeneratorConfig config() {
        InputStream in = GeneratorUpgradesTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in, "config.yml упакован в модуль");
        return GeneratorConfig.load(YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    @Test void tierStepsMatchDesignExamples() {
        GeneratorConfig config = config();
        GeneratorConfig.Cost tier3 = GeneratorUpgrades.tierCost(config, 3);
        assertEquals(25_000, tier3.money(), "тир 3 — 25 000 ₽ по дизайну");
        assertEquals("IRON_INGOT", tier3.resources().get(0).material());
        assertEquals(256, tier3.resources().get(0).amount());

        GeneratorConfig.Cost tier5 = GeneratorUpgrades.tierCost(config, 5);
        assertEquals(120_000, tier5.money(), "тир 5 — 120 000 ₽ по дизайну");
        assertEquals("DIAMOND", tier5.resources().get(0).material());
        assertEquals(64, tier5.resources().get(0).amount());

        assertThrows(IndexOutOfBoundsException.class, () -> GeneratorUpgrades.tierCost(config, 1),
                "у тира 1 нет предыдущего шага");
    }

    @Test void moduleCostsSplitResourcesAndMoney() {
        GeneratorConfig config = config();
        assertEquals("IRON_INGOT",
                GeneratorUpgrades.moduleCost(config, 1).resources().get(0).material());
        assertEquals(0, GeneratorUpgrades.moduleCost(config, 2).money());
        assertEquals(25_000, GeneratorUpgrades.moduleCost(config, 4).money());
        assertEquals(75_000, GeneratorUpgrades.moduleCost(config, 5).money());
        assertEquals(List.of(), GeneratorUpgrades.moduleCost(config, 5).resources(),
                "высшие уровни без ресурсов");
        assertEquals(50_000, config.chunkCost().money());
        assertEquals("DIAMOND", config.chunkCost().resources().get(0).material());
    }

    @Test void describeShowsMoneyAndResources() {
        GeneratorConfig config = config();
        assertEquals("25000 ₽ + IRON_INGOT × 256",
                GeneratorUpgrades.describe(GeneratorUpgrades.tierCost(config, 3)));
        assertEquals("75000 ₽",
                GeneratorUpgrades.describe(GeneratorUpgrades.moduleCost(config, 5)));
        assertEquals("IRON_INGOT × 32",
                GeneratorUpgrades.describe(GeneratorUpgrades.moduleCost(config, 1)));
    }

    @Test void paymentsUseWhitelistedSourceWithBankOnlyBurn() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkgenerator/GeneratorPlugin.java"));
        assertTrue(plugin.contains(
                        "new PaidActions(this, this::coreApi, this::messages, \"generator-upgrades\", true)"),
                "покупки платят источником generator-upgrades и только банком (касса не участвует)");
    }
}
