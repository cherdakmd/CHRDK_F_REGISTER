package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Книга коллекции кузни: находки, вехи, сохранение (1.4.150). */
class TierCollectionBookTest {

    @Test
    void catalogTotalsMatchRegistries() {
        assertEquals(7, TierCollectionBook.TIER_TOTAL);
        assertEquals(15 * TierDef.LADDER.size(), TierCollectionConfig.PROPERTY_TOTAL);
        assertEquals(30, TierCollectionConfig.AFFIX_TOTAL);
        assertEquals(7 + 105 + 30, TierCollectionBook.CATALOG_TOTAL);
    }

    @Test
    void firstDiscoveryIsNewSecondIsSilent() {
        TierCollectionBook book = new TierCollectionBook();
        String property = TierProperties.byTier("rare").get(0).id();
        String affix = TierAffixes.all().get(0).id();

        TierCollectionBook.Discovery first =
                book.discover("rare", List.of(property), List.of(affix));
        assertFalse(first.isEmpty());
        assertEquals("rare", first.tier());
        assertEquals(List.of(property), first.properties());
        assertEquals(List.of(affix), first.affixes());
        assertEquals(3, first.total());

        assertTrue(book.discover("rare", List.of(property), List.of(affix)).isEmpty(),
                "повторная встреча той же позиции не считается новинкой");
        assertEquals(3, book.total(), "редкость + свойство + аффикс — по одной записи");
    }

    @Test
    void unknownIdsAreIgnored() {
        TierCollectionBook book = new TierCollectionBook();
        TierCollectionBook.Discovery discovery = book.discover("mythic",
                List.of("no-such-property"), List.of("no-such-affix"));
        assertTrue(discovery.isEmpty(), "незнакомые иды не должны попадать в книгу");
        assertEquals(0, book.total());
        assertEquals(0, book.percent());
    }

    @Test
    void nullArgumentsAreSafe() {
        TierCollectionBook book = new TierCollectionBook();
        assertTrue(book.discover(null, null, null).isEmpty());
        assertEquals(0, book.tierCount());
        assertFalse(book.hasTier(null));
        assertFalse(book.hasProperty(null));
        assertFalse(book.hasAffix(null));
        assertFalse(book.claim(null));
        assertFalse(book.claim(" "));
    }

    @Test
    void milestoneIsPaidOnce() {
        TierCollectionBook book = new TierCollectionBook();
        assertTrue(book.claim("properties:15"));
        assertFalse(book.claim("properties:15"), "вторая выплата невозможна");
        assertTrue(book.isClaimed("properties:15"));
        assertFalse(book.isClaimed("properties:45"));
        assertEquals(Set.of("properties:15"), book.claimed());
    }

    @Test
    void percentUsesWholeCatalogAndCapsAtHundred() {
        assertEquals(0, TierCollectionBook.percentOf(0));
        assertEquals(0, TierCollectionBook.percentOf(-5));
        assertEquals(0, TierCollectionBook.percentOf(1), "142 позиции — меньше 1%");
        assertEquals(50, TierCollectionBook.percentOf(71));
        assertEquals(100, TierCollectionBook.percentOf(TierCollectionBook.CATALOG_TOTAL));
        assertEquals(100, TierCollectionBook.percentOf(9999), "больше каталога не бывает");
    }

    @Test
    void snapshotIsIndependentCopy() {
        TierCollectionBook book = new TierCollectionBook();
        String property = TierProperties.byTier("legendary").get(0).id();
        book.discover("legendary", List.of(property), List.of());
        TierCollectionBook.Snapshot snapshot = book.snapshot();

        book.discover("cursed", List.of(), List.of());

        assertEquals(1, snapshot.tierCount(), "снимок не должен меняться задним числом");
        assertEquals(2, book.tierCount());
        assertTrue(snapshot.hasTier("legendary"));
        assertFalse(snapshot.hasTier("cursed"));
    }

