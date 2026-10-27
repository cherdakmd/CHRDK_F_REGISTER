package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnvilQuotesTest {
    private final Object view = new Object();
    private final UUID player = UUID.randomUUID();
    private AnvilQuotes.Quote<Object, String> quote(String result) {
        return new AnvilQuotes.Quote<>(view, "left", "book", "name", result, 19, 40, 5, true, false, 7);
    }
    @Test void repeatedOwnedPreviewIsStableIncludingBlockedOutput() {
        var store = new AnvilQuotes<Object, String>();
        assertTrue(store.put(player, quote("result")));
        for (int i = 0; i < 100; i++) {
            var q = store.get(player);
            assertTrue(q.source(view, "left", "book", "name"));
            assertTrue(q.output("result", 19, 1, 40, 5, true, false));
            assertEquals(19, q.cost());
        }
        assertTrue(quote(null).output(null, 19, 1, 40, 5, true, false));
        assertEquals(1, store.size());
        assertTrue(quote("result").couldBeRepeat(19, 0));
        var zero = new AnvilQuotes.Quote<>(view, "left", "book", "name", "result", 19, 40, 5, true, false, 0);
        assertFalse(zero.couldBeRepeat(19, 0)); // Fresh native, не double preview.
        assertTrue(zero.couldBeRepeat(19, 1));
        assertFalse(zero.couldBeRepeat(20, 1));
    }
    @Test void sourceBindsWindowIdentityAndBothCompleteInputsAndRename() {
        var q = quote("result");
        assertFalse(q.source(new Object(), "left", "book", "name"));
        assertFalse(q.source(view, "other-left", "book", "name"));
        assertFalse(q.source(view, "left", "other-book", "name"));
        assertFalse(q.source(view, "left", "book", "other-name"));
    }
    @Test void alteredResultPriceMaterialCountLimitOrGenerationInvalidatesTake() {
        var q = quote("result");
        assertFalse(q.output("modified", 19, 1, 40, 5, true, false));
        assertFalse(q.output("result", 0, 1, 40, 5, true, false));
        assertFalse(q.output("result", 19, 0, 40, 5, true, false));
        assertFalse(q.output("result", 19, 1, 100, 5, true, false));
        assertFalse(q.output("result", 19, 1, 40, 6, true, false));
        assertFalse(q.output("result", 19, 1, 40, 5, false, false));
        assertFalse(q.output("result", 19, 1, 40, 5, true, true));
    }
    @Test void boundedStoreAllowsReplacementButRejectsOverflowWithoutEviction() {
        var store = new AnvilQuotes<Object, String>();
        for (int i = 0; i < AnvilQuotes.LIMIT; i++) assertTrue(store.put(new UUID(0, i), quote("result")));
        assertFalse(store.put(player, quote("result")));
        assertTrue(store.put(new UUID(0, 0), quote(null)));
        assertEquals(AnvilQuotes.LIMIT, store.size());
        store.remove(new UUID(0, 0));
        assertTrue(store.put(player, quote("result")));
        store.clear();
        assertEquals(0, store.size());
        assertNull(store.get(player));
    }
    @Test void listenerUsesViewApiNativeConsumptionAndCleanupContracts() throws Exception {
        String source = source("AnvilApplyListener");
        assertTrue(source.contains("view.setRepairItemCountCost(1)"));
        assertTrue(source.contains("view.setRepairCost(cost)"));
        assertFalse(source.contains("setMaximumRepairCost("));
        assertFalse(source.contains("setBypassCost("));
        assertFalse(source.contains("giveExp"));
        assertFalse(source.contains("setLevel("));
        assertFalse(source.contains("addItem("));
        assertTrue(source.contains("view.canBypassCost()"));
        assertTrue(source.contains("left.getAmount() != 1 || right.getAmount() != 1"));
        assertTrue(source.contains("quote.output(result"));
        assertTrue(source.contains("event.setCancelled(true)"));
        assertTrue(source.contains("!event.isCancelled()"));
        assertTrue(source.contains("notices.size() >= AnvilQuotes.LIMIT"));
        assertTrue(source.contains("notices.remove(player.getUniqueId(), ticket)"));
        assertTrue(source.contains("InventoryCloseEvent event"));
        assertTrue(source.contains("PlayerQuitEvent event"));
        String plugin = source("EnchantsPlugin");
        assertTrue(plugin.contains("if (anvil != null) anvil.clear()"));
        assertTrue(plugin.contains("() -> { if (anvil != null) anvil.clear(); }"));
    }
    @Test void priceAndApplyShareActualMergeRuleWithoutColourOrPreviewGiveaway() throws Exception {
        assertTrue(source("EnchantService").contains("AnvilPrice.merge(existing, level, def.maxLevel())"));
        String listener = source("AnvilApplyListener");
        assertTrue(listener.contains("meta.displayName(Component.text(rename))"));
        assertTrue(listener.contains("vanilla == null ? left.clone() : vanilla.clone()"));
        assertTrue(listener.contains("AnvilPrice.nativeCompatible(pureBook"));
        assertTrue(listener.contains("AnvilPrice.nextWork(leftWork, bookWork, cumulative)"));
        assertFalse(listener.contains("MiniMessage.miniMessage()"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
    @Test void playerReportReadsProductionQuoteWithoutInventoryOrExperienceMutation() throws Exception {
        var source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/AnvilApplyListener.java"));
        var start = source.indexOf("void preview(Player player)");
        var report = source.substring(start, source.indexOf("private void reject", start));
        for (String guard : java.util.List.of("instanceof AnvilView", "quotes.get", "quote.source", "quote.output", "view.canBypassCost()", "isAuthenticated", "canApplyFromBook", "quote.surcharge()", "Integer.MAX_VALUE", "AnvilPrice.merge", "player.getLevel() >= quote.cost()")) assertTrue(report.contains(guard), guard);
        for (String forbidden : java.util.List.of("setItem", "setResult", "setLevel", "giveExp", "dispatchCommand", "quotes.put", "items().write")) assertFalse(report.contains(forbidden), forbidden);
    }
}
