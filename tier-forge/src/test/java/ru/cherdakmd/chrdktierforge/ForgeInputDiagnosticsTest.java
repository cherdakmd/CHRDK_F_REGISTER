package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт отказов наковальни (7 ноября 2026): игрок с вещью без тира и искрой
 * первого тира видел «Нужна одна опознанная тир-вещь слева», хотя искре как раз
 * нужна вещь БЕЗ тира. Каждая причина теперь даёт свой ключ и следующий шаг.
 *
 * <p>Текст читается как файл, а не через {@code getString("tiers.forge-input")}:
 * messages.yml обёрнут в единственную секцию {@code tiers:}, а ключи вроде
 * {@code forge.seed.details} содержат точки и Bukkit трактует их как путь.</p>
 */
final class ForgeInputDiagnosticsTest {

    private static String stock() throws Exception {
        return Files.readString(Path.of("src/main/resources/messages.yml"), StandardCharsets.UTF_8);
    }

    private static YamlConfiguration parsed() throws Exception {
        var yaml = new YamlConfiguration();
        try (var in = ForgeInputDiagnosticsTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in);
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static String of(int amount, boolean unidentified, boolean meta,
                             ItemKind kind, boolean allowed, boolean seed, boolean tier) {
        return ForgeInputDiagnostics.of(amount, unidentified, meta, kind, allowed, seed, tier);
    }

    @Test
    void seedNeedsAnUntieredItemAndOtherMaterialsNeedATieredOne() {
        // Искра: вещь без тира проходит, вещь с тиром — отдельный отказ.
        assertNull(of(1, false, true, ItemKind.PICKAXE, true, true, false));
        assertEquals(ForgeInputDiagnostics.SEED_TIERED,
                of(1, false, true, ItemKind.PICKAXE, true, true, true));
        // Остальные семь расходников: наоборот.
        assertNull(of(1, false, true, ItemKind.PICKAXE, true, false, true));
        assertEquals(ForgeInputDiagnostics.NEED_TIER,
                of(1, false, true, ItemKind.PICKAXE, true, false, false));
    }

    @Test
    void concreteCausesWinOverTheTierCheck() {
        assertEquals(ForgeInputDiagnostics.STACK,
                of(2, false, true, ItemKind.PICKAXE, true, true, false));
        assertEquals(ForgeInputDiagnostics.UNIDENTIFIED,
                of(1, true, true, ItemKind.PICKAXE, true, true, false));
        assertEquals(ForgeInputDiagnostics.NO_META,
                of(1, false, false, ItemKind.PICKAXE, true, true, false));
        assertEquals(ForgeInputDiagnostics.WRONG_KIND,
                of(1, false, true, null, false, true, false));
        // Частый случай владельческого конфига: craft.tools: false.
        assertEquals(ForgeInputDiagnostics.KIND_DISABLED,
                of(1, false, true, ItemKind.PICKAXE, false, true, false));
        assertEquals(ForgeInputDiagnostics.KIND_DISABLED,
                of(1, false, true, ItemKind.PICKAXE, false, false, true));
    }

    @Test
    void everyDiagnosticKeyIsPresentInTheStockMessages() throws Exception {
        String stock = stock();
        List<String> keys = List.of(
                ForgeInputDiagnostics.MISSING, ForgeInputDiagnostics.STACK,
                ForgeInputDiagnostics.UNIDENTIFIED, ForgeInputDiagnostics.NO_META,
                ForgeInputDiagnostics.WRONG_KIND, ForgeInputDiagnostics.KIND_DISABLED,
                ForgeInputDiagnostics.NEED_TIER, ForgeInputDiagnostics.SEED_TIERED,
                ForgeInputDiagnostics.SEED_INVALID, ForgeInputDiagnostics.SEED_UID,
                ForgeInputDiagnostics.REPAIR_UNDAMAGED, ForgeInputDiagnostics.NAME_POOL_EMPTY);
        assertEquals(keys.stream().distinct().count(), keys.size(), "ключи не должны дублироваться");
        for (String key : keys) {
            assertTrue(stock.contains("\n  " + key + ":"), "нет ключа " + key);
        }
        // YAML остаётся валидным после правки (в т.ч. строки с двоеточием в тексте).
        assertNotNull(parsed().getConfigurationSection("tiers"));
    }

    @Test
    void seedRejectionExplainsTheContradictionInsteadOfAskingForATierItem() throws Exception {
        String stock = stock();
        assertFalse(stock.contains("Нужна одна опознанная тир-вещь"),
                "общий отказ больше не должен требовать тир-вещь у искры");
        assertTrue(stock.contains("forge-input-seed-tiered: '<red>Искра первого тира работает только с вещью БЕЗ тира"));
        assertTrue(stock.contains("Возвышение"));
        assertTrue(stock.contains("forge-input-need-tier: '<red>Этому расходнику нужна вещь С тиром"));
        assertTrue(stock.contains("Искрой первого тира"));
    }

    @Test
    void seedLoreNoLongerCallsTheLeftItemUnsuitable() throws Exception {
        String stock = stock();
        assertFalse(stock.contains("одна неподходящая вещь слева"));
        assertTrue(stock.contains("одна вещь БЕЗ тира слева"));
        assertTrue(stock.contains("Для повышения уже существующего тира нужны «Возвышение»"));
    }
}
