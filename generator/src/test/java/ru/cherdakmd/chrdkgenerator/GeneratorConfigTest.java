package ru.cherdakmd.chrdkgenerator;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор секции {@code generator:}: эталонный config.yml и контракт ошибок. */
class GeneratorConfigTest {

    private static YamlConfiguration resource() {
        InputStream in = GeneratorConfigTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in, "config.yml упакован в модуль");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test void defaultConfigParsesWholeGeneratorSection() {
        GeneratorConfig config = GeneratorConfig.load(resource());
        assertEquals(30, config.cycleSeconds());
        assertEquals(256, config.bunkerBase());
        assertEquals(8, config.offlineHours());
        assertEquals(0.5, config.offlineEfficiency());
        assertEquals(false, config.fuelEnabled());
        assertEquals(256, config.cyclesPerBucket());
        assertEquals(5.0, config.rebirthPercentPerRebirth());
        assertEquals(0.9, config.rebirthDecay());
        assertEquals(50.0, config.rebirthBonusCapPercent());

        GeneratorConfig.Craft craft = config.craft();
        assertTrue(craft.enabled());
        assertEquals(List.of("ABA", "BCB", "ABA"), craft.shape());
        assertEquals("IRON_INGOT", craft.ingredients().get('A'));
        assertEquals("DIAMOND", craft.ingredients().get('C'));

        assertEquals(GeneratorConfig.TIERS, config.tiers().size());
        assertEquals("Исток", config.tier(1).name());
        assertEquals("Сердце", config.tier(7).name());
        assertEquals(List.of(1, 1, 2, 2, 3, 3, 4),
                config.tiers().stream().map(GeneratorConfig.Tier::unitsPerCycle).toList());
        assertEquals(4, config.tier(7).drops().size());
        assertTrue(config.tier(7).drops().stream().filter(GeneratorDrops.Drop::rare).count() == 2);
    }

    @Test void malformedGeneratorSectionIsRejected() {
        YamlConfiguration yaml = resource();

        yaml.set("generator.tiers", List.of());
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(yaml), "пустой список тиров");

        YamlConfiguration material = resource();
        material.set("generator.tiers", tiersWithFirstDrop("NOT_A_BLOCK"));
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(material), "неизвестный материал");

        YamlConfiguration shape = resource();
        shape.set("generator.craft.shape", List.of("AB", "CD"));
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(shape), "форма не 3 строки");

        YamlConfiguration ingredient = resource();
        ingredient.set("generator.craft.ingredients.X", "IRON_INGOT");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(ingredient), "лишний ингредиент");

        YamlConfiguration cycle = resource();
        cycle.set("generator.cycle-seconds", 0);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(cycle), "нулевой цикл");

        YamlConfiguration missing = new YamlConfiguration();
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(missing), "нет секции generator");
    }

    private static List<Object> tiersWithFirstDrop(String material) {
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("material", material);
        drop.put("weight", 6);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < GeneratorConfig.TIERS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "Тир" + (i + 1));
            row.put("units-per-cycle", 1);
            row.put("drops", List.of(drop));
            rows.add(row);
        }
        return rows;
    }

    @Test void tierDropsCarryWeightsAndRareFlags() {
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("material", "COBBLESTONE");
        drop.put("weight", 6);
        Map<String, Object> rare = new LinkedHashMap<>();
        rare.put("material", "NETHER_STAR");
        rare.put("weight", 1);
        rare.put("rare", true);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < GeneratorConfig.TIERS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "Тир" + (i + 1));
            row.put("units-per-cycle", 1);
            row.put("drops", List.of(drop, rare));
            rows.add(row);
        }
        YamlConfiguration yaml = resource();
        yaml.set("generator.tiers", rows);
        GeneratorConfig config = GeneratorConfig.load(yaml);
        GeneratorConfig.Tier seven = config.tier(7);
        assertEquals(2, seven.drops().size());
        assertEquals(6, seven.drops().get(0).weight());
        assertEquals(false, seven.drops().get(0).rare());
        assertEquals(true, seven.drops().get(1).rare());
    }
}
