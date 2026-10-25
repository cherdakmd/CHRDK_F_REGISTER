package ru.cherdakmd.chrdkquests;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила квестов (1.4.161): цепочки, фракции, ранги, сдача и суточный лимит.
 */
class QuestServiceTest {

    private static final Logger LOGGER = Logger.getLogger("QuestServiceTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String DAY = "2026-10-24";

    private static final String CONFIG = """
            general:
              claims-per-day: 3
            factions:
              watch:
                name: "Орден"
              guild:
                name: "Гильдия"
            npcs:
              elder:
                name: "Старейшина"
                quests:
                  - id: elder_kill
                    type: KILL
                    targets: [ZOMBIE]
                    amount: 2
                    xp: 10
                    reputation: 5
                  - id: elder_mine
                    type: MINE
                    targets: [STONE]
                    amount: 3
                    xp: 10
                    reputation: 5
              scout:
                name: "Разведчик"
                faction: watch
                quests:
                  - id: scout_first
                    type: KILL
                    targets: [SKELETON]
                    amount: 1
                    xp: 5
                    reputation: 100
                  - id: scout_rank
                    type: KILL
                    targets: [CREEPER]
                    amount: 1
                    xp: 5
                    reputation: 5
                    min-rank: 2
              farmer:
                name: "Фермер"
                quests:
                  - id: farmer_wheat
                    type: GATHER
                    targets: [WHEAT]
                    amount: 4
                    xp: 5
                    reputation: 5
            """;

    private static QuestConfig config(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return QuestConfig.parse(yaml, LOGGER);
    }

    private static QuestService service(Path dir, String text) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.load();
        return new QuestService(config(text), store);
    }

    private static QuestService service(Path dir) {
        return service(dir, CONFIG);
    }

    @Test
    void factionJoinAndLeaveKeepReputation(@TempDir Path dir) {
        QuestService service = service(dir);
        assertSame(QuestService.Result.UNKNOWN_FACTION, service.join(PLAYER, "нет"));
        assertSame(QuestService.Result.NO_FACTION, service.leaveFaction(PLAYER));
        assertEquals("", service.factionId(PLAYER));
        assertNull(service.faction(PLAYER));
        assertEquals(0, service.reputation(PLAYER));
        assertEquals("", service.rankId(PLAYER));
        assertEquals(0, service.rankNumber(PLAYER));

        assertSame(QuestService.Result.OK, service.join(PLAYER, " WATCH "));
        assertSame(QuestService.Result.SAME_FACTION, service.join(PLAYER, "watch"));
        assertEquals("watch", service.factionId(PLAYER));
        assertNotNull(service.faction(PLAYER));
        assertEquals("newcomer", service.rankId(PLAYER));
        assertEquals(1, service.rankNumber(PLAYER));

        service.store().addReputation(PLAYER, "watch", 300);
        assertSame(QuestService.Result.OK, service.leaveFaction(PLAYER));
        assertEquals("", service.factionId(PLAYER));
        assertEquals(300, service.store().reputation(PLAYER, "watch"),
                "репутация не сгорает при выходе");
        assertSame(QuestService.Result.OK, service.join(PLAYER, "guild"));
        assertEquals(2, service.rankNumber(PLAYER, "watch"), "ранг считается по очкам фракции");
        assertEquals(0, service.rankNumber(PLAYER, "нет"));
    }

    @Test
    void chainBlocksTheNextQuest(@TempDir Path dir) {
        QuestService service = service(dir);
        Quest first = service.quest("elder_kill");
        Quest second = service.quest("elder_mine");
        assertNotNull(first);
        assertNotNull(second);

        assertSame(QuestService.Availability.AVAILABLE, service.availability(PLAYER, first));
        assertSame(QuestService.Availability.LOCKED, service.availability(PLAYER, second));
        assertSame(QuestService.Result.LOCKED, service.claim(PLAYER, "elder_mine", 0, DAY));
        assertSame(first, service.current(PLAYER, service.npcs().get(0)));
        assertEquals(List.of("elder_kill", "scout_first", "farmer_wheat"),
                service.currentQuests(PLAYER).stream().map(Quest::id).toList(),
                "у каждого персонажа своё текущее задание");
    }

