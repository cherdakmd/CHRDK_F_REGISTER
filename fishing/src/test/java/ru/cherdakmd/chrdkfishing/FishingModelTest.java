package ru.cherdakmd.chrdkfishing;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkfishing.FishingModel.*;

class FishingModelTest {
    @Test void catalogIsCompleteAndEachRarityCanBeSelected() {
        assertEquals(92, FishCatalog.all().size());
        assertEquals(54, FishCatalog.all().stream().filter(Species::fictional).count());
        assertEquals(92, new HashSet<>(FishCatalog.all().stream().map(Species::id).toList()).size());
        for (Rarity rarity : Rarity.values()) assertFalse(FishCatalog.byRarity(rarity).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> FishCatalog.all().clear());
    }

    @Test void eachStoredLengthAndSizeMustMatchTheCatalog() {
        for (Species species : FishCatalog.all()) {
            for (Size size : Size.values()) {
                Range range = species.lengths().get(size.ordinal());
                assertEquals(species, validate(new Catch(species.id(), size, range.min())));
                assertEquals(species, validate(new Catch(species.id(), size, range.max())));
                assertThrows(IllegalArgumentException.class, () -> validate(new Catch(species.id(), size, range.max() + 1)));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> validate(new Catch("unknown", Size.NORMAL, 10)));
    }

    @Test void priceIsBasedOnCategoryNotExactLength() {
        assertEquals(900, price(new Catch("whitefish", Size.SMALL, 15)));
        assertEquals(1200, price(new Catch("whitefish", Size.NORMAL, 25)));
        assertEquals(1800, price(new Catch("whitefish", Size.LARGE, 40)));
        assertEquals(3600, price(new Catch("whitefish", Size.TROPHY, 55)));
        assertEquals(3600, price(new Catch("whitefish", Size.TROPHY, 75)));
        assertEquals(190, price(new Catch("roach", Size.SMALL, 8))); // previous 18.75 rounds to 19, then ×10
    }

    @Test void everyNewCatchIsExactlyTenTimesTheOldRoundedPrice() {
        int[] numerators = {3, 4, 6, 12};
        for (var species : FishCatalog.all()) for (var size : Size.values()) {
            var fish = new Catch(species.id(), size, species.lengths().get(size.ordinal()).min());
            var reference = FishCatalog.priceReference(species.id());
            long old = ((reference == null ? species.basePrice() : reference.basePrice()) * numerators[size.ordinal()] + 2) / 4;
            assertEquals(old * (reference == null ? 10 : 100), price(fish), species.id() + " " + size);
            assertTrue(price(fish) <= FishToken.MAX_PRICE_POINTS);
        }
    }
    @Test void premiumFishAreExactlyTenTimesTheirReferenceAtEverySize() {
        assertEquals(47, FishCatalog.premium().size());
        for (var item : FishCatalog.premium()) for (var size : Size.values()) {
            var reference = FishCatalog.byId(item.referenceId());
            int length = reference.lengths().get(size.ordinal()).min();
            assertEquals(price(new Catch(reference.id(), size, length)) * 10,
                    price(new Catch(item.id(), size, length)), item.id() + " " + size);
        }
    }
    @Test void newGemMetalAndTerrariaInspiredFishAreMythicAndUsePremiumPricing() {
        var added = FishCatalog.all().subList(70, 92);
        assertEquals(22, added.size());
        assertEquals(16, added.stream().filter(species -> species.id().startsWith("terraria_")).count());
        for (String id : List.of("diamond_fish", "gold_fish", "silver_fish", "platinum_fish", "palladium_fish", "rhodium_fish"))
            assertTrue(added.stream().anyMatch(species -> species.id().equals(id)));
        for (var species : added) {
            assertEquals(Rarity.MYTHIC, species.rarity());
            assertNotNull(FishCatalog.priceReference(species.id()));
        }
    }
    @Test void oldSignedQuotesStayValidWhenPricesChange() {
        byte[] key = new byte[FishToken.KEY_BYTES];
        var fish = new Catch("whitefish", Size.NORMAL, 25);
        var old = FishToken.issue(key, java.util.UUID.randomUUID(), fish, 120);
        var fresh = FishToken.issue(key, java.util.UUID.randomUUID(), fish, price(fish));
        assertTrue(FishToken.verify(key, old));
        assertEquals(120, old.quotedPrice());
        assertEquals(1200, fresh.quotedPrice());
    }

    @Test void seededRollsProduceOnlyValidFishForEveryRodLevel() {
        var random = new Random(1839472);
        for (int rod = 1; rod <= 10; rod++) {
            for (int i = 0; i < 5000; i++) {
                Catch fish = roll(random, rod);
                assertNotNull(validate(fish));
                assertTrue(price(fish) > 0);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> roll(random, 0));
        assertThrows(IllegalArgumentException.class, () -> roll(random, 11));
    }

    @Test void rodTransfersOnlySmallProbabilityToLarge() {
        var basic = new int[4]; var max = new int[4];
        var r1 = new Random(2024); var r10 = new Random(2024);
        for (int i = 0; i < 100_000; i++) {
            basic[roll(r1, 1).size().ordinal()]++;
            max[roll(r10, 10).size().ordinal()]++;
        }
        assertTrue(basic[Size.SMALL.ordinal()] > max[Size.SMALL.ordinal()] + 8000);
        assertTrue(max[Size.LARGE.ordinal()] > basic[Size.LARGE.ordinal()] + 8000);
        assertTrue(Math.abs(basic[Size.NORMAL.ordinal()] - max[Size.NORMAL.ordinal()]) < 1000);
        assertTrue(Math.abs(basic[Size.TROPHY.ordinal()] - max[Size.TROPHY.ordinal()]) < 500);
        assertEquals(20, rod(10).xpBonusPercent());
        assertEquals(10, rods().size());
    }

    @Test void invalidDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Range(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new Species("x", "X", Rarity.COMMON, false, 1,
                List.of(new Range(1, 2), new Range(2, 3), new Range(4, 5), new Range(6, 7))));
        assertThrows(IllegalArgumentException.class, () -> new Rod(2, "X", 0, 31));
    }
}
