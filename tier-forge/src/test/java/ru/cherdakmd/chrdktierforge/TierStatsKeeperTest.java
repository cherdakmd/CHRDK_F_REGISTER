package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Статистика находок и её сериализация в stats.yml (1.4.81). */
class TierStatsKeeperTest {

    @Test
    void countsPerTierAndTotals() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID player = UUID.randomUUID();
        keeper.count(player, "rare");
        keeper.count(player, "rare");
        keeper.count(player, "legendary");
        keeper.count(UUID.randomUUID(), "cursed");

        assertEquals(2, keeper.totalOf(player, "rare"));
        assertEquals(1, keeper.totalOf(player, "legendary"));
        assertEquals(0, keeper.totalOf(player, "epic"));
        assertEquals(3, keeper.total(player));
        assertEquals(4, keeper.grandTotal());
        assertEquals(2, keeper.players());
    }

    @Test
    void unknownTierAndNullPlayerAreIgnored() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID player = UUID.randomUUID();
        keeper.count(player, "mythic");
        keeper.count(null, "rare");
        assertEquals(0, keeper.grandTotal(), "незнакомые тиры не должны попадать в статистику");
    }

    @Test
    void resetClearsOnlyOnePlayer() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        keeper.count(first, "fine");
        keeper.count(second, "rare");
        keeper.reset(first);
        assertEquals(0, keeper.total(first));
        assertEquals(1, keeper.total(second));
        assertEquals(1, keeper.grandTotal());
    }

    @Test
    void snapshotIsDeepCopy() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID player = UUID.randomUUID();
        keeper.count(player, "epic");
        Map<UUID, Map<String, Integer>> snapshot = keeper.snapshot();
        snapshot.get(player).put("epic", 999);
        assertEquals(1, keeper.totalOf(player, "epic"), "снимок не должен менять живой счётчик");
    }

    @Test
    void yamlRoundTripKeepsCounts() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID rich = UUID.randomUUID();
        UUID poor = UUID.randomUUID();
        keeper.count(rich, "cursed");
        keeper.count(rich, "rare");
        keeper.count(rich, "rare");
        keeper.count(poor, "legendary");

        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("players");
        keeper.writeTo(root);

        TierStatsKeeper restored = new TierStatsKeeper();
        restored.load(root);

        assertEquals(2, restored.totalOf(rich, "rare"));
        assertEquals(1, restored.totalOf(rich, "cursed"));
        assertEquals(1, restored.totalOf(poor, "legendary"));
        assertEquals(keeper.grandTotal(), restored.grandTotal());
        assertEquals(2, restored.players());
    }

    @Test
    void brokenEntriesAreSkipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("players");
        root.createSection("not-a-uuid").set("rare", 5);
        root.createSection(UUID.randomUUID().toString()).set("mythic", 4);
        root.createSection(UUID.randomUUID().toString()).set("rare", -3);
        root.createSection(UUID.randomUUID().toString()).set("rare", 2);

        TierStatsKeeper keeper = new TierStatsKeeper();
        keeper.load(root);

        assertEquals(2, keeper.grandTotal(), "битые UUID, неизвестные тиры и минусы отбрасываются");
        assertTrue(keeper.players() >= 1);
    }

    @Test
    void emptyInputsAreNoOp() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        keeper.load(null);
        keeper.writeTo(null);
        assertEquals(0, keeper.grandTotal());
    }
    @Test
    void topRanksByCountThenUuid() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        keeper.count(second, "rare");
        keeper.count(second, "rare");
        keeper.count(first, "rare");
        keeper.count(third, "common");

        List<TierStatsKeeper.TopEntry> all = keeper.top(null, 5);
        assertEquals(3, all.size());
        assertEquals(second, all.get(0).player(), "две находки — первое место");
        assertEquals(first, all.get(1).player(), "при равенстве решает UUID");
        assertEquals(third, all.get(2).player());
        assertEquals(2, all.get(0).count());
    }

    @Test
    void topByTierFiltersAndLimits() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        keeper.count(first, "rare");
        keeper.count(first, "rare");
        keeper.count(second, "rare");
        keeper.count(second, "common");

        List<TierStatsKeeper.TopEntry> rare = keeper.top("rare", 1);
        assertEquals(1, rare.size(), "лимит соблюдается");
        assertEquals(first, rare.get(0).player());
        assertEquals(2, rare.get(0).count());
        assertEquals(1, keeper.top("common", 5).size(), "другой тир — своя выборка");
        assertTrue(keeper.top("legendary", 5).isEmpty());
    }

    @Test
    void topHandlesZeroLimitAndUnknownTier() {
        TierStatsKeeper keeper = new TierStatsKeeper();
        keeper.count(UUID.randomUUID(), "rare");
        assertTrue(keeper.top(null, 0).isEmpty());
        assertTrue(keeper.top(null, -3).isEmpty());
        assertTrue(keeper.top("no-such-tier", 5).isEmpty());
    }
}
