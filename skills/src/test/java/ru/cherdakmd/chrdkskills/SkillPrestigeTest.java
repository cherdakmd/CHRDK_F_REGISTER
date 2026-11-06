package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.MilestoneMigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Престиж и дневной бюджет алмазов: накопление, смена дня, перезапуск и —
 * главное — совместимость {@code skills.json} со строгим чтецом legacy-ledger
 * {@link MilestoneMigration}, который требует ровно {@code version: 1}.
 */
class SkillPrestigeTest {

    private static final Logger LOGGER = Logger.getLogger("chrdk-skills-prestige-test");
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path folder;

    @Test
    void prestigeIsPerSkillAndAccumulates() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertEquals(0, store.prestige(PLAYER, SkillType.MINING));
        assertTrue(store.addPrestige(PLAYER, SkillType.MINING));
        assertTrue(store.addPrestige(PLAYER, SkillType.MINING));
        assertEquals(2, store.prestige(PLAYER, SkillType.MINING));
        assertEquals(0, store.prestige(PLAYER, SkillType.FARMING));
        assertEquals(2, store.totalPrestige(PLAYER));
        assertTrue(store.addPrestige(PLAYER, SkillType.FARMING));
        assertEquals(3, store.totalPrestige(PLAYER));
        assertFalse(store.addPrestige(null, SkillType.MINING));
    }

    @Test
    void dailyBudgetResetsOnNewDay() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.noteDiamondDrops(PLAYER, "2026-11-06", 7);
        store.noteDiamondDrops(PLAYER, "2026-11-06", 3);
        assertEquals(10, store.diamondDropsToday(PLAYER, "2026-11-06"));
        assertEquals(0, store.diamondDropsToday(PLAYER, "2026-11-07"));
        store.noteDiamondDrops(PLAYER, "2026-11-07", 2);
        assertEquals(2, store.diamondDropsToday(PLAYER, "2026-11-07"));
        assertEquals(0, store.diamondDropsToday(PLAYER, "2026-11-06"),
                "старый день не воскресает");
    }

    @Test
    void snapshotStaysCompatibleWithMilestoneMigration() throws IOException {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.setName(PLAYER, "Steve");
        store.addXp(PLAYER, SkillType.MINING, 131_175L);
        store.markMilestone(PLAYER, 100);
        store.addPrestige(PLAYER, SkillType.MINING);
        store.noteDiamondDrops(PLAYER, "2026-11-06", 9);
        store.flush();

        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\": 1"), "версию поднимать нельзя");
        assertTrue(json.contains("\"prestige\""));
        assertTrue(json.contains("\"diamondDay\""));
        assertTrue(json.contains("\"diamondToday\""));

        // Строгий чтец ядра не должен упасть на новых полях: иначе плагин
        // отключится при старте и прогресс игроков окажется недоступен.
        Map<UUID, Set<String>> legacy = MilestoneMigration.json(
                store.file(), MilestoneMigration.Kind.SKILLS);
        assertTrue(legacy.get(UUID.fromString(PLAYER)).contains("level:100"));

        SkillStore reloaded = new SkillStore(folder, LOGGER);
        reloaded.load();
        assertEquals(131_175L, reloaded.xp(PLAYER, SkillType.MINING));
        assertTrue(reloaded.milestones(PLAYER).contains(100));
        assertEquals(1, reloaded.prestige(PLAYER, SkillType.MINING));
        assertEquals(9, reloaded.diamondDropsToday(PLAYER, "2026-11-06"));
        assertEquals("Steve", reloaded.name(PLAYER));
    }

    @Test
    void legacyFileWithoutNewFieldsStillLoads() throws IOException {
        Path file = folder.resolve("skills.json");
        Files.writeString(file, """
                {"version":1,"players":{"%s":{"name":"Alex","xp":{"mining":500},"milestones":[10,25]}}}
                """.formatted(PLAYER), StandardCharsets.UTF_8);
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertEquals(500L, store.xp(PLAYER, SkillType.MINING));
        assertEquals(0, store.prestige(PLAYER, SkillType.MINING));
        assertEquals(0, store.diamondDropsToday(PLAYER, "2026-11-06"));
    }
}
