package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель квестов (1.4.161): виды, клампы награды, цепочки и ранги фракций.
 */
class QuestModelTest {

    private static QuestReward reward() {
        return new QuestReward(100, 10, List.of(new QuestReward.Item(Material.BREAD, 4)));
    }

    private static Quest kill(String id, int amount) {
        return new Quest(id, "elder", "Тест", "", QuestType.KILL,
                List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.ZOMBIE),
                List.of(), amount, reward(), 0);
    }

    @Test
    void typeParsesCaseAndSpaces() {
        assertSame(QuestType.KILL, QuestType.parse(" kill "));
        assertSame(QuestType.MINE, QuestType.parse("Mine"));
        assertSame(QuestType.GATHER, QuestType.parse("gather"));
        assertNull(QuestType.parse(null));
        assertNull(QuestType.parse(" "));
        assertNull(QuestType.parse("собрать"));
        assertEquals("gather", QuestType.GATHER.id());
    }

    @Test
    void rewardIsClampedAndFiltered() {
        QuestReward empty = new QuestReward(-5, -5, List.of(
                new QuestReward.Item(Material.AIR, 1),
                new QuestReward.Item(null, 3)));
        assertEquals(0, empty.xp());
        assertEquals(0, empty.reputation());
        assertTrue(empty.items().isEmpty(), "воздух не награда");
        assertTrue(empty.empty());
        assertEquals("", empty.summary());

        QuestReward huge = new QuestReward(1_000_000, 1_000_000,
                List.of(new QuestReward.Item(Material.ARROW, 1_000)));
        assertEquals(QuestReward.MAX_XP, huge.xp());
        assertEquals(QuestReward.MAX_REPUTATION, huge.reputation());
        assertEquals(QuestReward.MAX_ITEM_AMOUNT, huge.items().get(0).amount(),
                "стак не больше 64");
        assertFalse(huge.empty());
        assertEquals("+100000 xp, +10000 rep, ARROW x64", huge.summary());
    }

    @Test
    void rewardSummaryKeepsOrder() {
        assertEquals("+100 xp, +10 rep, BREAD x4", reward().summary());
        assertEquals("+5 xp", new QuestReward(5, 0, List.of()).summary());
        assertEquals("BREAD x1", new QuestReward(0, 0,
                List.of(new QuestReward.Item(Material.BREAD, 1))).summary());
        assertTrue(QuestReward.none().empty());
    }

    @Test
    void questNormalizesIdAndTargets() {
        Quest quest = kill("  Elder_First  ", 0);
        assertEquals("elder_first", quest.id());
        assertEquals("Тест", quest.name());
        assertEquals(1, quest.amount(), "ноль целей превращается в одну");
        assertEquals(List.of(EntityType.ZOMBIE, EntityType.SKELETON), quest.entities(),
                "дубли целей убраны");
        assertTrue(quest.usable());
        assertTrue(quest.matchesEntity(EntityType.ZOMBIE));
        assertFalse(quest.matchesEntity(EntityType.CREEPER));
        assertFalse(quest.matchesEntity(null));
        assertFalse(quest.matchesMaterial(Material.STONE));
        assertTrue(quest.belongsTo("ELDER"));
        assertTrue(quest.belongsTo(" elder "));
        assertFalse(quest.belongsTo("brock"));
        assertFalse(quest.belongsTo(null));
    }

    @Test
    void questWithoutTargetsIsNotUsable() {
        Quest empty = new Quest("q", "elder", "", "", QuestType.MINE, List.of(), List.of(),
                5, reward(), 0);
        assertFalse(empty.usable(), "шахтёрское задание без блоков невыполнимо");

        Quest noReward = new Quest("q", "elder", "", "", QuestType.KILL,
                List.of(EntityType.ZOMBIE), List.of(), 5, QuestReward.none(), 0);
        assertFalse(noReward.usable(), "задание без награды не выдаётся");

        Quest air = new Quest("q", "elder", "", "", QuestType.MINE,
                List.of(), List.of(Material.AIR, Material.STONE), 5, reward(), 0);
        assertEquals(List.of(Material.STONE), air.materials());
        assertTrue(air.usable());
    }

    @Test
    void questRankAndAmountAreClamped() {
        Quest quest = new Quest("q", "elder", " ", "", QuestType.KILL,
                List.of(EntityType.ZOMBIE), List.of(), 1_000_000, reward(), 99);
        assertEquals(Quest.MAX_AMOUNT, quest.amount());
        assertEquals(Quest.MAX_RANK, quest.minRank());
        assertEquals("q", quest.name(), "пустое имя заменяется идом");
        assertEquals("", quest.description());
    }

    @Test
    void factionRanksFollowThresholds() {
        QuestFaction faction = new QuestFaction("Watch", " ", "", null, "",
                QuestConfig.defaultRanks());
        assertEquals("watch", faction.id());
        assertEquals("watch", faction.name());
        assertSame(Material.PAPER, faction.icon());
        assertFalse(faction.hasPrefix());

        assertEquals("newcomer", faction.rankId(0));
        assertEquals("newcomer", faction.rankId(249));
        assertEquals("fighter", faction.rankId(250));
        assertEquals("marshal", faction.rankId(5_000), "выше крыши — маршал");
        assertEquals(1, faction.rankNumber(0));
        assertEquals(2, faction.rankNumber(250));
        assertEquals(5, faction.rankNumber(10_000));
        assertEquals(250, faction.nextThreshold(0));
        assertEquals(-1, faction.nextThreshold(3_000));
        assertEquals(150, faction.toNext(100));
        assertEquals(0, faction.toNext(9_999));
        assertEquals(3_000, faction.maxThreshold());
    }

    @Test
    void factionCustomLadderIsOrderedAndLimited() {
        TreeMap<Integer, String> ladder = new TreeMap<>();
        ladder.put(500, "high");
        ladder.put(0, "low");
        ladder.put(100, "mid");
        for (int index = 0; index < 20; index++) {
            ladder.put(1_000 + index, "extra" + index);
        }
        QuestFaction faction = new QuestFaction("f", "Фракция", "описание", Material.SHIELD,
                "<red>[Ф]</red> ", ladder);
        assertEquals("low", faction.rankId(0));
        assertEquals("mid", faction.rankId(100));
        assertEquals("high", faction.rankId(500), "порядок не зависит от вставки");
        assertTrue(faction.hasPrefix());
        assertTrue(faction.ranks().size() <= QuestFaction.MAX_RANKS);
    }

    @Test
    void factionWithoutLadderFallsBackToDefault() {
        QuestFaction faction = new QuestFaction("f", "Ф", "", null, null, new TreeMap<>());
        assertEquals(QuestFaction.DEFAULT_RANKS, faction.ranks());
        assertNotNull(faction.ranks().firstEntry());
    }

    @Test
    void npcChainKeepsOrderAndReassignsOwner() {
        Quest first = kill("first", 2);
        Quest second = new Quest("second", "чужой", "Второе", "", QuestType.MINE,
                List.of(), List.of(Material.STONE), 3, reward(), 0);
        QuestNpc npc = new QuestNpc("Elder", "Старейшина", "описание", Material.OAK_SIGN,
                "", List.of(first, second));
        assertEquals("elder", npc.id());
        assertEquals(2, npc.quests().size());
        assertEquals("elder", npc.quests().get(1).npcId(), "задание перепривязано к персонажу");
        assertTrue(npc.neutral());
        assertEquals(first, npc.quest("FIRST"));
        assertNull(npc.quest(null));
        assertNull(npc.quest("нет"));
        assertEquals(0, npc.indexOf("first"));
        assertEquals(1, npc.indexOf(" second "));
        assertEquals(-1, npc.indexOf("нет"));
    }

    @Test
    void npcDropsUnusableQuests() {
        Quest broken = new Quest("broken", "elder", "", "", QuestType.KILL,
                List.of(), List.of(), 1, reward(), 0);
        QuestNpc npc = new QuestNpc("elder", "Старейшина", "", Material.OAK_SIGN, "",
                List.of(broken, kill("good", 1)));
        assertEquals(1, npc.quests().size());
        assertEquals("good", npc.quests().get(0).id());
    }

    @Test
    void textTagsNeverBreakWithoutRegistry() {
        String entity = QuestText.entityTag(EntityType.ZOMBIE);
        assertTrue(entity.startsWith("<lang:") || entity.equals("zombie"), entity);
        String material = QuestText.materialTag(Material.IRON_ORE);
        assertTrue(material.startsWith("<lang:") || material.equals("iron ore"), material);
        assertEquals("?", QuestText.entityTag(null));
        assertEquals("?", QuestText.materialTag(null));
        assertEquals("?", QuestText.materialTag(Material.AIR));
        assertEquals("iron_ore", QuestText.plainMaterial(Material.IRON_ORE));
        assertEquals("?", QuestText.joinTags(null));
        assertEquals("?", QuestText.joinTags(List.of()));
        assertEquals("a, b", QuestText.joinTags(List.of("a", "b")));
    }
    @Test void questCurrencyCannotBeEnabledThroughCustomItems(){var reward=new QuestReward(10,0,List.of(new QuestReward.Item(Material.DIAMOND,1),new QuestReward.Item(Material.DIAMOND_BLOCK,1),new QuestReward.Item(Material.BREAD,2)));assertEquals(1,reward.items().size());assertEquals(Material.BREAD,reward.items().getFirst().material());}

}
