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
 * Перерождения (п.30) и дневной бюджет алмазов: накопление, полный сброс,
 * конвертация старого престижа, вехи и уникальные предметы — и совместимость
 * {@code skills.json} со строгим чтецом legacy-ledger {@link MilestoneMigration},
 * который требует ровно {@code version: 1}.
 */
class SkillRebirthTest {

    private static final Logger LOGGER = Logger.getLogger("chrdk-skills-rebirth-test");
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path folder;

    @Test
    void rebirthsAccumulate() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertEquals(0, store.rebirths(PLAYER));
        store.addRebirth(PLAYER);
        store.addRebirth(PLAYER);
        assertEquals(2, store.rebirths(PLAYER));
        assertEquals(0, store.totalPrestige(PLAYER),
                "историческая шкала ступеней живёт отдельно; контракт ×10 — в SkillService");
    }

    @Test
    void resetForRebirthClearsLevelsAttributesAndBranchesButKeepsEarned() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 131_175L);
        store.addXp(PLAYER, SkillType.FISHING, 5_000L);
        store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 40);
        store.setBranch(PLAYER, BuildBranch.WARRIOR, 3);
        store.noteDiamondDrops(PLAYER, "2026-11-10", 5);

        store.resetForRebirth(PLAYER);

        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
        assertEquals(0L, store.xp(PLAYER, SkillType.FISHING));
        assertEquals(0, store.attribute(PLAYER, PlayerAttribute.STRENGTH));
        assertEquals(0, store.branch(PLAYER, BuildBranch.WARRIOR));
        assertEquals(5, store.diamondDropsToday(PLAYER, "2026-11-10"),
                "дневной бюджет не входит в сброс");
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
    void milestoneAndUniqueRewardsAreOneTime() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.markRebirthMilestone(PLAYER, 5));
        assertFalse(store.markRebirthMilestone(PLAYER, 5), "веха одноразова");
        assertTrue(store.claimedRebirthMilestones(PLAYER).contains(5));

        assertTrue(store.grantRebirthUnique(PLAYER, "nether_star"));
        assertFalse(store.grantRebirthUnique(PLAYER, "nether_star"),
                "уникальный предмет не выдаётся дважды");
        assertTrue(store.grantRebirthUnique(PLAYER, "dragon_egg"));
        assertEquals(2, store.rebirthUniques(PLAYER).size());
    }

    @Test
    void rebirthTopIsSortedAndSkipsEmptyPlayers() {
        SkillStore store = new SkillStore(folder, LOGGER);
        String other = "22222222-3333-4444-5555-666666666666";
        store.addRebirth(PLAYER);
        for (int count = 0; count < 4; count++) {
            store.addRebirth(other);
        }
        store.setName(PLAYER, "Steve");
        store.setName(other, "Alex");
        assertEquals(2, store.rebirthTop(10).size());
        assertEquals(other, store.rebirthTop(10).get(0).getKey());
        assertEquals(4, store.rebirthTop(10).get(0).getValue());
        assertEquals(1, store.rebirthTop(1).size());
    }

    @Test
    void snapshotStaysCompatibleWithMilestoneMigration() throws IOException {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.setName(PLAYER, "Steve");
        store.addXp(PLAYER, SkillType.MINING, 131_175L);
        store.markMilestone(PLAYER, 100);
        store.addRebirth(PLAYER);
        store.noteDiamondDrops(PLAYER, "2026-11-06", 9);
        assertTrue(store.markRebirthMilestone(PLAYER, 5));
        store.flush();

        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\": 1"), "версию поднимать нельзя");
        assertTrue(json.contains("\"rebirths\""));
        assertTrue(json.contains("\"claimedRebirthMilestones\""));
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
        assertEquals(1, reloaded.rebirths(PLAYER));
        assertEquals(9, reloaded.diamondDropsToday(PLAYER, "2026-11-06"));
        assertTrue(reloaded.claimedRebirthMilestones(PLAYER).contains(5));
        assertEquals("Steve", reloaded.name(PLAYER));
    }

    @Test
    void legacyPrestigeConvertsToRebirthsOnce() throws IOException {
        Path file = folder.resolve("skills.json");
        Files.writeString(file, """
                {"version":1,"players":{"%s":{"name":"Alex","xp":{"mining":131175},"prestige":{"mining":10,"combat":10},"milestones":[]}}}
                """.formatted(PLAYER), StandardCharsets.UTF_8);
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertEquals(2, store.rebirths(PLAYER),
                "каждые 10 старых ступеней суммарно — одно перерождение");
        assertEquals(20, store.totalPrestige(PLAYER),
                "шкала ступеней сохранена для контракта аддонов");
        assertTrue(store.markRebirthMilestone(PLAYER, 5),
                "веха 5 не пройдена счётчиком 2 — конвертация её не отмечает");
        store.flush();

        SkillStore reloaded = new SkillStore(folder, LOGGER);
        reloaded.load();
        assertEquals(2, reloaded.rebirths(PLAYER), "конвертация одноразовая");
    }

    @Test
    void legacyConversionMarksOldMilestonesWithoutPaying() throws IOException {
        Path file = folder.resolve("skills.json");
        Files.writeString(file, """
                {"version":1,"players":{"%s":{"name":"Alex","xp":{},"prestige":{"mining":10,"combat":10,"farming":10,"fishing":10,"woodcutting":10,"digging":10},"milestones":[]}}}
                """.formatted(PLAYER), StandardCharsets.UTF_8);
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertEquals(6, store.rebirths(PLAYER));
        assertTrue(store.claimedRebirthMilestones(PLAYER).contains(5),
                "веха 5 уже пройдена по старому престижу");
        assertFalse(store.claimedRebirthMilestones(PLAYER).contains(10),
                "веха 10 ещё впереди");
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
        assertEquals(0, store.rebirths(PLAYER));
        assertEquals(0, store.diamondDropsToday(PLAYER, "2026-11-06"));
    }
}
