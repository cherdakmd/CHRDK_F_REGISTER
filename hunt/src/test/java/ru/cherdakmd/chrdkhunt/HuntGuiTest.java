package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Чистая маршрутизация и контракты безопасности GUI, без запуска сервера. */
class HuntGuiTest {
    @Test void mainButtonsDoNotOverlapAndRetainOldActions() {
        var newSlots = Set.of(HuntGui.WILD_SLOT, HuntGui.MOON_SLOT, HuntGui.GUIDE_SLOT, HuntGui.HUD_SLOT);
        assertEquals(4, newSlots.size());
        for (int slot : newSlots) { assertTrue(slot >= 0 && slot < 54); assertTrue(HuntGui.mainAction(slot)); }
        for (int old : new int[]{19, 20, 21, 23, 25, 45, 49, 53}) {
            assertFalse(newSlots.contains(old)); assertTrue(HuntGui.mainAction(old));
        }
        int count = 0;
        for (int slot = -999; slot < 999; slot++) if (HuntGui.mainAction(slot)) count++;
        assertEquals(12, count);
        assertFalse(HuntGui.mainAction(Integer.MIN_VALUE)); assertFalse(HuntGui.mainAction(Integer.MAX_VALUE));
    }
    @Test void aliasesAndMoonCommandOpenInventoriesWithoutDispatchingPlayerCommands() throws Exception {
        String commands = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntCommandHandler.java"));
        assertTrue(commands.contains("case \"gui\", \"menu\""));
        assertTrue(commands.contains("plugin.gui().open(player)"));
        assertTrue(commands.contains("plugin.wildGui().openMoon(p)"));
        String gui = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntGui.java"));
        assertFalse(gui.contains("performCommand("));
        assertTrue(gui.contains("slot < 0 || slot >= event.getView().getTopInventory().getSize()"));
        assertTrue(gui.contains("getHolder() != top"));
        assertTrue(gui.contains("Bukkit.getScheduler().runTask(plugin"));
        assertTrue(gui.contains("plugin.finance().eligible(player)"));
        assertTrue(gui.contains("!owner.equals(player.getUniqueId())"));
    }
    @Test void newWindowsAreProtectedAndCloseWithExistingLifecycle() throws Exception {
        String gui = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntGui.java"));
        String drag = gui.substring(gui.indexOf("public void onDrag"));
        assertTrue(drag.contains("top instanceof GuideHolder"));
        assertTrue(drag.contains("holder instanceof GuideHolder"));
        String moon = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/WildBestiaryGui.java"));
        assertTrue(moon.contains("boolean openMoon(Player p)"));
        assertTrue(moon.contains("getHolder() != h"));
        assertTrue(moon.contains("if (slot == 18) plugin.gui().open(p)"));
        assertTrue(moon.contains("else if (slot == 22) openMoon(p)"));
        assertFalse(moon.contains("runTaskTimer"));
        assertFalse(gui.contains("runTaskTimer"));
    }
    @Test void everyNewGuiTextExistsIncludingMultilineLore() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            var texts = ru.cherdakmd.chrdkregister.Messages.from(stream, null, java.util.logging.Logger.getAnonymousLogger());
            for (String key : new String[]{"menu-main-back", "menu-wild-lore", "menu-moon-open", "menu-guide",
                    "menu-guide-open", "menu-guide-lore", "menu-guide-combat", "menu-guide-combat-lore",
                    "menu-hud", "menu-hud-state", "menu-hud-toggle", "menu-moon-safety", "menu-moon-safety-lore",
                    "menu-moon-sleep", "menu-moon-sleep-lore", "menu-moon-waves", "menu-moon-waves-lore",
                    "menu-moon-dawn", "menu-refresh", "menu-refresh-lore"}) assertTrue(texts.has(key), key);
            assertTrue(texts.getList("menu-guide-combat-lore").size() > 1);
            assertFalse(texts.get("menu-hud-state", "state", texts.mini("mob-on")).toString().contains("%state%"));
        }
    }
}
