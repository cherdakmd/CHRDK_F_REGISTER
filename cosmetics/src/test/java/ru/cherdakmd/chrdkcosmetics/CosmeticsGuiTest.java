package ru.cherdakmd.chrdkcosmetics;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticsGuiTest {
    @TempDir Path dir;
    @Test void actualTrailNamesRenderAsColorsAndGradientNotLiteralTags() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            var messages = Messages.from(stream, dir.resolve("messages.yml"), Logger.getAnonymousLogger());
            for (String markup : List.of("<red>Огненный шлейф</red>", "<gradient:#b388ff:#88ffff>Сияние</gradient>")) {
                var rendered = messages.get("line", "state", messages.mini("state-on"), "name", Messages.trusted(markup), "id", "flame");
                String text = PlainTextComponentSerializer.plainText().serialize(rendered);
                assertFalse(text.contains("<red>")); assertFalse(text.contains("<gradient")); assertTrue(text.contains(Messages.stripFormatting(markup)));
                assertNotEquals(rendered, messages.get("line", "state", "", "name", markup, "id", "flame"));
            }
            String escaped = PlainTextComponentSerializer.plainText().serialize(messages.get("unknown", "id", "<red>fake</red>"));
            assertTrue(escaped.contains("<red>fake</red>"), "не делаем любой ввод игрока доверенным");
        }
    }
    @Test void everyConfiguredTrailCanBeReachedWithRealPagination() {
        for (int size : new int[]{0,1,7,45,46,90,91}) {
            Set<Integer> seen = new HashSet<>();
            for (int p = 0; p < CosmeticsGui.pages(size); p++) for (int i = p * 45; i < Math.min(size, (p + 1) * 45); i++) assertTrue(seen.add(i));
            assertEquals(size, seen.size()); assertEquals(0, CosmeticsGui.page(-1, size));
            assertEquals(CosmeticsGui.pages(size)-1, CosmeticsGui.page(Integer.MAX_VALUE, size));
        }
    }
    @Test void legacySelectionsMigrateAndJoinTogglePersistsWithoutLosingTrail() throws Exception {
        UUID id = UUID.randomUUID(); Files.writeString(dir.resolve("cosmetics-data.json"), "{\"" + id + "\":\"aurora\"}");
        var store = new SelectionStore(dir, null); store.load(); assertEquals("aurora", store.selectionOf(id)); assertTrue(store.joinEnabled(id));
        store.joinEnabled(id, false); store.save();
        var restored = new SelectionStore(dir, null); restored.load(); assertEquals("aurora", restored.selectionOf(id)); assertFalse(restored.joinEnabled(id));
        restored.select(id, null); restored.save();
        var cleared = new SelectionStore(dir, null); cleared.load(); assertNull(cleared.selectionOf(id)); assertFalse(cleared.joinEnabled(id));
    }
    @Test void corruptReloadDoesNotClearLoadedSelectionsAndNoOpDoesNotDirty() throws Exception {
        UUID id = UUID.randomUUID(); var store = new SelectionStore(dir, null); store.select(id, "flame"); store.save();
        store.select(id, "flame"); store.joinEnabled(id, true); assertFalse(store.isDirty());
        Files.writeString(dir.resolve("cosmetics-data.json"), "broken"); store.load(); assertEquals("flame", store.selectionOf(id));
    }
    @Test void rankOnlyExplicitEmptyProductIsNotOpenedByPackAndNonFiniteEffectsAreBounded() {
        var defs = CosmeticsDefs.parseTrails(List.of(Map.of("id", "gold", "particle", "electric_spark", "product", "", "rank", 4,
                "spread", Double.NaN, "speed", Double.POSITIVE_INFINITY)), true);
        assertEquals("", defs.getFirst().product());
        assertFalse(CosmeticsDefs.unlocked(defs.getFirst(), true, false, 0, id -> true, List.of("trails-pack")));
        assertTrue(CosmeticsDefs.unlocked(defs.getFirst(), true, true, 4, id -> false, List.of()));
        assertTrue(Double.isFinite(defs.getFirst().spread())); assertTrue(Double.isFinite(defs.getFirst().speed()));
    }
    @Test void guiUsesOwnerSnapshotAuthViewIdentityAndDoesNotSellOrGiveItems() throws Exception {
        String gui = source("CosmeticsGui");
        for (String guard : List.of("boolean blocked = event.isCancelled()", "event.setCancelled(true)", "holder.owner.equals(player.getUniqueId())",
                "holder.snapshot != plugin.config()", "getTopInventory().getHolder() != holder", "!plugin.eligible(player)", "LINKS.contains(action.value)", "void stop()")) assertTrue(gui.contains(guard), guard);
        assertFalse(gui.contains("addItem(")); assertFalse(gui.contains("dispatchCommand(")); assertFalse(gui.contains("shopbuy"));
        String command = source("CosmeticsCommandHandler"); assertTrue(command.contains("args.length == 0 || args[0].equalsIgnoreCase(\"list\")"));
        assertTrue(command.contains("Messages.trusted(def.displayName())"));
    }
    @Test void effectsRecheckAccessAndAuthenticationAndAutosaveIsAsync() throws Exception {
        String plugin = source("CosmeticsPlugin");
        for (String guard : List.of("!eligible(player)", "unlocked(player.getName(), def)", "store.joinEnabled", "PlayerAuthCompletedEvent",
                "runTaskTimerAsynchronously(this, store::save", "saveTask.cancel()", "gui.stop()", "snapshot != config")) assertTrue(plugin.contains(guard), guard);
    }
    @Test void everyHubSectionHasRealDefaultsAndFixedCommandTargets() throws Exception {
        assertTrue(CosmeticsGui.LINKS.containsAll(List.of("menu", "labels", "labels color", "labels order", "labels preset", "hat")));
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            var messages = Messages.from(stream, dir.resolve("messages.yml"), Logger.getAnonymousLogger());
            for (String key : List.of("gui-trails", "gui-join", "gui-colors", "gui-labels", "gui-slots", "gui-presets", "gui-hat", "gui-trail-lore", "gui-summary")) assertTrue(messages.has(key), key);
        }
    }
    private String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkcosmetics/"+name+".java")); }
}
