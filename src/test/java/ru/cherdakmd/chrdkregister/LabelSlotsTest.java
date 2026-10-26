package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Конкретные места: пропажа источника и понижение ранга никогда не запускают подмену. */
class LabelSlotsTest {
    private static final String PLAYER = "Steve";
    private static final LabelPreferences.Label R = LabelPreferences.Label.RANK;
    private static final LabelPreferences.Label C = LabelPreferences.Label.CLASSES;
    private static final LabelPreferences.Label G = LabelPreferences.Label.GUILDS;
    private static final LabelPreferences.Label T = LabelPreferences.Label.TREASURES;

    private static LabelPreferences prefs(Path dir) {
        var prefs = new LabelPreferences(dir, Logger.getAnonymousLogger());
        prefs.load();
        return prefs;
    }

    private static LabelStyleService styles(LabelPreferences prefs, int[] level, Set<String> missing) {
        return new LabelStyleService((id, player, name) ->
                !missing.contains(id) && Set.of("classes", "guilds", "treasures").contains(id)
                        ? "<aqua>[" + id + "]</aqua> " + name : name,
                prefs, new LabelStyleService.RankLookup() {
                    public LabelStyleService.Rank view(String player) {
                        return level[0] == 0 ? null
                                : new LabelStyleService.Rank(level[0], "✦", "<gold>%name%</gold>", "Ранг", true);
                    }
                    public List<String> registrationOrder() { return List.of(); }
                });
    }

    private static List<LabelPreferences.Label> shown(LabelStyleService styles) {
        return styles.selection(PLAYER).shown().stream().map(LabelStyleService.Decoration::source).toList();
    }

    @Test
    void unavailableHiddenAndExpiredSourcesLeaveTheirPlacesUnfilled(@TempDir Path dir) {
        var prefs = prefs(dir);
        int[] level = {1};
        Set<String> missing = new HashSet<>();
        var styles = styles(prefs, level, missing);
        prefs.setSlots(PLAYER, List.of("rank", "classes", "guilds", "treasures"));
        assertEquals(List.of(R, C), shown(styles));
        missing.add("classes");
        assertEquals(List.of(R), shown(styles));
        assertTrue(styles.selection(PLAYER).obtained(G), "гильдия получена, но не подменяет класс");
        assertEquals(List.of("rank", "classes"), styles.assignments(PLAYER).subList(0, 2));
        level[0] = 0;
        assertTrue(shown(styles).isEmpty(), "после истечения ранга не подставляется третья метка");
        level[0] = 1;
        missing.clear();
        assertEquals(List.of(R, C), shown(styles));
        prefs.set(PLAYER, R, false);
        assertEquals(List.of(C), shown(styles));
        assertEquals("Steve · [classes]", Messages.stripFormatting(styles.decorate(PLAYER, PLAYER, "")));
    }

    @Test
    void rankDowngradePreservesLockedPlacesAndStaleClicksCannotEditThem(@TempDir Path dir) {
        var prefs = prefs(dir);
        int[] level = {5};
        var styles = styles(prefs, level, Set.of());
        prefs.setSlots(PLAYER, List.of("rank", "classes", "guilds", "treasures"));
        var saved = styles.assignments(PLAYER);
        assertEquals(List.of(R, C, G, T), shown(styles));
        level[0] = 1;
        assertEquals(List.of(R, C), shown(styles));
        assertTrue(styles.selection(PLAYER).deferred(G));
        assertEquals(LabelStyleService.AssignmentResult.LOCKED, styles.assign(PLAYER, 2, null));
        assertEquals(LabelStyleService.AssignmentResult.LOCKED, styles.assign(PLAYER, 2, C));
        assertEquals(saved, styles.assignments(PLAYER));
        level[0] = 5;
        assertEquals(List.of(R, C, G, T), shown(styles));
    }