    @Test
    void writeAndReadRoundTripInCatalogOrder() {
        TierCollectionBook book = new TierCollectionBook();
        List<String> properties = List.of(
                TierProperties.byTier("legendary").get(3).id(),
                TierProperties.byTier("cursed").get(0).id());
        book.discover("epic", properties, List.of(TierAffixes.all().get(4).id()));
        book.claim("affixes:10");

        YamlConfiguration yaml = new YamlConfiguration();
        book.writeTo(yaml.createSection("collection"));
        List<String> stored = yaml.getStringList("collection.properties");
        assertEquals(2, stored.size());
        assertEquals(TierProperties.byTier("cursed").get(0).id(), stored.get(0),
                "порядок — по каталогу, а не по времени находки");

        TierCollectionBook restored = new TierCollectionBook();
        restored.read(yaml.getConfigurationSection("collection"));
        assertEquals(book.snapshot().total(), restored.total());
        assertTrue(restored.hasTier("epic"));
        assertTrue(restored.isClaimed("affixes:10"));
    }

    @Test
    void corruptFileDoesNotBreakProgress() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.tiers", List.of("rare", "mythic", "rare"));
        yaml.set("collection.properties", List.of(TierProperties.all().get(0).id(), "удалённое"));
        yaml.set("collection.affixes", List.of("ghost-affix"));

        TierCollectionBook book = new TierCollectionBook();
        book.read(yaml.getConfigurationSection("collection"));

        assertEquals(1, book.tierCount(), "дубли и незнакомцы отброшены");
        assertEquals(1, book.propertyCount());
        assertEquals(0, book.affixCount());
        assertEquals(0, book.claimed().size());
    }

    @Test
    void readWithoutSectionClearsBook() {
        TierCollectionBook book = new TierCollectionBook();
        book.discover("rare", List.of(), List.of());
        book.claim("tiers");
        book.read(null);
        assertEquals(0, book.total());
        assertTrue(book.claimed().isEmpty());
    }

    @Test
    void progressCountsSectionsAndPercent() {
        TierCollectionBook book = new TierCollectionBook();
        assertEquals(0, book.progress().total());
        assertEquals(0, book.progress().sectionPercent("tiers"));

        for (String tier : TierDef.LADDER) {
            book.discover(tier, List.of(), List.of());
        }
        TierCollectionBook.Progress progress = book.progress();
        assertEquals(7, progress.tiers());
        assertEquals(7, progress.total());
        assertTrue(progress.complete("tiers"));
        assertFalse(progress.complete("affixes"));
        assertFalse(progress.complete("properties"));
        assertFalse(progress.complete("all"));
        assertEquals(7, progress.count("all"));
        assertEquals(100, progress.sectionPercent("tiers"));
        assertEquals(4, progress.sectionPercent("all"), "7 из 142 — это 4%");
        assertEquals(0, progress.count("unknown"));

        TierCollectionBook.Snapshot snapshot = book.snapshot();
        assertEquals(progress, snapshot.progress());
        assertTrue(snapshot.complete("tiers"));
    }

    @Test
    void progressComparesWithThresholds() {
        TierCollectionBook.Progress empty = TierCollectionBook.Progress.EMPTY;
        assertEquals(0, empty.total());
        assertEquals(0, empty.percent());
        assertFalse(empty.complete("all"));
        assertEquals(0, empty.sectionPercent("all"));

        TierCollectionBook.Progress full = new TierCollectionBook.Progress(
                TierCollectionBook.TIER_TOTAL,
                TierCollectionConfig.PROPERTY_TOTAL,
                TierCollectionConfig.AFFIX_TOTAL);
        assertTrue(full.complete("tiers"));
        assertTrue(full.complete("affixes"));
        assertTrue(full.complete("properties"));
        assertTrue(full.complete("all"));
        assertEquals(100, full.percent());
        assertEquals(TierCollectionBook.CATALOG_TOTAL, full.total());
    }

    @Test
    void discoveryKeepsAllNewPositionsOfOneItem() {
        TierCollectionBook book = new TierCollectionBook();
        List<String> pool = new ArrayList<>();
        for (TierProperty property : TierProperties.byTier("fine")) {
            pool.add(property.id());
        }
        TierCollectionBook.Discovery discovery = book.discover("fine",
                pool.subList(0, 2), List.of(TierAffixes.good().get(0).id(), TierAffixes.bad().get(0).id()));
        assertEquals(2, discovery.properties().size());
        assertEquals(2, discovery.affixes().size());
        assertEquals(5, discovery.total(), "редкость + 2 свойства + 2 аффикса = 5 позиций");
    }
}
