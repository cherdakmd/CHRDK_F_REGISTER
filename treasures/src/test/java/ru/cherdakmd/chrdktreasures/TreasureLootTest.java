package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureLootTest {

    @TempDir
    Path folder;

    @AfterEach
    void restoreShippedTables() {
        TreasureLoot.install(shipped());
    }

    private static TreasureLoot.Table shipped() {
        try (var stream = TreasureLootTest.class.getClassLoader()
                .getResourceAsStream(TreasureLoot.FILE_NAME)) {
            assertNotNull(stream, "loot.yml не найден в ресурсах");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return TreasureLoot.parse(YamlConfiguration.loadConfiguration(reader), null);
            }
        } catch (Exception exception) {
            throw new AssertionError("не удалось прочитать loot.yml", exception);
        }
    }

    private static TreasureLoot.Table table(String yaml) {
        return TreasureLoot.parse(YamlConfiguration.loadConfiguration(
                new java.io.StringReader(yaml)), null);
    }

    @Test
    void everyTierHasItsOwnNumberOfRolls() {
        assertEquals(1, TreasureLoot.rolls(TreasureTier.COMMON));
        assertEquals(2, TreasureLoot.rolls(TreasureTier.RARE));
        assertEquals(2, TreasureLoot.rolls(TreasureTier.EPIC));
        assertEquals(3, TreasureLoot.rolls(TreasureTier.LEGENDARY));
        assertEquals(4, TreasureLoot.rolls(TreasureTier.MYTHICAL));
    }

    @Test
    void shippedTablesAreRichAndContainNamedFinds() {
        TreasureLoot.Table table = shipped();
        assertTrue(table.size() >= 900, "слишком мало записей: " + table.size());
        Set<String> names = new HashSet<>();
        for (TreasureTier tier : TreasureTier.values()) {
            for (TreasureLoot.LootEntry entry : table.global(tier)) {
                if (!entry.name().isBlank()) {
                    names.add(entry.name());
                }
            }
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            for (TreasureTier tier : TreasureTier.values()) {
                for (TreasureLoot.LootEntry entry : table.regional(region, tier)) {
                    if (!entry.name().isBlank()) {
                        names.add(entry.name());
                    }
                }
            }
        }
        assertTrue(names.size() >= 40, "именных находок слишком мало: " + names.size());
        assertTrue(names.contains("Кровавый суп"));
        assertTrue(names.contains("Зависть пиглина"));
        assertTrue(names.contains("Обычный амулет"));
    }

    @Test
    void allPoolsContainRealItems() {
        Set<String> materials = new HashSet<>();
        for (TreasureTier tier : TreasureTier.values()) {
            List<TreasureLoot.LootEntry> pool = TreasureLoot.poolOf(tier);
            assertFalse(pool.isEmpty(), "пустой пул у " + tier.id());
            for (TreasureLoot.LootEntry entry : pool) {
                materials.add(entry.material());
                assertValidItem(entry);
            }
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            List<TreasureLoot.LootEntry> flavor = TreasureLoot.flavorOf(region);
            assertFalse(flavor.isEmpty(), "нет биомной добычи у " + region.id());
            for (TreasureLoot.LootEntry entry : flavor) {
                materials.add(entry.material());
                assertValidItem(entry);
            }
        }
        assertTrue(materials.size() >= 300, "слишком мало разных материалов: " + materials.size());
    }

    private static void assertValidItem(TreasureLoot.LootEntry entry) {
        Material material;
        try {
            material = Material.valueOf(entry.material());
        } catch (IllegalArgumentException exception) {
            throw new AssertionError("неизвестный материал " + entry.material(), exception);
        }
        assertNotNull(material, "нет материала " + entry.material());
        assertTrue(entry.min() >= 1 && entry.max() >= entry.min());
        assertTrue(entry.max() <= 64, "стек больше 64: " + entry.material());
        assertTrue(entry.weight() >= 1);
        assertTrue(entry.unlock() >= 0);
    }

    @Test
    void everyRegionTierPoolIsFilled() {
        TreasureLoot.Table table = shipped();
        for (TreasureTier tier : TreasureTier.values()) {
            assertTrue(table.global(tier).size() >= 15,
                    "общий пул " + tier.id() + " слишком мал: " + table.global(tier).size());
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            for (TreasureTier tier : TreasureTier.values()) {
                int size = table.regional(region, tier).size();
                assertTrue(size >= 4,
                        "пул " + region.id() + "/" + tier.id() + " слишком мал: " + size);
            }
        }
    }

    @Test
    void shippedTablesDeclareCurrentVersion() {
        assertEquals(TreasureLoot.CURRENT_VERSION, shipped().version());
    }

    @Test
    void oldFileGetsNewEntriesButKeepsAdminEdits() {
        TreasureLoot.Table old = table("""
                global:
                  common:
                    - { material: COAL, min: 1, max: 2, weight: 4, unlock: 0 }
                """);
        assertEquals(1, old.size());
        assertEquals(1, old.version(), "файл без version считается старым");

        TreasureLoot.Table merged = old.withMissing(shipped());
        assertTrue(merged.size() > 900, "старый файл не пополнился: " + merged.size());

        List<TreasureLoot.LootEntry> coal = merged.global(TreasureTier.COMMON).stream()
                .filter(entry -> entry.material().equals("COAL"))
                .toList();
        assertEquals(1, coal.size(), "запись администратора задвоилась");
        assertEquals(1, coal.get(0).min(), "правка администратора потерялась");
        assertEquals(TreasureLoot.CURRENT_VERSION, merged.version());
    }

    @Test
    void poolsDoNotRepeatTheSameItem() {
        TreasureLoot.Table table = shipped();
        for (TreasureTier tier : TreasureTier.values()) {
            assertNoRepeats("global/" + tier.id(), table.global(tier));
            for (TreasureRegion region : TreasureRegion.values()) {
                assertNoRepeats(region.id() + "/" + tier.id(), table.regional(region, tier));
            }
        }
    }

    private static void assertNoRepeats(String where, List<TreasureLoot.LootEntry> entries) {
        Set<String> keys = new HashSet<>();
        for (TreasureLoot.LootEntry entry : entries) {
            String key = entry.material() + '\u0000' + entry.name();
            assertTrue(keys.add(key), "повтор в пуле " + where + ": " + entry.material()
                    + " / " + entry.name());
        }
    }

    @Test
    void versionInFileIsReadFromYaml() {
        TreasureLoot.Table table = table("""
                version: 2
                global:
                  rare:
                    - { material: DIAMOND, min: 1, max: 1, weight: 1, unlock: 0 }
                """);
        assertEquals(2, table.version());
        assertEquals(1, table.size());
    }

    @Test
    void rollAggregatesStacksAndCapsAtSixtyFour() {
        Random random = new Random(42L);
        for (TreasureTier tier : TreasureTier.values()) {
            for (TreasureRegion region : TreasureRegion.values()) {
                List<TreasureLoot.LootStack> loot = TreasureLoot.roll(
                        tier, region, random, Long.MAX_VALUE);
                assertFalse(loot.isEmpty(), "пустой клад " + tier.id() + "/" + region.id());
                Set<String> keys = new HashSet<>();
                for (TreasureLoot.LootStack stack : loot) {
                    assertTrue(keys.add(stack.material() + '\u0000' + stack.name()),
                            "запись не сложилась: " + stack.material());
                    assertTrue(stack.amount() >= 1 && stack.amount() <= 64,
                            "кривое количество " + stack.amount());
                    assertNotNull(Material.valueOf(stack.material()));
                }
            }
        }
    }

    @Test
    void nullRegionStillGivesTierLoot() {
        List<TreasureLoot.LootStack> loot = TreasureLoot.roll(
                TreasureTier.MYTHICAL, null, new Random(7L));
        assertFalse(loot.isEmpty());
    }

    @Test
    void unlockHidesExpensiveEntriesUntilProgress() {
        TreasureLoot.install(table("""
                global:
                  common:
                    - { material: COAL, min: 1, max: 1, weight: 1, unlock: 0 }
                    - { material: DIAMOND, min: 1, max: 1, weight: 1000, unlock: 100 }
                """));
        for (int seed = 0; seed < 50; seed++) {
            for (TreasureLoot.LootStack stack : TreasureLoot.roll(
                    TreasureTier.COMMON, null, new Random(seed), 0)) {
                assertEquals("COAL", stack.material(), "алмаз выпал до порога");
            }
        }
        TreasureLoot.LootEntry diamond = TreasureLoot.poolOf(TreasureTier.COMMON).stream()
                .filter(entry -> entry.material().equals("DIAMOND"))
                .findFirst().orElseThrow();
        assertEquals(0.0, TreasureLoot.chance(TreasureTier.COMMON, null, diamond, 0), 1e-9);
        assertTrue(TreasureLoot.chance(TreasureTier.COMMON, null, diamond, 100) > 0.9);

        boolean seen = false;
        for (int seed = 0; seed < 20 && !seen; seed++) {
            for (TreasureLoot.LootStack stack : TreasureLoot.roll(
                    TreasureTier.COMMON, null, new Random(seed), 100)) {
                seen |= stack.material().equals("DIAMOND");
            }
        }
        assertTrue(seen, "после порога алмаз так и не выпал");
    }

    @Test
    void namedEntriesKeepTheirNamesThroughParsingAndRoll() {
        TreasureLoot.install(table("""
                global:
                  common:
                    - { material: MUSHROOM_STEW, min: 1, max: 1, weight: 1, unlock: 0, name: "Кровавый суп" }
                """));
        List<TreasureLoot.LootEntry> pool = TreasureLoot.poolOf(TreasureTier.COMMON);
        assertEquals(1, pool.size());
        assertEquals("Кровавый суп", pool.get(0).name());
        for (int seed = 0; seed < 5; seed++) {
            List<TreasureLoot.LootStack> loot = TreasureLoot.roll(
                    TreasureTier.COMMON, null, new Random(seed), 0);
            assertEquals(1, loot.size());
            assertEquals("MUSHROOM_STEW", loot.get(0).material());
            assertEquals("Кровавый суп", loot.get(0).name());
        }
    }

    @Test
    void namedAndPlainStacksAreNotMerged() {
        TreasureLoot.install(table("""
                global:
                  rare:
                    - { material: MUSHROOM_STEW, min: 1, max: 1, weight: 1, unlock: 0, name: "Кровавый суп" }
                    - { material: MUSHROOM_STEW, min: 1, max: 1, weight: 1, unlock: 0 }
                """));
        boolean separate = false;
        for (int seed = 0; seed < 100 && !separate; seed++) {
            List<TreasureLoot.LootStack> loot = TreasureLoot.roll(
                    TreasureTier.RARE, null, new Random(seed), 0);
            if (loot.size() == 2) {
                assertEquals(1, loot.stream().filter(stack -> stack.name().isBlank()).count());
                assertEquals(1, loot.stream()
                        .filter(stack -> "Кровавый суп".equals(stack.name())).count());
                separate = true;
            }
        }
        assertTrue(separate, "броски не дали одновременно именную и обычную миску");
    }

    @Test
    void brokenRowsAreSkippedInsteadOfBreakingTheTable() {
        TreasureLoot.Table parsed = table("""
                global:
                  common:
                    - { material: NOT_A_MATERIAL, min: 1, max: 1, weight: 1 }
                    - { material: COAL, min: 0, max: 1, weight: 1 }
                    - { material: COAL, min: 2, max: 1, weight: 1 }
                    - { material: COAL, min: 1, max: 2, weight: 0 }
                    - { material: COAL, min: 1, max: 2, weight: 5 }
                    - { min: 1, max: 2, weight: 5 }
                """);
        assertEquals(1, parsed.global(TreasureTier.COMMON).size());
        assertEquals("COAL", parsed.global(TreasureTier.COMMON).get(0).material());
    }

    @Test
    void brokenOrEmptyFileFallsBackToBuiltinTable() throws Exception {
        // SnakeYAML не бросает исключение на мусор: Bukkit отдаёт пустой конфиг.
        Path broken = folder.resolve("broken.yml");
        Files.writeString(broken, "}{ это не yaml");
        Path empty = folder.resolve("empty.yml");
        Files.writeString(empty, "");
        for (Path file : List.of(broken, empty)) {
            TreasureLoot.Table parsed = TreasureLoot.load(file, null);
            // Bukkit на мусоре отдаёт пустой конфиг, но загрузчик может и сразу
            // подставить встроенный набор — оба исхода допустимы.
            assertTrue(parsed.size() == 0 || parsed.size() == TreasureLoot.builtin().size(),
                    "неожиданный размер таблицы у " + file.getFileName() + ": " + parsed.size());
            TreasureLoot.install(parsed);
            assertEquals(TreasureLoot.builtin().size(), TreasureLoot.active().size(),
                    "пустая таблица не должна оставлять игроков без добычи");
        }
        assertEquals(TreasureLoot.builtin().size(),
                TreasureLoot.load(folder.resolve("missing.yml"), null).size(),
                "отсутствующий файл сразу даёт встроенный набор");
    }

    @Test
    void regionalEntriesWeighTwiceInCombinedPool() {
        TreasureLoot.Table parsed = table("""
                global:
                  common:
                    - { material: COAL, min: 1, max: 1, weight: 10, unlock: 0 }
                regions:
                  plains:
                    common:
                      - { material: WHEAT, min: 1, max: 1, weight: 10, unlock: 0 }
                """);
        List<TreasureLoot.LootEntry> combined = parsed.combined(
                TreasureTier.COMMON, TreasureRegion.PLAINS);
        assertEquals(2, combined.size());
        assertEquals(10, combined.get(0).weight());
        assertEquals(20, combined.get(1).weight(), "биомная запись весит вдвое");
    }
}