    @Test
    void killQuestMovesAndClaimsOnce(@TempDir Path dir) {
        QuestService service = service(dir);
        assertSame(QuestService.Result.NOT_READY, service.claim(PLAYER, "elder_kill", 0, DAY));
        service.store().addProgress(PLAYER, "elder_kill", 1, 2);
        assertSame(QuestService.Availability.IN_PROGRESS,
                service.availability(PLAYER, service.quest("elder_kill")));
        assertFalse(service.ready(PLAYER, service.quest("elder_kill")));

        service.store().addProgress(PLAYER, "elder_kill", 1, 2);
        assertEquals(2, service.progress(PLAYER, service.quest("elder_kill")));
        assertTrue(service.ready(PLAYER, service.quest("elder_kill")));
        assertSame(QuestService.Availability.READY,
                service.availability(PLAYER, service.quest("elder_kill")));

        assertSame(QuestService.Result.OK, service.claim(PLAYER, "elder_kill", 0, DAY));
        assertTrue(service.store().done(PLAYER, "elder_kill"));
        assertSame(QuestService.Result.DONE, service.claim(PLAYER, "elder_kill", 0, DAY));
        assertSame(QuestService.Availability.DONE,
                service.availability(PLAYER, service.quest("elder_kill")));
        // Цепочка открыла следующее задание.
        assertSame(QuestService.Availability.AVAILABLE,
                service.availability(PLAYER, service.quest("elder_mine")));
    }

    @Test
    void reputationGoesToNpcFactionOrPlayerFaction(@TempDir Path dir) {
        QuestService service = service(dir);
        // Нейтральный NPC без фракции у игрока: очки некуда писать.
        assertEquals("", service.rewardFaction(PLAYER, service.quest("elder_kill")));
        service.join(PLAYER, "guild");
        assertEquals("guild", service.rewardFaction(PLAYER, service.quest("elder_kill")),
                "нейтральный NPC кормит текущую фракцию игрока");

        service.store().addProgress(PLAYER, "elder_kill", 2, 2);
        assertSame(QuestService.Result.OK, service.claim(PLAYER, "elder_kill", 0, DAY));
        assertEquals(5, service.store().reputation(PLAYER, "guild"));
        assertEquals(5, service.reputation(PLAYER));

        // Задание Ордена требует быть именно в Ордене.
        Quest scout = service.quest("scout_first");
        assertEquals("watch", service.rewardFaction(PLAYER, scout));
        assertSame(QuestService.Availability.WRONG_FACTION, service.availability(PLAYER, scout));
        assertSame(QuestService.Result.WRONG_FACTION, service.claim(PLAYER, "scout_first", 0, DAY));
        service.leaveFaction(PLAYER);
        service.join(PLAYER, "watch");
        assertSame(QuestService.Availability.AVAILABLE, service.availability(PLAYER, scout));
    }

    @Test
    void rankAndChainGuardTheChain(@TempDir Path dir) {
        QuestService service = service(dir);
        service.join(PLAYER, "watch");
        Quest ranked = service.quest("scout_rank");
        assertNotNull(ranked);
        // Первое задание Ордена ещё не закрыто.
        assertSame(QuestService.Availability.LOCKED, service.availability(PLAYER, ranked));

        service.store().addProgress(PLAYER, "scout_first", 1, 1);
        assertSame(QuestService.Result.OK, service.claim(PLAYER, "scout_first", 0, DAY));
        assertEquals(100, service.reputation(PLAYER));
        assertEquals(1, service.rankNumber(PLAYER), "100 очков — ещё новичок");
        assertSame(QuestService.Availability.RANK, service.availability(PLAYER, ranked));
        assertSame(QuestService.Result.RANK, service.claim(PLAYER, "scout_rank", 0, DAY));

        service.store().addReputation(PLAYER, "watch", 150);
        assertEquals(2, service.rankNumber(PLAYER), "250 очков — второй ранг");
        assertSame(QuestService.Availability.AVAILABLE, service.availability(PLAYER, ranked));
    }

