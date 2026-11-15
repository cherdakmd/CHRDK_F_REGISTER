package ru.cherdakmd.chrdkmobs;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт ресурсов модуля: таблица тиров, лут, варианты, тексты и plugin.yml.
 */
class MobTierConfigTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void tierTableIsTenRowsAndDocumented() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("distances: [500, 1200, 2200, 3500, 5200, 7200, 9600, 12500, 16000]"),
                "девять границ = десять тиров");
        assertTrue(config.contains("table:"), "свойства каждого тира");
        assertTrue(config.contains("loot-chance: 0.15"), "шанс дропа лута настраивается на тир");
        assertTrue(config.contains("name-prefix: true"));
        assertTrue(config.contains("NATURAL/PATROL"), "фермы спавнеров исключены документированно");
        assertTrue(config.contains("reward-tiers: 2"));
        assertTrue(config.contains("loot-bonus: 1"));
        assertTrue(config.contains("epithets:"), "имена элит настраиваются");
    }

    @Test
    void progressionVariantAndLootSettingsAreNamed() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("progression:"));
        assertTrue(config.contains("health-per-100: 0.10"));
        assertTrue(config.contains("damage-per-100: 0.05"));
        assertTrue(config.contains("prestige-weight: 5"));
        assertTrue(config.contains("max-bonus: 1.5"));
        assertTrue(config.contains("variants:"), "проклятые, тлеющие, всратые и кринжовые");
        assertTrue(config.contains("chance: 0.012"));
        assertTrue(config.contains("loot:"));
        assertTrue(config.contains("essence-xp: 40"));
        assertTrue(config.contains("variant-xp: 60"));
        assertTrue(config.contains("effects:"), "эффекты особых мобов");
        assertTrue(config.contains("particles: true"), "частицы особых мобов по мотивам Infernal-Mobs");
        assertTrue(config.contains("прогрессией НЕ"), "награда не масштабируется прогрессией");
        assertTrue(config.contains("mitigation:"), "потолок суммарного снижения урона плагинами");
        assertTrue(config.contains("cap: 0.5"));
        assertTrue(config.contains("per-tier-step: 0.04"));
        assertTrue(config.contains("floor: 0.3"));
    }

    @Test
    void parsedTableCarriesPerTierProperties() throws IOException {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                new StringReader(read("src/main/resources/config.yml")));
        MobsConfig config = MobsConfig.parse(cfg, Logger.getLogger("test"));
        assertTrue(config.enabled());
        assertEquals(9, config.thresholds().size(), "девять границ");
        assertEquals(10, config.table().size(), "десять строк свойств");
        MobsConfig.Tier first = config.tier(1);
        assertEquals(1.0, first.health());
        assertEquals(0, first.reward(), "первый тир без денег");
        assertEquals(0.15, first.lootChance(), "шанс дропа лута тира 1");
        MobsConfig.Tier last = config.tier(10);
        assertEquals(10.0, last.health());
        assertEquals(3.8, last.damage());
        assertEquals(5, last.reward());
        assertEquals(4, last.abilitiesMin());
        assertEquals(5, last.abilitiesMax());
        assertEquals(3, last.lootMin());
        assertEquals(3, last.lootMax());
        assertEquals(0.08, last.lootChance(), "шанс дропа лута тира 10");
        assertEquals(last.health(), config.tier(99).health(),
                "тиры выше последней строки наследуют её");
        assertEquals(0.5, config.mitigation().cap(), 1e-9);
        assertEquals(0.04, config.mitigation().perTierStep(), 1e-9);
        assertEquals(0.3, config.mitigation().floor(), 1e-9);
    }

    @Test
    void lootChanceFallsBackWhenRowOmitsIt() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(
                "tiers:\n"
                        + "  table:\n"
                        + "    1: {health: 1.0, damage: 1.0, abilities: [1, 1], reward: 0, loot: [1, 2]}\n"));
        MobsConfig config = MobsConfig.parse(cfg, Logger.getLogger("test"));
        assertEquals(0.35, config.tier(1).lootChance(),
                "строка без loot-chance не роняет парсер");
    }

    @Test
    void textsAndPluginContract() throws IOException {
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("mob-names:"), "русские названия мобов");
        assertTrue(messages.contains("PILLAGER: Разбойник"), "имена мобов — на русском");
        assertTrue(messages.contains("ZOMBIE: Зомби"));
        assertTrue(messages.contains("mob-name-prefix"));
        assertTrue(messages.contains("mob-reward"));
        assertTrue(messages.contains("mobtiers-header"));
        assertTrue(messages.contains("mobtiers-line"));
        assertTrue(messages.contains("дроп %chance%"), "шанс дропа лута виден в /mobtiers");
        assertTrue(messages.contains("elite-name"), "имя элиты");
        assertTrue(messages.contains("elite-reward"), "награда за элиту");
        assertTrue(messages.contains("variant-reward"), "награда за вариант");
        assertTrue(messages.contains("variant-cursed: "), "проклятые");
        assertTrue(messages.contains("variant-smoldering: "), "тлеющие");
        assertTrue(messages.contains("variant-gross: "), "всрaтые");
        assertTrue(messages.contains("variant-cringe: "), "кринжовые");
        assertTrue(messages.contains("loot.essence-combat.name"), "эссенции навыков");
        assertTrue(messages.contains("loot.variant-gross.name"), "сущности вариантов");
        assertTrue(messages.contains("loot-used"));
        assertTrue(messages.contains("mobtiers-progression"));
        assertTrue(messages.contains("mobtiers-elite"));
        assertTrue(messages.contains("mobtiers-variants"));
        assertTrue(messages.contains("mobtiers-loot"));
        String plugin = read("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("name: CHRDK_Mobs"));
        assertTrue(plugin.contains("main: ru.cherdakmd.chrdkmobs.MobsPlugin"));
        assertTrue(plugin.contains("CHRDK_Core"));
        assertTrue(plugin.contains("mobtiers"), "команда для меню и рассылки");
        assertTrue(plugin.contains("chrdkmobs.use"), "право пункта меню");
    }
}
