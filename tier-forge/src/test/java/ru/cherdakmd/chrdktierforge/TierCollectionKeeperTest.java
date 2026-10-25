package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Хранилище книг коллекции в stats.yml (1.4.150). */
class TierCollectionKeeperTest {

    @Test
    void recordIsPerPlayer() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String property = TierProperties.byTier("common").get(0).id();

        assertFalse(keeper.record(alice, "common", List.of(property), List.of()).isEmpty());
        assertTrue(keeper.record(alice, "common", List.of(property), List.of()).isEmpty());
        assertEquals("common", keeper.record(bob, "common", List.of(), List.of()).tier(),
                "у второго игрока та же редкость — новинка");

        assertEquals(2, keeper.snapshot(alice).total(), "редкость + свойство");
        assertEquals(1, keeper.snapshot(bob).total());
        assertEquals(2, keeper.players());
    }

    @Test
    void unknownPlayerGetsEmptySnapshot() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        TierCollectionBook.Snapshot snapshot = keeper.snapshot(UUID.randomUUID());
        assertEquals(0, snapshot.total());
        assertEquals(0, snapshot.percent());
        assertFalse(snapshot.hasTier("rare"));
        assertEquals(0, keeper.snapshot(null).total(), "null — пустой снимок, а не падение");
        assertTrue(keeper.record(null, "rare", List.of(), List.of()).isEmpty());
    }

    @Test
    void claimsAreTrackedPerPlayer() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID player = UUID.randomUUID();
        keeper.record(player, "cursed", List.of(), List.of());

        assertTrue(keeper.claim(player, "tiers"));
        assertFalse(keeper.claim(player, "tiers"));
        assertTrue(keeper.isClaimed(player, "tiers"));
        assertFalse(keeper.isClaimed(UUID.randomUUID(), "tiers"),
                "чужую веху закрыть нельзя");
        assertFalse(keeper.claim(null, "tiers"));
    }

    @Test
    void writeAndLoadSurviveRestart() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String property = TierProperties.byTier("epic").get(2).id();
        String affix = TierAffixes.bad().get(1).id();
        keeper.record(first, "epic", List.of(property), List.of(affix));
        keeper.claim(first, "properties:15");
        keeper.record(second, "weak", List.of(), List.of());

        YamlConfiguration yaml = new YamlConfiguration();
        keeper.writeTo(yaml.createSection("collection"));

        TierCollectionKeeper restored = new TierCollectionKeeper();
        restored.load(yaml.getConfigurationSection("collection"));

        assertEquals(2, restored.players());
        assertEquals(3, restored.snapshot(first).total(), "редкость + свойство + аффикс");
        assertTrue(restored.isClaimed(first, "properties:15"));
        assertEquals(1, restored.snapshot(second).total());
    }

    @Test
    void brokenKeysAreSkippedOnLoad() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.not-a-uuid.tiers", List.of("rare"));
        UUID player = UUID.randomUUID();
        yaml.set("collection." + player + ".tiers", List.of("rare"));

        TierCollectionKeeper keeper = new TierCollectionKeeper();
        keeper.load(yaml.getConfigurationSection("collection"));

        assertEquals(1, keeper.players(), "строка без UUID не создаёт книгу");
        assertEquals(1, keeper.snapshot(player).total());
    }

    @Test
    void loadWithoutSectionAndWriteToNullAreSafe() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID player = UUID.randomUUID();
        keeper.record(player, "rare", List.of(), List.of());
        keeper.load(null);
        keeper.writeTo(null);
        assertEquals(1, keeper.players(), "load(null) ничего не стирает");

        keeper.clear();
        assertEquals(0, keeper.players());
    }

    @Test
    void progressIsCheapAndSafe() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID player = UUID.randomUUID();
        assertEquals(TierCollectionBook.Progress.EMPTY, keeper.progress(player));
        assertEquals(TierCollectionBook.Progress.EMPTY, keeper.progress(null));

        keeper.record(player, "rare", List.of(TierProperties.byTier("rare").get(0).id()), List.of());
        TierCollectionBook.Progress progress = keeper.progress(player);
        assertEquals(1, progress.tiers());
        assertEquals(1, progress.properties());
        assertEquals(0, progress.affixes());
        assertEquals(2, progress.total());
        assertEquals(1, progress.sectionPercent("all"), "2 из 142 — 1%");
    }

    @Test
    void topRanksByTotalThenTiers() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID small = UUID.randomUUID();
        UUID big = UUID.randomUUID();
        UUID tieTiers = UUID.randomUUID();
        UUID tieProps = UUID.randomUUID();

        keeper.record(small, "cursed", List.of(), List.of());
        keeper.record(big, "cursed", List.of(TierProperties.byTier("cursed").get(0).id()),
                List.of(TierAffixes.all().get(0).id()));
        // Равное «всего» (2), но у tieTiers обе позиции — редкости.
        keeper.record(tieTiers, "rare", List.of(), List.of());
        keeper.record(tieTiers, "epic", List.of(), List.of());
        keeper.record(tieProps, "rare", List.of(TierProperties.byTier("rare").get(1).id()),
                List.of());

        List<TierCollectionKeeper.Row> rows = keeper.top(10);
        assertEquals(4, rows.size());
        assertEquals(big, rows.get(0).player(), "3 позиции — первое место");
        assertEquals(3, rows.get(0).total());
        assertEquals(tieTiers, rows.get(1).player(), "при равенстве выигрывает больше редкостей");
        assertEquals(tieProps, rows.get(2).player());
        assertEquals(small, rows.get(3).player());

        assertEquals(2, keeper.top(2).size());
        assertTrue(keeper.top(0).isEmpty());
    }

    @Test
    void emptyBooksDoNotEnterTop() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        keeper.record(UUID.randomUUID(), null, List.of(), List.of());
        assertTrue(keeper.top(10).isEmpty(), "книга без находок в рейтинг не попадает");
    }

    @Test
    void titleChoiceNeedsABook() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID player = UUID.randomUUID();
        assertFalse(keeper.chooseTitle(player, "tiers"), "без книги выбирать нечего");
        assertNull(keeper.titleChoice(player));
        assertFalse(keeper.chooseTitle(null, "tiers"));

        keeper.record(player, "rare", List.of(), List.of());
        assertTrue(keeper.chooseTitle(player, "off"));
        assertEquals("off", keeper.titleChoice(player));
        assertTrue(keeper.chooseTitle(player, "auto"));
        assertNull(keeper.titleChoice(player));
    }

    @Test
    void choiceSurvivesSaveAndLoad() {
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        UUID player = UUID.randomUUID();
        for (String tier : TierDef.LADDER) {
            keeper.record(player, tier, List.of(), List.of());
        }
        assertTrue(keeper.chooseTitle(player, "tiers"));

        YamlConfiguration yaml = new YamlConfiguration();
        keeper.writeTo(yaml.createSection("collection"));
        TierCollectionKeeper restored = new TierCollectionKeeper();
        restored.load(yaml.getConfigurationSection("collection"));
        assertEquals("tiers", restored.titleChoice(player));
    }

    @Test
    void emptyBooksAreNotRestored() {
        YamlConfiguration yaml = new YamlConfiguration();
        UUID empty = UUID.randomUUID();
        yaml.createSection("collection." + empty);
        TierCollectionKeeper keeper = new TierCollectionKeeper();
        keeper.load(yaml.getConfigurationSection("collection"));
        assertEquals(0, keeper.players(), "пустая секция не считается прогрессом");
    }
}