    @Test
    void gatherNeedsItemsAndKeepsNoProgress(@TempDir Path dir) {
        QuestService service = service(dir);
        Quest wheat = service.quest("farmer_wheat");
        assertNotNull(wheat);
        assertEquals(0, service.progress(PLAYER, wheat), "GATHER не двигается событиями");
        assertFalse(service.ready(PLAYER, wheat));
        assertSame(QuestService.Availability.AVAILABLE, service.availability(PLAYER, wheat));
        assertSame(QuestService.Availability.IN_PROGRESS, service.availability(PLAYER, wheat, 2));
        assertSame(QuestService.Availability.READY, service.availability(PLAYER, wheat, 4));

        assertSame(QuestService.Result.NO_ITEMS, service.claim(PLAYER, "farmer_wheat", 3, DAY));
        assertSame(QuestService.Result.OK, service.claim(PLAYER, "farmer_wheat", 4, DAY));
        assertTrue(service.store().done(PLAYER, "farmer_wheat"));
    }

    @Test
    void dailyLimitStopsExtraClaims(@TempDir Path dir) {
        QuestService service = service(dir, CONFIG.replace("claims-per-day: 3",
                "claims-per-day: 1"));
        assertEquals(1, service.claimsLeft(PLAYER, DAY));
        service.store().addProgress(PLAYER, "elder_kill", 2, 2);
        assertSame(QuestService.Result.OK, service.claim(PLAYER, "elder_kill", 0, DAY));
        assertEquals(0, service.claimsLeft(PLAYER, DAY));
        assertEquals(1, service.claimsLeft(PLAYER, "2026-10-25"), "новый день — снова можно");

        service.store().addProgress(PLAYER, "elder_mine", 3, 3);
        assertSame(QuestService.Result.DAILY_LIMIT, service.claim(PLAYER, "elder_mine", 0, DAY));
        assertFalse(service.store().done(PLAYER, "elder_mine"), "отказ не закрывает задание");
        assertSame(QuestService.Result.OK,
                service.claim(PLAYER, "elder_mine", 0, "2026-10-25"));
    }

    @Test
    void countersAndLookups(@TempDir Path dir) {
        QuestService service = service(dir);
        assertEquals(5, service.totalQuests());
        assertEquals(List.of("elder_kill", "elder_mine", "scout_first", "scout_rank",
                "farmer_wheat"), service.questIds());
        assertNull(service.find("нет"));
        assertNull(service.quest(null));
        assertEquals(0, service.doneCount(PLAYER));
        assertEquals(0, service.readyCount(PLAYER));

        service.store().addProgress(PLAYER, "elder_kill", 2, 2);
        assertEquals(1, service.readyCount(PLAYER));
        service.store().markDone(PLAYER, "elder_kill");
        assertEquals(1, service.doneCount(PLAYER));
        assertEquals(0, service.readyCount(PLAYER));

        assertEquals(3, service.npcs().size(), "персонажи из конфига");
        assertNull(service.current(PLAYER, null));
    }

    @Test
    void dayIsUtcDate() {
        assertEquals("1970-01-01", QuestService.day(0L));
        assertEquals("2026-10-24", QuestService.day(1_792_800_000_000L));
        assertEquals("2026-10-23", QuestService.day(1_792_800_000_000L - 1));
        assertSame("ok", QuestService.normalize(" WATCH ").equals("watch") ? "ok" : "bad");
    }
}
