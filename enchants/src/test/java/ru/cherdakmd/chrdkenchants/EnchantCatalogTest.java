package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class EnchantCatalogTest {
    @TempDir Path dir;
    @Test void everyDefinitionIsReachableExactlyOnceWithEverySort() {
        for (var sort : EnchantCatalog.Sort.values()) {
            var selected = EnchantCatalog.select(EnchantCatalog.Query.all().sort(sort), Map.of(), Set.of());
            var reached = new ArrayList<EnchantDef>();
            for (int page = 0; page < EnchantCatalog.pages(selected.size()); page++) {
                var slice = EnchantCatalog.slice(selected, page);
                assertTrue(slice.size() <= 45); reached.addAll(slice);
            }
            assertEquals(EnchantRegistry.size(), reached.size());
            assertEquals(reached.size(), reached.stream().map(EnchantDef::id).distinct().count());
            assertEquals(Set.copyOf(EnchantRegistry.all()), Set.copyOf(reached));
        }
    }
    @Test void allFiftyFiveLightDefinitionsAreReachableNotJustTheFirstFortyFive() {
        var selected = EnchantCatalog.select(EnchantCatalog.Query.all().polarity(EnchantDef.Polarity.LIGHT), Map.of(), Set.of());
        assertEquals(55, selected.size()); assertEquals(2, EnchantCatalog.pages(selected.size()));
        assertEquals(45, EnchantCatalog.slice(selected, 0).size()); assertEquals(10, EnchantCatalog.slice(selected, 1).size());
    }
    @Test void pagesAndEmptyResultsAreClampedIncludingExtremeRequests() {
        assertEquals(1, EnchantCatalog.pages(0)); assertEquals(1, EnchantCatalog.pages(-1));
        assertEquals(1, EnchantCatalog.pages(45)); assertEquals(2, EnchantCatalog.pages(46));
        assertTrue(EnchantCatalog.pages(Integer.MAX_VALUE) > 1);
        assertEquals(0, EnchantCatalog.page(Integer.MIN_VALUE, 70));
        assertEquals(1, EnchantCatalog.page(Integer.MAX_VALUE, 70));
        assertTrue(EnchantCatalog.slice(List.of(), Integer.MAX_VALUE).isEmpty());
    }
    @Test void searchFindsIdsAndLocalizedNamesAndNormalizesYoAndWhitespace() {
        var names = new HashMap<String, String>(); names.put("seer", "Провидец"); names.put("featherstep", "Лёгкий шаг");
        var search = new EnchantCatalog.Query("  ПРОВИД  ", null, null, null, false, null);
        assertEquals(List.of("seer"), ids(EnchantCatalog.select(search, names, Set.of())));
        assertEquals(List.of("featherstep"), ids(EnchantCatalog.select(new EnchantCatalog.Query("легкий", null, null, null, false, null), names, Set.of())));
        assertEquals(List.of("air_reserve"), ids(EnchantCatalog.select(new EnchantCatalog.Query("AIR_RESERVE", null, null, null, false, null), names, Set.of())));
        assertThrows(IllegalArgumentException.class, () -> new EnchantCatalog.Query("x".repeat(65), null, null, null, false, null));
    }
    @Test void groupRarityPolarityAndHandFiltersComposeWithoutLeaks() {
        var query = EnchantCatalog.Query.all().group(ItemGroup.SWORD).rarity(EnchantDef.Rarity.RARE)
                .polarity(EnchantDef.Polarity.LIGHT).hand(true);
        assertEquals(List.of("crippling_edge"), ids(EnchantCatalog.select(query, Map.of(), Set.of("crippling_edge", "sapping_edge", "dull_blade"))));
        assertTrue(EnchantCatalog.select(query.group(ItemGroup.BOW), Map.of(), Set.of("crippling_edge")).isEmpty());
        assertTrue(EnchantCatalog.select(query, Map.of(), Set.of()).isEmpty());
    }
    @Test void handModeUsesActualRollerLimitsAndDoesNotAdvertiseDuplicateOrConflictingAdditions() {
        var existing = Map.of("crippling_edge", 1, "scholar", 1, "plunder", 1);
        Set<String> applicable = EnchantRoller.applicable(EnchantRegistry.all(), "DIAMOND_SWORD", existing, 3, 1)
                .stream().map(EnchantDef::id).collect(Collectors.toSet());
        assertTrue(EnchantCatalog.select(EnchantCatalog.Query.all().hand(true), Map.of(), applicable).isEmpty());
        applicable = EnchantRoller.applicable(EnchantRegistry.all(), "DIAMOND_SWORD", Map.of("crippling_edge", 1), 3, 1)
                .stream().map(EnchantDef::id).collect(Collectors.toSet());
        var selected = ids(EnchantCatalog.select(EnchantCatalog.Query.all().hand(true), Map.of(), applicable));
        assertFalse(selected.contains("crippling_edge")); assertFalse(selected.contains("sapping_edge"));
        assertFalse(selected.contains("quiet_step")); assertTrue(selected.contains("lifesteal"));
    }
    @Test void raritySortIsDescendingAndStableAndFilterCyclesReachAll() {
        var selected = EnchantCatalog.select(EnchantCatalog.Query.all().sort(EnchantCatalog.Sort.RARITY), Map.of(), Set.of());
        assertEquals(EnchantDef.Rarity.LEGENDARY, selected.getFirst().rarity());
        for (int i = 1; i < selected.size(); i++) assertTrue(selected.get(i - 1).rarity().ordinal() >= selected.get(i).rarity().ordinal());
        assertEquals(EnchantDef.Polarity.LIGHT, EnchantCatalog.next(null, EnchantDef.Polarity.values()));
        assertEquals(EnchantDef.Polarity.CURSE, EnchantCatalog.next(EnchantDef.Polarity.LIGHT, EnchantDef.Polarity.values()));
        assertNull(EnchantCatalog.next(EnchantDef.Polarity.CURSE, EnchantDef.Polarity.values()));
    }
    @Test void conflictsContainActualOtherIdsAndNeverTheDefinitionItself() {
        assertEquals(List.of("sapping_edge"), EnchantCatalog.conflicts(EnchantRegistry.byId("crippling_edge")));
        assertEquals(List.of("second_skin", "thin_padding"), EnchantCatalog.conflicts(EnchantRegistry.byId("bulwark")));
        assertTrue(EnchantCatalog.conflicts(EnchantRegistry.byId("scholar")).isEmpty());
    }
    @Test void timerDisplayRoundsUpAndNeverOverflowsOrChangesTheDeadline() {
        assertEquals(0, EnchantTimers.seconds(1000, 1000)); assertEquals(0, EnchantTimers.seconds(999, 1000));
        assertEquals(1, EnchantTimers.seconds(1001, 1000)); assertEquals(2, EnchantTimers.seconds(2001, 1000));
        assertTrue(EnchantTimers.seconds(Long.MAX_VALUE, Long.MIN_VALUE) > 0);
        assertEquals(0, EnchantTimers.seconds(0, 1000));
    }
    @Test void guiHasOwnerGenerationViewIdentityAndPriorCancellationChecks() throws Exception {
        String source = source("EnchantGui");
        for (String guard : List.of("boolean blocked = event.isCancelled()", "event.setCancelled(true)", "event.getRawSlot() >= 54",
                "holder.owner.equals(player.getUniqueId())", "holder.generation == plugin.sourceGeneration()",
                "getTopInventory().getHolder() == holder", "eligible(player)", "void closeAll()", "return !stopped && player.isOnline()")) assertTrue(source.contains(guard), guard);
        assertFalse(source.contains("addItem(")); assertFalse(source.contains("dropItem"));
        assertFalse(source.contains("Math.min(items.size(), PAGE_SIZE)"));
        assertTrue(source("EnchantsPlugin").contains("if (gui != null) gui.closeAll()"));
    }
    @Test void commandAndAdminBoundariesAreExplicitAndInvalidLevelsDoNotFallBackToOne() throws Exception {
        String source = source("EnchantCommandHandler");
        for (String guard : List.of("!player.hasPermission(\"chrdkenchants.use\")", "!plugin.coreApi().isAuthenticated(actor)",
                "level < 1 || level > def.maxLevel()", "!plugin.config().isEnabled(def.id())", "catalog-admin-level",
                "Messages.trusted(plugin.gui().conflictsLabel(def))", "args.length == 0 || plugin.coreApi() == null")) assertTrue(source.contains(guard), guard);
        assertFalse(source.contains("level = Math.max(1, Math.min(def.maxLevel(), level))"));
    }
    @Test void inspectionAndCooldownCommandsOnlyReadPdcAndActualTimerKeysMatchHandlers() throws Exception {
        String source = source("EnchantCommandHandler");
        assertTrue(source.contains("case \"inspect\"")); assertTrue(source.contains("case \"cooldowns\""));
        assertTrue(source.contains("getOrDefault(new NamespacedKey(plugin, key), PersistentDataType.LONG, 0L)"));
        assertFalse(source.contains("PersistentDataContainer().set(")); assertFalse(source.contains("PersistentDataContainer().remove("));
        assertTrue(source("PveEffectsListener").contains("\"" + EnchantTimers.PVE + "\""));
        assertTrue(source("UtilityEffectsListener").contains("\"" + EnchantTimers.WORK + "\""));
        assertTrue(source("UtilityEffectsListener").contains("\"" + EnchantTimers.AIR + "\""));
    }
    @Test void allControlAndExactGroupLabelsExistInActualMessageDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            var messages = Messages.from(stream, dir.resolve("messages.yml"), Logger.getLogger("CatalogTest"));
            for (ItemGroup group : ItemGroup.values()) assertTrue(messages.has("catalog-group-" + group.name().toLowerCase(java.util.Locale.ROOT)));
            for (String key : List.of("catalog-group", "catalog-rarity", "catalog-polarity", "catalog-hand", "catalog-sort", "catalog-result", "catalog-clear", "catalog-inspect-line", "catalog-cooldowns")) assertTrue(messages.has(key), key);
        }
    }
    private List<String> ids(List<EnchantDef> values) { return values.stream().map(EnchantDef::id).toList(); }
    private String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java")); }
}