    @Test
    void assigningMovesSourceAndEnablesItWhileClearingNeverPullsAnother(@TempDir Path dir) {
        var prefs = prefs(dir);
        var styles = styles(prefs, new int[]{1}, Set.of());
        prefs.setSlots(PLAYER, List.of("rank", "classes", "guilds"));
        prefs.set(PLAYER, G, false);
        assertEquals(LabelStyleService.AssignmentResult.APPLIED, styles.assign(PLAYER, 0, G));
        assertEquals(List.of("guilds", "classes", ""), styles.assignments(PLAYER).subList(0, 3));
        assertTrue(prefs.visible(PLAYER, G));
        assertEquals(List.of(G, C), shown(styles));
        styles.assign(PLAYER, 1, null);
        assertEquals(List.of(G), shown(styles));
        prefs.setAll(PLAYER, false);
        prefs.setAll(PLAYER, true);
        assertEquals(List.of(G), shown(styles), "включение источников не заполняет очищенное место");
        assertEquals(LabelStyleService.AssignmentResult.UNAVAILABLE,
                styles.assign(PLAYER, 1, LabelPreferences.Label.HEADER));
        assertEquals(LabelStyleService.AssignmentResult.UNAVAILABLE,
                styles.assign(PLAYER, 1, LabelPreferences.Label.QUESTS));
        assertEquals(LabelStyleService.AssignmentResult.LOCKED, styles.assign(PLAYER, -1, G));
    }

    @Test
    void explicitEmptySlotsPersistAndPresetsIncludeThem(@TempDir Path dir) throws Exception {
        var prefs = prefs(dir);
        var styles = styles(prefs, new int[]{5}, Set.of());
        prefs.setSlots(PLAYER, List.of());
        assertTrue(shown(styles).isEmpty());
        prefs.setColor(PLAYER, "aqua");
        prefs.savePreset(PLAYER, "p1");
        prefs.reset(PLAYER);
        assertNull(prefs.slots(PLAYER));
        prefs.apply(PLAYER, prefs.presets(PLAYER).get("p1"));
        assertNotNull(prefs.slots(PLAYER));
        assertTrue(shown(styles).isEmpty());
        assertEquals("aqua", prefs.color(PLAYER));
        var restored = prefs(dir);
        assertNotNull(restored.slots(PLAYER));
        assertEquals(8, restored.slots(PLAYER).size());
        assertTrue(restored.slots(PLAYER).stream().allMatch(String::isEmpty));
        assertEquals(restored.slots(PLAYER), restored.presets(PLAYER).get("p1").slots());
        assertTrue(Files.readString(restored.file()).contains("\"version\":4"));
    }

    @Test
    void legacyFileAndPresetKeepPositionsWithoutAvailabilityFiltering(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("labels.json"), """
                {"version":3,"players":{"steve":{"order":["guilds","classes","rank"],
                "color":"aqua","hidden":["streams"],"presets":{"p1":{"order":["guilds","classes","rank"]}}}}}
                """);
        var prefs = prefs(dir);
        var styles = styles(prefs, new int[]{1}, Set.of("guilds"));
        assertEquals(List.of(C), shown(styles));
        assertEquals("aqua", prefs.color(PLAYER));
        assertFalse(prefs.visible(PLAYER, LabelPreferences.Label.STREAMS));
        styles.assign(PLAYER, 0, R);
        prefs.apply(PLAYER, prefs.presets(PLAYER).get("p1"));
        assertEquals(List.of(C), shown(styles));
        assertEquals(List.of("guilds", "classes", "rank"), styles.assignments(PLAYER).subList(0, 3));
    }

    @Test
    void junkAndDuplicatesBecomeHolesRatherThanShiftingPositions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("labels.json"), """
                {"version":4,"players":{"steve":{"slots":["classes",null,"classes","header","guilds",17,"unknown","treasures","rank"]}}}
                """);
        var prefs = prefs(dir);
        assertEquals(List.of("classes", "", "", "", "guilds", "", "", "treasures"), prefs.slots(PLAYER));
        var styles = styles(prefs, new int[]{1}, Set.of());
        assertEquals(List.of(C), shown(styles));
        assertTrue(styles.selection(PLAYER).deferred(G));
        styles.applyLimits(new LabelLimits(List.of(2), List.of(8)));
        assertEquals(List.of(C, G, T), shown(styles));
    }

    @Test
    void rankRequirementsComeFromConfiguredCounts() {
        var defaults = LabelLimits.DEFAULT;
        assertEquals(0, defaults.levelForLabelSlot(0));
        assertEquals(3, defaults.levelForLabelSlot(2));
        assertEquals(4, defaults.levelForLabelSlot(3));
        assertEquals(5, defaults.levelForLabelSlot(4));
        assertEquals(-1, defaults.levelForLabelSlot(5));
        assertEquals(-1, defaults.levelForLabelSlot(-1));
        assertEquals(0, new LabelLimits(List.of(2), List.of(8)).levelForLabelSlot(7));
    }
}
