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
    @Test void anvilQuoteBindsFullInputsWindowSettingsAndRename() {
        Object settings=new Object(),view=new Object();
        var quote=new ForgeAnvilInputs<>(view,"sword:pdc:durability","crystals:16",settings,"name");
        assertTrue(quote.matches(view,"sword:pdc:durability","crystals:16",settings,"name"));
        assertFalse(quote.matches(new Object(),"sword:pdc:durability","crystals:16",settings,"name"));
        assertFalse(quote.matches(view,"sword:pdc:changed","crystals:16",settings,"name"));
        assertFalse(quote.matches(view,"sword:pdc:durability","crystals:15",settings,"name"));
        assertFalse(quote.matches(view,"sword:pdc:durability","crystals:16",new Object(),"name"));
        assertFalse(quote.matches(view,"sword:pdc:durability","crystals:16",settings,"changed"));
    }
    @Test void selectionWrapsWithoutAcceptingForgedOrEmptyIndexes() {
        assertEquals(0,ForgeAnvilInputs.next(0,1)); assertEquals(1,ForgeAnvilInputs.next(0,3));
        assertEquals(0,ForgeAnvilInputs.next(2,3));
        assertThrows(IllegalArgumentException.class,()->ForgeAnvilInputs.next(0,0));
        assertThrows(IllegalArgumentException.class,()->ForgeAnvilInputs.next(3,3));
        assertThrows(IllegalArgumentException.class,()->ForgeAnvilInputs.next(-1,3));
    }
    @Test void sequenceCannotWrapOrGoBackwards() {
        assertEquals(1, ForgeMaterial.nextSequence(0));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.nextSequence(-1));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.nextSequence(Long.MAX_VALUE));
    }
    @Test void allEightMaterialsHaveSharedInstructionsAndOnlyFiniteIds() throws Exception {
        var messages = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());
        assertEquals(8, ForgeMaterial.values().length);
        for (var material : ForgeMaterial.values()) {
            assertSame(material, ForgeMaterial.byId(material.id.toUpperCase(Locale.ROOT)));
            String lines = messages.getList("forge." + material.id + ".details").toString();
            assertTrue(lines.contains("слева"));
            assertTrue(lines.contains("справа"));
            assertTrue(lines.contains("ЛКМ"));
            assertFalse(lines.contains("/tiers forge"));
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
    @Test void rc9AnvilInstructionsMigrationPreservesCustomAndEmptyWithoutRewritingFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var path = directory.resolve("messages.yml");
        byte[] legacy;
        try (var input = getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc9.yml")) {
            assertNotNull(input); legacy = input.readAllBytes();
        }
        java.nio.file.Files.write(path,legacy);
        var logger = Logger.getAnonymousLogger();
        var fresh = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),null,logger);
        var migrated = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc9.yml"),logger);
        assertEquals(fresh.raw("forge-help"),migrated.raw("forge-help"));
        assertArrayEquals(legacy,java.nio.file.Files.readAllBytes(path));
        for (String custom : List.of("Свой текст", "")) {
            String yaml = "tiers:\n  forge-help: '" + custom + "'\n";
            java.nio.file.Files.writeString(path,yaml);
            var retained = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                    getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc9.yml"),logger);
            assertEquals(custom,retained.raw("forge-help"));
            assertEquals(yaml,java.nio.file.Files.readString(path));
        }
    }
    @Test void rc16FullXpPricesAreLevelsAndSevenTiersStayIntact() {
        assertEquals(8, ForgeMaterial.SEED_XP);
        assertEquals(6, ForgeMaterial.REPAIR_XP);
        assertEquals(10, ForgeMaterial.INSCRIBE_XP);
        assertEquals(4, ForgeMaterial.REMOVE_XP);
        assertEquals(2, ForgeMaterial.NAME_XP);
        assertEquals(7, TierDef.LADDER.size());
        assertEquals(4, ForgeMaterial.HUNT_DROPS.size());
        assertEquals(4, ForgeMaterial.SHOP_ONLY.size());
        assertTrue(ForgeMaterial.SEED.seed());
        assertTrue(ForgeMaterial.REPAIR.shopOnly());
        assertFalse(ForgeMaterial.SEED.shopOnly());
    }
    @Test void seedDropChanceIsHalfOneAndThreePercent() {
        assertEquals(0.5, ForgeMaterial.chance(ForgeMaterial.SEED_BASE_DROP_PERCENT,1,0), 0.0);
        assertEquals(1.0, ForgeMaterial.chance(ForgeMaterial.SEED_BASE_DROP_PERCENT,30,0), 0.0);
        assertEquals(3.0, ForgeMaterial.chance(ForgeMaterial.SEED_BASE_DROP_PERCENT,30,1), 0.0);
    }
    @Test void seedUidIsValid12HexAndNeverAcceptsRawUuid() {
        String canonical = "6f1c2a94-6e5b-4d3a-9c7e-1b0d5f8a2c33";
        String uid = ForgeMaterial.seedUid(canonical);
        assertEquals(12, uid.length());
        assertTrue(TierModifierIds.validUid(uid));
        assertFalse(TierModifierIds.validUid(canonical));
        assertEquals(uid, ForgeMaterial.seedUid(canonical));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.seedUid("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> ForgeMaterial.seedUid(canonical.toUpperCase(Locale.ROOT).replace("-", "")));
    }
    @Test void rc15AnvilInstructionsMigrationPreservesCustomAndEmptyWithoutRewritingFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var path = directory.resolve("messages.yml");
        byte[] legacy;
        try (var input = getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc15.yml")) {
            assertNotNull(input); legacy = input.readAllBytes();
        }
        java.nio.file.Files.write(path,legacy);
        var logger = Logger.getAnonymousLogger();
        var fresh = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),null,logger);
        var migrated = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc15.yml"),logger);
        assertEquals(fresh.raw("forge-help"),migrated.raw("forge-help"));
        assertArrayEquals(legacy,java.nio.file.Files.readAllBytes(path));
        for (String custom : List.of("Свой текст", "")) {
            String yaml = "tiers:\n  forge-help: '" + custom + "'\n";
            java.nio.file.Files.writeString(path,yaml);
            var retained = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"),path,
                    getClass().getResourceAsStream("/tierforge-messages-legacy-1.4.282-rc15.yml"),logger);
            assertEquals(custom,retained.raw("forge-help"));
            assertEquals(yaml,java.nio.file.Files.readString(path));
        }
    }
    @Test void rc16LoreAppendIsSingleAndRefreshOwnedLoreStaysMigrationOnly() throws Exception {
        String items = java.nio.file.Files.readString(java.nio.file.Path.of(
                "tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/TierItems.java"));
        assertTrue(items.contains("ItemStack forgeMaterialCopy("));
        assertTrue(items.contains("refreshTierModifiers(copy); // migration-only"));
        int start = items.indexOf("private ItemStack assemble");
        int end = items.indexOf("public void bumpUpgrade(", start);
        assertTrue(start >= 0 && end > start);
        assertEquals(1, items.substring(start, end).split("lore.add\\(tierLine\\(tier, kind\\)\\);", -1).length - 1);
        String lore = java.nio.file.Files.readString(java.nio.file.Path.of(
                "tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/TierLore.java"));
        assertTrue(lore.contains("if (!recognized) return before;"));
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
