package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EnchantDescriptionsTest {
    @TempDir Path folder;
    static final Logger LOG = Logger.getAnonymousLogger();
    Messages messages() throws Exception { return Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, LOG); }
    String plain(Messages messages, String key, Object... args) {
        return PlainTextComponentSerializer.plainText().serialize(messages.get(key, args));
    }
    @Test void everyDefinitionHasDetailedRenderableTextSharedByBookAndCatalog() throws Exception {
        var messages = messages();
        assertEquals(76, EnchantRegistry.size());
        for (var def : EnchantRegistry.all()) {
            String key = "en." + def.id() + ".lore";
            assertTrue(messages.has(key), key);
            var lines = messages.getList(key);
            assertTrue(lines.size() >= 2, key);
            for (var line : lines) assertFalse(PlainTextComponentSerializer.plainText().serialize(line).isBlank(), key);
            assertFalse(plain(messages, key).matches("(?s).*%[a-z_]+%.*"), key);
        }
    }
    @Test void helpTopicsAreFiniteCaseInsensitiveAndDoNotAcceptArbitraryMessagePaths() throws Exception {
        var messages = messages();
        for (String topic : EnchantHelp.TOPICS) {
            String key = EnchantHelp.topic(topic.toUpperCase(Locale.ROOT));
            assertEquals("help-" + topic, key);
            assertTrue(messages.has(key));
            assertTrue(messages.getList(key).size() >= 7, key);
        }
        for (String invalid : List.of("", "../messages", "en.seer.lore", "books extra", "help-books")) assertNull(EnchantHelp.topic(invalid));
        assertNull(EnchantHelp.topic(null));
        assertTrue(messages.getList("help-overview").size() >= 5);
    }
    @Test void explanationsUseActualHandlerUnitsNotMisleadingUnusedConstants() throws Exception {
        var m = messages();
        String quick = plain(m, "en.quickdraw.lore");
        assertTrue(quick.contains("+4/6/8%")); assertTrue(quick.contains("Не сокращает"));
        for (int level = 1; level <= 3; level++) assertEquals(2 * new int[]{4,6,8}[level - 1], EnchantParams.percent("quickdraw", level));
        assertTrue(plain(m, "en.vitality.lore").contains("0,5/0,75/1 ед. HP"));
        assertTrue(plain(m, "en.prospector.lore").contains("Спешку I/II/II"));
        assertTrue(plain(m, "en.dull_pick.lore").contains("Усталость I/II/II"));
        assertTrue(plain(m, "en.gluttony.lore").contains("0,05/0,0875/0,125"));
        assertTrue(plain(m, "en.thunderstrike.lore").contains("не гарантированный поджог"));
        assertTrue(plain(m, "sources-table").contains("не ограничивает его I/II/III"));
        assertTrue(plain(m, "sources-mobs").contains("5/20/40% на шаг"));
    }
    @Test void bookAndInfoMetadataHaveNoUnfilledPlaceholders() throws Exception {
        var m = messages();
        for (String key : List.of("book-level", "book-rarity", "book-items", "book-conflicts", "book-preview", "book-apply", "book-curse-warning", "info-id", "info-rules", "info-curse-warning")) {
            assertTrue(m.has(key), key);
            String rendered = plain(m, key, "id", "dull_blade", "level", 2, "maxlevel", 3,
                    "rarity", "Обычная", "items", "Мечи", "conflicts", "Громобой", "state", "Да", "max", 3, "legendary", 1);
            assertFalse(rendered.matches("(?s).*%[a-z_]+%.*"), key + ": " + rendered);
        }
        assertTrue(plain(m, "help-anvil").contains("Общие 3 добавляются один раз"));
        assertTrue(plain(m, "help-workshop").contains("8 лазурита + 1 физический алмаз"));
    }
    @Test void exactOldStockIsMigratedWithoutWritingOwnerFileAndCustomTextsRemain() throws Exception {
        Path file = folder.resolve("messages.yml");
        try (var input = getClass().getResourceAsStream("/enchants-messages-legacy-1.4.282-rc6.yml")) {
            assertNotNull(input); Files.copy(input, file);
        }
        byte[] original = Files.readAllBytes(file);
        var fresh = messages();
        var migrated = migrated(file);
        for (var def : EnchantRegistry.all()) assertEquals(fresh.template("en." + def.id() + ".lore"), migrated.template("en." + def.id() + ".lore"));
        assertEquals(fresh.template("sources-table"), migrated.template("sources-table"));
        assertEquals(fresh.template("gui-prev"), migrated.template("gui-prev"));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(migrated.template("en.quickdraw.lore"), migrated(file).template("en.quickdraw.lore"));
        Files.writeString(file, "enchants:\n  en.quickdraw.lore:\n    - '<green>Текст владельца</green>'\n  gui-prev: ''\n");
        var custom = migrated(file);
        assertEquals("Текст владельца", plain(custom, "en.quickdraw.lore"));
        assertEquals("", custom.template("gui-prev"));
        assertEquals(fresh.template("help-books"), custom.template("help-books"));
    }
    Messages migrated(Path file) throws Exception {
        return Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), file,
                getClass().getResourceAsStream("/enchants-messages-legacy-1.4.282-rc6.yml"), LOG);
    }
    @Test void malformedOwnerYamlIsNotSubstitutedDuringMigration() throws Exception {
        Path file = folder.resolve("messages.yml"); String broken = "enchants: [broken"; Files.writeString(file, broken);
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () -> migrated(file));
        assertEquals(broken, Files.readString(file));
    }
    @Test void suppliedLegacyStreamIsClosedEvenWhenOwnerYamlFailsBeforeMigration() throws Exception {
        Path file = folder.resolve("messages.yml"); Files.writeString(file, "enchants: [broken");
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var legacy = new java.io.ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public void close() throws java.io.IOException { closed.set(true); super.close(); }
        };
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () ->
                Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), file, legacy, LOG));
        assertTrue(closed.get());
    }
    @Test void descriptionRefreshIsExplicitAndOnlyChangesLoreOnAValidatedBook() throws Exception {
        String items = source("EnchantItems"), commands = source("EnchantCommandHandler");
        String refresh = items.substring(items.indexOf("public boolean refreshBookDescription"), items.indexOf("Перерисовывает секцию лора"));
        assertTrue(refresh.contains("validated(book) == null")); assertTrue(refresh.contains("refreshLore(meta, read(book))"));
        assertFalse(refresh.contains("applyAttributes(")); assertFalse(refresh.contains("stampBook(")); assertFalse(refresh.contains("write("));
        assertTrue(commands.contains("refreshBookDescription(described)"));
        assertTrue(items.contains("get(\"book-level\"")); assertTrue(items.contains("get(\"book-preview\""));
    }
    @Test void catalogDetailsUseServerSnapshotAndRetainDeferredAuthGenerationAndViewChecks() throws Exception {
        String gui = source("EnchantGui");
        for (String guard : List.of("event.setCancelled(true)", "holder.entries.get(event.getRawSlot())", "holder.generation != plugin.sourceGeneration()",
                "getTopInventory().getHolder() != holder", "EnchantCommandHandler.showInfo(plugin, player, id)", "this.entries = List.copyOf(entries)")) assertTrue(gui.contains(guard), guard);
        assertFalse(gui.contains("addItem(")); assertFalse(gui.contains("dropItem"));
        String checkedLoader = Files.readString(Path.of("../src/main/java/ru/cherdakmd/chrdkregister/Messages.java"));
        assertTrue(checkedLoader.contains("migrateCheckedStock(defaults, overrides, plugin.getResource(\"enchants-messages-legacy-1.4.282-rc6.yml\"))"));
    }
    String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java")); }
}
