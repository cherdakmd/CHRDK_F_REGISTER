package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ForgeMaterialTest {
    @Test void tierPricesGrowWithoutAddingAnEighthTier() {
        assertArrayEquals(new int[]{1,1,2,4,8,16}, java.util.stream.IntStream.rangeClosed(1,6).map(ForgeMaterial::ascendCost).toArray());
        assertArrayEquals(new int[]{9,12,15,18,21,24}, java.util.stream.IntStream.rangeClosed(1,6).map(ForgeMaterial::ascendXp).toArray());
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.ascendCost(0));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.ascendCost(7));
        assertEquals(7, TierDef.LADDER.size());
    }
    @Test void dropRatesAreBoundedAndInvalidProgressionCannotProduceMaterials() {
        assertEquals(1, ForgeMaterial.chance(1,1,0));
        assertEquals(2, ForgeMaterial.chance(1,30,0));
        assertEquals(6, ForgeMaterial.chance(1,30,1));
        assertEquals(100, ForgeMaterial.chance(200,30,1));
        assertEquals(0, ForgeMaterial.chance(Double.NaN,1,0));
        assertEquals(0, ForgeMaterial.chance(-1,1,0));
        assertEquals(0, ForgeMaterial.chance(1,0,0));
        assertEquals(0, ForgeMaterial.chance(1,31,0));
        assertEquals(0, ForgeMaterial.chance(1,10,99));
    }
    @Test void confirmationBindsBothFullInputsSlotWorldSettingsTimeAndLevels() {
        Object settings = new Object(); UUID world = UUID.randomUUID();
        var quote = new ForgeQuote<>("sword:pdc:durability", "crystals:16", 2, world, settings, 1000, 24);
        assertTrue(quote.matches("sword:pdc:durability", "crystals:16",2,world,settings,1000,24));
        assertFalse(quote.matches("sword:pdc:changed", "crystals:16",2,world,settings,900,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:15",2,world,settings,900,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:16",3,world,settings,900,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:16",2,UUID.randomUUID(),settings,900,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:16",2,world,new Object(),900,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:16",2,world,settings,1001,24));
        assertFalse(quote.matches("sword:pdc:durability", "crystals:16",2,world,settings,900,23));
    }
    @Test void sequenceCannotWrapOrGoBackwards() {
        assertEquals(1, ForgeMaterial.nextSequence(0));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.nextSequence(-1));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.nextSequence(Long.MAX_VALUE));
    }
    @Test void allThreeMaterialsHaveSharedInstructionsAndOnlyFiniteIds() throws Exception {
        var messages = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());
        assertEquals(3, ForgeMaterial.values().length);
        for (var material : ForgeMaterial.values()) {
            assertSame(material, ForgeMaterial.byId(material.id.toUpperCase(Locale.ROOT)));
            String lines = messages.getList("forge." + material.id + ".details").toString();
            assertTrue(lines.contains("основную руку"));
            assertTrue(lines.contains("вторую"));
            assertTrue(lines.contains("/tiers forge confirm"));
            assertTrue(lines.contains("XP"));
            assertTrue(messages.has("forge." + material.id + ".name"));
        }
        assertNull(ForgeMaterial.byId(null));
        assertNull(ForgeMaterial.byId("../ascend"));
        assertNull(ForgeMaterial.byId("<red>ascend"));
    }
    @Test void previewChoiceIsStableAcrossRepeatsButAdvancesAfterPaidSuccess() {
        long first = ForgeMaterial.choice("persistent-uid", 1, ForgeMaterial.REFORGE, "rare_edge").nextLong();
        for (int i = 0; i < 100; i++) assertEquals(first,
                ForgeMaterial.choice("persistent-uid", 1, ForgeMaterial.REFORGE, "RARE_EDGE").nextLong());
        assertNotEquals(first, ForgeMaterial.choice("persistent-uid", 2, ForgeMaterial.REFORGE, "rare_edge").nextLong());
    }
    @Test void stockFooterMigrationPreservesCustomAndEmptyWithoutRewritingFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var path = directory.resolve("messages.yml");
        byte[] legacy;
        try (var input = getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc8.yml")) {
            assertNotNull(input); legacy = input.readAllBytes();
        }
        java.nio.file.Files.write(path,legacy);
        var logger = Logger.getAnonymousLogger();
        var fresh = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),null,logger);
        var migrated = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc8.yml"),logger);
        assertEquals(fresh.raw("list-footer"),migrated.raw("list-footer"));
        assertArrayEquals(legacy,java.nio.file.Files.readAllBytes(path));
        for (String custom : List.of("Свой текст", "")) {
            String yaml = "tiers:\n  list-footer: '" + custom + "'\n";
            java.nio.file.Files.writeString(path,yaml);
            var retained = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                    getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc8.yml"),logger);
            assertEquals(custom,retained.raw("list-footer"));
            assertEquals(yaml,java.nio.file.Files.readString(path));
        }
    }
    @Test void propertyReplacementPoolStaysInTierRoleAndAvoidsExistingProperties() {
        for (String tier : TierDef.LADDER) for (ItemKind kind : ItemKind.values()) {
            var properties = TierProperties.roll(tier,kind,1,new Random(1));
            var candidates = TierProperties.candidates(tier,kind).stream().filter(p -> !properties.contains(p)).toList();
            for (var candidate : candidates) {
                assertEquals(tier,candidate.tier()); assertTrue(candidate.fits(kind));
                assertFalse(properties.contains(candidate));
            }
        }
    }
}
