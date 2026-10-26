package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Меню «Оформление ника» (1.4.186): раскладка плиток и то, что каждая надпись
 * действительно переключается — в меню, командой и по русскому псевдониму.
 */
class LabelsMenuServiceTest {

    private static final Logger LOGGER = Logger.getLogger("LabelsMenuServiceTest");

    @Test
    void everyLabelHasExactlyOneTile() {
        List<LabelPreferences.Label> tiles = new ArrayList<>();
        Set<Integer> slots = new HashSet<>();
        for (int slot : LabelsMenuService.ENTRY_SLOTS) {
            assertTrue(slot >= 0 && slot < LabelsMenuService.SIZE, "слот вне меню: " + slot);
            assertTrue(slots.add(slot), "слот повторяется: " + slot);
            LabelPreferences.Label label = LabelsMenuService.labelAt(slot);
            assertNotNull(label, "пустая плитка в слоте " + slot);
            tiles.add(label);
        }
        assertEquals(LabelPreferences.Label.values().length, tiles.size(),
                "плиток ровно столько, сколько надписей");
        assertEquals(EnumSet.allOf(LabelPreferences.Label.class), EnumSet.copyOf(tiles),
                "каждая надпись — ровно один раз");
    }

    @Test
    void tilesDoNotCoverButtons() {
        int[] reserved = {LabelsMenuService.HEAD_SLOT, LabelsMenuService.PREVIEW_SLOT,
                LabelsMenuService.ENABLE_ALL_SLOT,
                LabelsMenuService.DISABLE_ALL_SLOT, LabelsMenuService.PROFILE_SLOT,
                LabelsMenuService.RESET_SLOT, LabelsMenuService.CLOSE_SLOT};
        for (int slot : reserved) {
            assertNull(LabelsMenuService.labelAt(slot), "кнопка перекрыта плиткой: " + slot);
        }
        for (int slot : LabelsMenuService.ENTRY_SLOTS) {
            assertNotNull(LabelsMenuService.labelAt(slot), "плитка потерялась: " + slot);
        }
    }

    @Test
    void slotLookupRoundTrips() {
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            int slot = LabelsMenuService.slotOf(label);
            assertTrue(slot >= 0, "у надписи нет плитки: " + label.id());
            assertEquals(label, LabelsMenuService.labelAt(slot), "слот не совпал: " + label.id());
        }
        assertNull(LabelsMenuService.labelAt(-1));
        assertNull(LabelsMenuService.labelAt(LabelsMenuService.SIZE + 5));
    }

    @Test
    void everyTileHasTextsAndIcon() throws Exception {
        try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/messages.yml"))) {
            Messages messages = Messages.from(stream, null, LOGGER);
            for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
                assertFalse(LabelsMenuService.nameOf(messages, label).text().isBlank(),
                        "нет названия: " + label.id());
                assertFalse(label.id().isBlank(), "нет ида: " + label);
            }
            for (String key : List.of("labels.menu-title", "labels.menu-head", "labels.menu-head-lore",
                    "labels.menu-preview", "labels.menu-preview-lore", "labels.menu-entry-on",
                    "labels.menu-entry-off", "labels.menu-enable-all", "labels.menu-disable-all",
                    "labels.menu-reset", "labels.menu-profile", "labels.menu-close",
                    "labels.profile-section", "labels.profile-button", "labels.profile-button-lore",
                    "labels.profile-all-visible", "labels.profile-line", "labels.profile-hidden",
                    "labels.profile-hint")) {
                assertFalse(messages.raw(key, "player", "x", "visible", "1", "total", "9",
                        "name", "y", "nick", "<gold>Ник</gold>").isBlank(),
                        "пустой текст: " + key);
            }
        }
    }

    @Test
    void commandAcceptsEveryIdAndRussianAlias() {
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            assertEquals(label, LabelsCommandHandler.singleOf(label.id()),
                    "ид не разобран: " + label.id());
            assertEquals(label, LabelsCommandHandler.singleOf(label.id().toUpperCase()),
                    "регистр не важен: " + label.id());
        }
        assertEquals(LabelPreferences.Label.RANK, LabelsCommandHandler.singleOf("ранг"));
        assertEquals(LabelPreferences.Label.HEADER, LabelsCommandHandler.singleOf("шапка"));
        assertEquals(LabelPreferences.Label.STREAMS, LabelsCommandHandler.singleOf("стример"));
        // «badges» и «all» — не одиночные надписи, у них свои ветки команды.
        assertNull(LabelsCommandHandler.singleOf("badges"));
        assertNull(LabelsCommandHandler.singleOf("all"));
        assertNull(LabelsCommandHandler.singleOf("меню"));
        assertNull(LabelsCommandHandler.singleOf(null));
    }
}
