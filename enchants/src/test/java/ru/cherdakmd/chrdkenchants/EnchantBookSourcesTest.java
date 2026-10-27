package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.EnchantBookPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EnchantBookSourcesTest {
    @Test void sourceTiersGateRarityWithoutRemovingTheCatalog() {
        assertEquals(60, EnchantRegistry.all().size());
        for (EnchantDef def : EnchantRegistry.all()) {
            assertEquals(def.rarity().ordinal() <= 1, EnchantBookPolicy.allows(def.rarity().name(), 1));
            assertEquals(def.rarity().ordinal() <= 2, EnchantBookPolicy.allows(def.rarity().name(), 2));
            assertTrue(EnchantBookPolicy.allows(def.rarity().name(), 3));
        }
        assertFalse(EnchantBookPolicy.allows(null, 3));
        assertFalse(EnchantBookPolicy.allows("MYTHIC", 3));
        assertFalse(EnchantBookPolicy.allows("", 3));
    }
    @Test void allTiersHaveLightBooksAndHigherTiersActuallyExpandThePool() {
        int previous = 0;
        for (int tier = 1; tier <= 3; tier++) {
            int current = tier;
            long size = EnchantRegistry.light().stream().filter(def -> EnchantBookPolicy.allows(def.rarity().name(), current)).count();
            assertTrue(size > previous); previous = (int) size;
        }
        assertEquals(45, previous);
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
    @Test void booksFromMobsUseNaturalProvenanceOneClaimAndAnActualPlayerFinisher() throws Exception {
        String source = source("MobBookListener");
        assertTrue(source.contains("data.has(claimed)"));
        assertTrue(source.indexOf("data.set(claimed") < source.indexOf("nextDouble()"));
        assertTrue(source.contains("!killer.equals(attacker)"));
        assertTrue(source.contains("getEntitySpawnReason()"));
        assertTrue(source.contains("MobProgressionKeys.NATURAL.equals"));
        assertTrue(source.contains("getOrDefault(active"));
        assertTrue(source.contains("EnchantDef.Polarity.LIGHT"));
        assertTrue(source.contains("event.getDrops().add(book)"));
        assertFalse(source.contains("addItem(")); assertFalse(source.contains("setDroppedExp("));
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
        assertTrue(config.getBoolean("sources.mobs.enabled"));
        assertEquals(.35, config.getDouble("sources.mobs.regular-chance-percent"));
        assertEquals(8, config.getDouble("sources.mobs.elite-chance-percent"));
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
