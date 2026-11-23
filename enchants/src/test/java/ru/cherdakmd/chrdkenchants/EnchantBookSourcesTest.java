package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.EnchantBookPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EnchantBookSourcesTest {
    @Test void sourceTiersGateRarityWithoutRemovingTheCatalog() {
        assertEquals(75, EnchantRegistry.all().size());
        for (EnchantDef def : EnchantRegistry.all()) {
            assertEquals(def.rarity().ordinal() <= 1, EnchantBookPolicy.allows(def.rarity().name(), 1));
            assertEquals(def.rarity().ordinal() <= 2, EnchantBookPolicy.allows(def.rarity().name(), 2));
            assertTrue(EnchantBookPolicy.allows(def.rarity().name(), 3));
        }
        assertFalse(EnchantBookPolicy.allows(null, 3));
        assertFalse(EnchantBookPolicy.allows("MYTHIC", 3));
        assertFalse(EnchantBookPolicy.allows("", 3));
    }
    @Test void newDefaultsAreBalancedAndExistingSettingsArePreserved() throws Exception {
        var defaults = EnchantsConfig.parse(new YamlConfiguration(), java.util.logging.Logger.getAnonymousLogger());
        assertEquals(30, defaults.tableChancePercent());
        assertEquals(15, defaults.tableCurseChancePercent());
        assertEquals(35, defaults.villagerConvertChancePercent());
        var saved = new YamlConfiguration();
        saved.set("sources.table.chance-percent", 35);
        saved.set("sources.table.curse-chance-percent", 25);
        saved.set("sources.villager.convert-chance-percent", 50);
        var prior = EnchantsConfig.parse(saved, java.util.logging.Logger.getAnonymousLogger());
        assertEquals(35, prior.tableChancePercent());
        assertEquals(25, prior.tableCurseChancePercent());
        assertEquals(50, prior.villagerConvertChancePercent());
        var bundled = yaml("config.yml");
        assertEquals(30, bundled.getInt("sources.table.chance-percent"));
        assertEquals(15, bundled.getInt("sources.table.curse-chance-percent"));
        assertEquals(35, bundled.getInt("sources.villager.convert-chance-percent"));
    }
    @Test void allTiersHaveLightBooksAndHigherTiersActuallyExpandThePool() {
        int previous = 0;
        for (int tier = 1; tier <= 3; tier++) {
            int current = tier;
            long size = EnchantRegistry.light().stream().filter(def -> EnchantBookPolicy.allows(def.rarity().name(), current)).count();
            assertTrue(size > previous); previous = (int) size;
        }
        assertEquals(60, previous);
    }
    @Test void deferredTableCommitHasCancellationReloadIdentityAndAuthenticationGuards() throws Exception {
        String source = source("TableListener");
        assertTrue(source.contains("event.isCancelled() || generation != plugin.sourceGeneration()"));
        assertTrue(source.contains("!plugin.coreApi().isAuthenticated(player)"));
        assertTrue(source.contains("!current.equals(expected)"));
        assertTrue(source.contains("getTopInventory().equals(inventory)"));
        assertTrue(source.contains("before.getType() == Material.BOOK"));
        assertTrue(source.contains("storage.addStoredEnchant"));
        assertTrue(source.contains("inventory.setItem(0, result)"));
        assertFalse(source.contains("addItem(")); assertFalse(source.contains("dropItem("));
    }
    @Test void anvilRetainsVanillaResultAndBlocksInvalidCustomTransfer() throws Exception {
        String source = source("AnvilApplyListener");
        assertTrue(source.contains("vanilla == null ? left.clone() : vanilla.clone()"));
        assertTrue(source.contains("event.setResult(null)"));
        assertTrue(source.contains("!plugin.coreApi().isAuthenticated(viewer)"));
        assertTrue(source.contains("vanillaCost"));
    }
    @Test void newBookDetailsAndSourceDefaultsAreShipped() throws Exception {
        var messages = yaml("messages.yml");
        for (String key : new String[]{"book-rarity", "book-items", "book-conflicts", "book-apply"}) {
            String text = messages.getString("enchants." + key);
            assertNotNull(text); assertTrue(text.contains("✶"));
            assertDoesNotThrow(() -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text));
        }
        var config = yaml("config.yml");
        assertFalse(config.contains("sources.mobs"));
    }
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
    private YamlConfiguration yaml(String name) throws Exception {
        var result = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(stream); result.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }
}
