package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ExpeditionLabelsTest {
    @Test void earnedTitleUsesAnExistingSlotAndMissingProviderDoesNotReplaceIt(@TempDir Path dir) {
        var prefs = new LabelPreferences(dir, Logger.getAnonymousLogger()); prefs.load();
        var online = new AtomicBoolean(true);
        var styles = new LabelStyleService((id, player, anchor) -> id.equals("bp-expeditions") && online.get()
                ? anchor + " <gold>Покоритель миров</gold>" : anchor,
                prefs, new LabelStyleService.RankLookup() {
                    public LabelStyleService.Rank view(String name) { return null; }
                    public List<String> registrationOrder() { return List.of(); }
                });
        var label = LabelPreferences.Label.EXPEDITIONS;
        assertTrue(styles.selection("Steve").obtained(label));
        int oldLimit = styles.selection("Steve").limit();
        assertEquals(LabelStyleService.AssignmentResult.APPLIED, styles.assign("Steve", 0, label));
        assertEquals("Steve · Покоритель миров", Messages.stripFormatting(styles.decorate("Steve", "Steve", "")));
        assertEquals(oldLimit, styles.selection("Steve").limit());
        online.set(false); assertTrue(styles.selection("Steve").shown().isEmpty());
        assertEquals("bp-expeditions", styles.assignments("Steve").get(0));
        online.set(true); assertTrue(styles.selection("Steve").displayed(label));
        assertEquals(LabelStyleService.AssignmentResult.LOCKED, styles.assign("Steve", oldLimit, label));
    }
    @Test void legacyOrdinalBitsAndSlotsArePreserved(@TempDir Path dir) {
        assertEquals(7, LabelPreferences.Label.HEADER.ordinal());
        assertEquals(8, LabelPreferences.Label.EXPEDITIONS.ordinal());
        var prefs = new LabelPreferences(dir, Logger.getAnonymousLogger()); prefs.load();
        prefs.setSlots("Steve", List.of("classes", "achievements"));
        var before = prefs.slots("Steve");
        assertEquals("classes", before.get(0)); assertEquals("achievements", before.get(1));
        assertEquals(8, before.size()); assertTrue(before.subList(2, 8).stream().allMatch(String::isEmpty));
    }
}
