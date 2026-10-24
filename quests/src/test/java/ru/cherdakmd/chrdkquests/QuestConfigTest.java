package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml квестов (1.4.161): умолчания, клампы, деградация опечаток
 * и сборка цепочек.
 */
class QuestConfigTest {

    private static final Logger LOGGER = Logger.getLogger("QuestConfigTest");

    private static QuestConfig parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return QuestConfig.parse(config, LOGGER);
    }

    @Test
    void emptyConfigUsesDefaults() {
        QuestConfig config = parse("");
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertTrue(config.actionbar());
        assertTrue(config.factionPrefix());
        assertEquals(1, config.prefixFromRank());
        assertEquals(10, config.claimsPerDay());
        assertTrue(config.factions().isEmpty());
        assertTrue(config.npcList().isEmpty());
        assertEquals(0, config.totalQuests());
        assertNull(config.faction("watch"));
        assertNull(config.npc("elder"));
        assertNull(config.quest("q"));
        assertEquals(0, config.allQuests().size());
        assertEquals(0, config.factionIds().size());
        assertEquals(0, config.npcIds().size());
    }

    @Test
    void generalClampsAreApplied() {
        QuestConfig config = parse("""
                general:
                  enabled: false
                  locale: " EN "
                  actionbar: false
                  faction-prefix: false
                  prefix-from-rank: 99
                  claims-per-day: 0
                """);
        assertFalse(config.enabled());
        assertEquals("en", config.locale());
        assertFalse(config.actionbar());
        assertFalse(config.factionPrefix());
        assertEquals(10, config.prefixFromRank(), "ранг префикса зажат в 1..10");
        assertEquals(1, config.claimsPerDay(), "лимит сдач не меньше одной");
    }

    @Test
    void factionsParseRanksAndIcons() {
        QuestConfig config = parse("""
                factions:
                  watch:
                    name: "Орден"
                    description: "Стены"
                    icon: SHIELD
                    prefix: "<red>[С]</red> "
                    ranks:
                      0: novice
                      100: warrior
                  broken:
                    icon: НЕТ_ТАКОГО
                    ranks: {}
                """);
        assertEquals(2, config.factions().size());
        QuestFaction watch = config.faction(" WATCH ");
        assertNotNull(watch);
        assertEquals("Орден", watch.name());
        assertEquals("Стены", watch.description());
        assertSame(Material.SHIELD, watch.icon());
        assertTrue(watch.hasPrefix());
        assertEquals("novice", watch.rankId(0));
        assertEquals("warrior", watch.rankId(100));

        QuestFaction broken = config.faction("broken");
        assertNotNull(broken);
        assertSame(Material.SHIELD, broken.icon(), "неизвестная иконка — щит по умолчанию");
        assertEquals(QuestFaction.DEFAULT_RANKS, broken.ranks(),
                "пустая лестница заменяется стандартной");
        assertEquals("broken", broken.name(), "пустое имя заменяется идом");
    }

    @Test
    void npcWithUnknownFactionBecomesNeutral() {
        QuestConfig config = parse("""
                factions:
                  watch:
                    name: "Орден"
                npcs:
                  elder:
                    name: "Старейшина"
                    faction: "нет-такой"
                    quests:
                      - id: elder_first
                        type: KILL
                        targets: [ZOMBIE]
                        amount: 3
                        xp: 50
                """);
        QuestNpc npc = config.npc("elder");
        assertNotNull(npc);
        assertTrue(npc.neutral(), "опечатка во фракции не запирает цепочку");
    }

    @Test
    void typosInTargetsDegradeTheQuest() {
        QuestConfig config = parse("""
                npcs:
                  elder:
                    quests:
                      - id: good
                        type: KILL
                        targets: [ZOMBIE, НЕ_МОБ, minecraft:skeleton]
                        amount: 2
                        xp: 10
                      - id: no_targets
                        type: KILL
                        targets: [НЕ_МОБ]
                        amount: 2
                        xp: 10
                      - id: no_reward
                        type: MINE
                        targets: [STONE]
                        amount: 2
                      - id: bad_type
                        type: ПОЙМАТЬ
                        targets: [ZOMBIE]
                        xp: 10
                      - type: KILL
                        targets: [ZOMBIE]
                        xp: 10
                      - id: mining
                        type: MINE
                        targets: [minecraft:iron_ore, ВОЗДУХ]
                        amount: 5
                        xp: 10
                        reputation: 5
                """);
        QuestNpc npc = config.npc("elder");
        assertNotNull(npc);
        List<String> ids = npc.quests().stream().map(Quest::id).toList();
        assertEquals(List.of("good", "mining"), ids,
                "нерабочие задания не попадают в цепочку");

        Quest good = config.quest("good");
        assertNotNull(good);
        assertEquals(List.of(EntityType.ZOMBIE, EntityType.SKELETON), good.entities());
        Quest mining = config.quest("mining");
        assertNotNull(mining);
        assertEquals(List.of(Material.IRON_ORE), mining.materials());
        assertEquals(5, mining.reward().reputation());
    }

    @Test
    void duplicateQuestIdsAreSkipped() {
        QuestConfig config = parse("""
                npcs:
                  first:
                    quests:
                      - id: same
                        type: KILL
                        targets: [ZOMBIE]
                        xp: 10
                  second:
                    quests:
                      - id: same
                        type: KILL
                        targets: [SKELETON]
                        xp: 10
                      - id: second_own
                        type: KILL
                        targets: [CREEPER]
                        xp: 10
                """);
        assertEquals(2, config.totalQuests());
        assertEquals(EntityType.ZOMBIE, config.quest("same").entities().get(0),
                "остаётся первое объявление");
        assertEquals(1, config.npc("second").quests().size());
        assertEquals("second_own", config.npc("second").quests().get(0).id());
    }

    @Test
    void rewardsAndRankParsedFromMapItems() {
        QuestConfig config = parse("""
                npcs:
                  elder:
                    quests:
                      - id: q
                        name: "Имя"
                        description: "Описание"
                        type: GATHER
                        targets: [WHEAT]
                        amount: 16
                        xp: 120
                        reputation: 15
                        min-rank: 3
                        items:
                          BREAD: 4
                          НЕТ: 2
                          ARROW: 999
                """);
        Quest quest = config.quest("q");
        assertNotNull(quest);
        assertEquals("Имя", quest.name());
        assertEquals("Описание", quest.description());
        assertEquals(16, quest.amount());
        assertEquals(3, quest.minRank());
        assertEquals(120, quest.reward().xp());
        assertEquals(15, quest.reward().reputation());
        assertEquals(2, quest.reward().items().size());
        assertEquals(Material.BREAD, quest.reward().items().get(0).material());
        assertEquals(QuestReward.MAX_ITEM_AMOUNT, quest.reward().items().get(1).amount());
    }

    @Test
    void allQuestsAndIdsFollowConfigOrder() {
        QuestConfig config = parse("""
                factions:
                  one:
                    name: "Первая"
                  two:
                    name: "Вторая"
                npcs:
                  elder:
                    quests:
                      - id: a
                        type: KILL
                        targets: [ZOMBIE]
                        xp: 1
                      - id: b
                        type: KILL
                        targets: [ZOMBIE]
                        xp: 1
                  brock:
                    quests:
                      - id: c
                        type: MINE
                        targets: [STONE]
                        xp: 1
                """);
        assertEquals(List.of("one", "two"), config.factionIds());
        assertEquals(List.of("elder", "brock"), config.npcIds());
        assertEquals(List.of("a", "b", "c"),
                config.allQuests().stream().map(Quest::id).toList());
        assertEquals(3, config.totalQuests());
    }

    @Test
    void nullConfigFallsBackToDefaults() {
        QuestConfig config = QuestConfig.parse(null, LOGGER);
        assertTrue(config.enabled());
        assertEquals(10, config.claimsPerDay());
        assertEquals(0, config.totalQuests());
    }
}
