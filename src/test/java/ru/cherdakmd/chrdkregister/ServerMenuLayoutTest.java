package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Серверное меню /menu (1.4.170): авто-раскладка слотов, явные слоты, размер
 * инвентаря и кнопки «Назад»/«Закрыть» на странице раздела.
 */
class ServerMenuLayoutTest {

    private static ServerMenuSettings.Entry entry(int slot, String name) {
        return new ServerMenuSettings.Entry(slot, Material.PAPER, name, "", "pass");
    }

    @Test
    void emptyListGivesSmallEmptyChest() {
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(List.of());
        assertEquals(27, layout.size());
        assertTrue(layout.slots().isEmpty());
    }

    @Test
    void autoEntriesFillCenterRow() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(-1, "a"), entry(-1, "b"), entry(-1, "c"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(List.of(10, 11, 12), layout.slots());
    }

    @Test
    void explicitSlotsWinOverAuto() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(20, "explicit"), entry(-1, "auto"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(20, layout.slots().get(0));
        assertEquals(10, layout.slots().get(1)); // авто не наезжает на занятое явным
    }

    @Test
    void duplicateExplicitSlotSecondFallsToAuto() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(10, "first"), entry(10, "dupe"), entry(-1, "tail"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(new HashSet<>(layout.slots()).size(), layout.slots().size(),
                "слоты не должны совпадать: " + layout.slots());
        assertTrue(layout.slots().contains(10));
    }

    @Test
    void explicitHighSlotGrowsInventory() {
        List<ServerMenuSettings.Entry> entries = List.of(entry(30, "deep"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(36, layout.size());
        assertEquals(30, layout.slots().get(0));
    }

    @Test
    void manyAutoEntriesGrowUpToFullChest() {
        List<ServerMenuSettings.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(entry(-1, "auto" + i));
        }
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertTrue(layout.size() >= 45, "тоpp 30 иконок не влезает в 27: " + layout.size());
        assertFalse(layout.slots().contains(-1), "все иконки разместились: " + layout.slots());
        assertEquals(new HashSet<>(layout.slots()).size(), layout.slots().size(),
                "слоты не должны совпадать");
    }

    @Test
    void infoEntryWithoutCommandIsNotClickable() {
        var hint = new ServerMenuSettings.Entry(22, Material.KNOWLEDGE_BOOK, "hint", "text", "");
        assertTrue(hint.info());
        var action = new ServerMenuSettings.Entry(10, Material.PAPER, "a", "", "menu");
        assertFalse(action.info());
    }

    @Test
    void categoryPageFitsBackAndCloseButtons() {
        List<Integer> requested = new ArrayList<>();
        for (int i = 0; i < 9; i++) requested.add(-1);
        requested.add(40); // «Назад»
        requested.add(44); // «Закрыть»
        ServerMenuService.MenuLayout layout = ServerMenuService.layoutSlots(requested);
        assertEquals(45, layout.size(), "страница раздела — 45 слотов");
        assertTrue(layout.slots().containsAll(List.of(40, 44)), layout.slots().toString());
        assertEquals(11, new HashSet<>(layout.slots()).size(), "слоты уникальны: " + layout.slots());
        assertFalse(layout.slots().contains(-1), layout.slots().toString());
    }

    @Test
    void closeButtonCanBeDisabled() {
        var settings = new ServerMenuSettings(true, "title", "%category%", 40, Material.ARROW, "back",
                -1, Material.BARRIER, "close", List.of(), List.of());
        assertEquals(-1, settings.closeSlot(), "минус один слот = кнопки нет");
        assertEquals(Material.BARRIER, settings.closeIcon());
    }

    @Test
    void categoriesDisappearWhenAllEntriesHidden() {
        var settings = new ServerMenuSettings(true, "t", "t", 40, Material.ARROW, "back",
                List.of(new ServerMenuSettings.Category("gear", Material.ANVIL, "Снаряжение", "", 14,
                        "CHRDK_Enchants", "")),
                List.of(new ServerMenuSettings.Entry(-1, Material.PAPER, "enchant", "", "enchants", "",
                        "chrdkenchants.use", "gear")));
        assertTrue(settings.grouped());
        assertEquals(1, settings.entriesOf("gear").size());
        assertTrue(settings.looseEntries().isEmpty());
    }

    @Test
    void explicitBackSlotDoesNotCollideWithAutoLayout() {
        List<Integer> requested = new ArrayList<>();
        for (int i = 0; i < 28; i++) requested.add(-1);
        requested.add(40);
        ServerMenuService.MenuLayout layout = ServerMenuService.layoutSlots(requested);
        assertEquals(54, layout.size(), "28 пунктов + «Назад» требуют полного сундука");
        assertEquals(40, layout.slots().get(28).intValue());
        assertFalse(layout.slots().contains(-1), layout.slots().toString());
        assertEquals(layout.slots().size(), new HashSet<>(layout.slots()).size());
    }

    @Test
    void categoryPageKeepsBackButtonAndFitsTwelveItems() {
        List<Integer> requested = new ArrayList<>();
        for (int i = 0; i < 12; i++) requested.add(-1);
        requested.add(49); // кнопка «Назад» с явным слотом
        ServerMenuService.MenuLayout layout = ServerMenuService.layoutSlots(requested);
        assertEquals(54, layout.size(), "страница категории — полный сундук");
        assertEquals(49, layout.slots().get(12));
        assertEquals(13, new HashSet<>(layout.slots()).size(), "слоты уникальны: " + layout.slots());
        assertFalse(layout.slots().contains(-1));
    }

    @Test
    void groupedMenuSplitsItemsByCategory() {
        var entries = List.of(
                new ServerMenuSettings.Entry(-1, Material.PAPER, "a", "", "pass", "", "", "account"),
                new ServerMenuSettings.Entry(-1, Material.PAPER, "b", "", "shop", "", "", "economy"),
                new ServerMenuSettings.Entry(-1, Material.PAPER, "c", "", "bal", "", "", ""));
        var settings = new ServerMenuSettings(true, "t", "t", 49, Material.ARROW, "back",
                List.of(new ServerMenuSettings.Category("account", Material.PAPER, "A", "", 10, "", ""),
                        new ServerMenuSettings.Category("economy", Material.DIAMOND, "E", "", 11, "", "")),
                entries);
        assertTrue(settings.grouped());
        assertEquals(1, settings.entriesOf("account").size());
        assertEquals("b", settings.entriesOf("economy").getFirst().name());
        assertEquals(1, settings.looseEntries().size());
        assertEquals("c", settings.looseEntries().getFirst().name());
    }

    @Test
    void flatConfigStaysSinglePage() {
        var settings = new ServerMenuSettings(true, "t", "t", 49, Material.ARROW, "back", List.of(),
                List.of(new ServerMenuSettings.Entry(-1, Material.PAPER, "a", "", "pass")));
        assertFalse(settings.grouped(), "без категорий меню остаётся плоским");
    }

    @Test
    void categoryHidesWhenPluginOrPermissionMissing() {
        var category = new ServerMenuSettings.Category("gear", Material.ANVIL, "Снаряжение", "",
                14, "CHRDK_Enchants", "chrdkenchants.use");
        assertFalse(category.available(plugin -> false, permission -> true));
        assertFalse(category.available(plugin -> true, permission -> false));
        assertTrue(category.available(plugin -> true, permission -> true));
        var open = new ServerMenuSettings.Category("social", Material.BELL, "Общество", "", 15, "", "");
        assertTrue(open.available(plugin -> false, permission -> false), "без условий категория видна");
    }
}
