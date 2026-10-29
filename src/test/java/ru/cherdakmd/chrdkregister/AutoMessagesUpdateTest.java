package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Автосообщения: действующие команды, корректные обещания и бережная миграция. */
class AutoMessagesUpdateTest {
    private YamlConfiguration yaml(String resource) {
        var input = getClass().getResourceAsStream("/" + resource);
        assertNotNull(input, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private List<AutoMessage> stock() {
        return ChatConfig.fromBukkitConfig(yaml("config.yml")).autoMessageEntries();
    }

    private String plain(AutoMessage message) {
        return PlainTextComponentSerializer.plainText().serialize(
                MiniMessage.builder().strict(true).build().deserialize(message.text()));
    }

    private Set<String> commands(YamlConfiguration descriptor) {
        var result = new HashSet<String>();
        var section = descriptor.getConfigurationSection("commands");
        assertNotNull(section);
        for (var command : section.getKeys(false)) {
            result.add(command);
            result.addAll(descriptor.getStringList("commands." + command + ".aliases"));
        }
        return result;
    }

    @Test void everyEntryParsesAndTargetsWorkingPlayerCommands() throws Exception {
        var config = yaml("config.yml");
        var entries = stock();
        assertEquals(44, entries.size());
        assertEquals(config.getList("auto-messages.messages").size(), entries.size(), "ни один текст не отброшен парсером");
        var core = yaml("plugin.yml");
        var coreCommands = commands(core);
        var addons = new HashMap<String, YamlConfiguration>();
        try (var paths = Files.list(Path.of("."))) {
            for (var folder : paths.toList()) {
                var path = folder.resolve("src/main/resources/plugin.yml");
                if (Files.exists(path)) {
                    var descriptor = YamlConfiguration.loadConfiguration(path.toFile());
                    addons.put(descriptor.getString("name"), descriptor);
                }
            }
        }
        var advertised = new HashSet<String>();
        var texts = new HashSet<String>();
        var commandPattern = Pattern.compile("/([a-z0-9_-]+)");
        var placeholders = Set.of("%balance%", "%pass_left%");
        for (var entry : entries) {
            assertTrue(texts.add(entry.text()), "повтор текста: " + entry.text());
            String text = plain(entry);
            assertFalse(text.isBlank());
            assertFalse(text.contains("<gray>"), "разметка не попала в текст");
            assertFalse(RetiredAddons.announcement(entry.text()));
            assertFalse(RetiredAddons.plugin(entry.plugin()));
            assertTrue(Set.of("always", "paid", "unsecured").contains(entry.condition()));
            var descriptor = entry.plugin().isBlank() ? core : addons.get(entry.plugin());
            assertNotNull(descriptor, entry.plugin());
            if (!entry.plugin().isBlank()) advertised.add(entry.plugin());
            if (!entry.permission().isBlank()) {
                assertTrue(descriptor.contains("permissions." + entry.permission()), entry.permission());
                assertTrue(descriptor.getBoolean("permissions." + entry.permission() + ".default"), entry.permission());
                assertFalse(entry.eligible(name -> true, permission -> false, false, true, true, true, 5));
            }
            if (!entry.plugin().isBlank()) {
                assertFalse(entry.permission().isBlank());
                assertFalse(entry.eligible(name -> false, permission -> true, false, true, true, true, 5));
            }
            var allowed = new HashSet<>(coreCommands);
            allowed.addAll(commands(descriptor));
            var matcher = commandPattern.matcher(text);
            while (matcher.find()) {
                var command = matcher.group(1);
                assertTrue(allowed.contains(command), entry.plugin() + ": " + command);
                assertFalse(command.equals("pass") || command.endsWith("admin") || command.equals("prof"), command);
            }
            matcher = Pattern.compile("%[a-z_]+%").matcher(entry.text());
            while (matcher.find()) assertTrue(placeholders.contains(matcher.group()), matcher.group());
        }
        assertEquals(addons.keySet(), advertised, "в рассылке представлены все действующие аддоны");
        MiniMessage.builder().strict(true).build().deserialize(config.getString("auto-messages.prefix"));
    }

    @Test void claimsAboutRewardsMoneyAndAccessMatchCurrentRules() {
        var entries = stock();
        String text = String.join("\n", entries.stream().map(this::plain).toList());
        assertFalse(text.contains("не сбрасывается"));
        assertTrue(text.contains("пропуск дня сбрасывает серию"));
        assertFalse(text.contains("Ранг закончился"));
        assertFalse(text.contains("Пробные дни"));
        assertFalse(text.contains("Выручку забирайте"));
        assertFalse(text.contains("72 достижения"));
        assertFalse(text.contains("60 перков"));
        assertFalse(text.contains("не сгорает при перезапуске"));
        assertFalse(text.contains("https://"), "официальные адреса выдают публичные команды, ссылки не дублируются");
        assertTrue(text.contains("/auction money показывает банк"));
        assertTrue(text.contains("Платный ранг не продлевается"));
        assertTrue(text.contains("2FA подключается по желанию"));
        assertTrue(text.contains("/wiki"));
        assertEquals(1, entries.stream().filter(e -> e.condition().equals("paid")).count());
        assertEquals(1, entries.stream().filter(e -> e.condition().equals("unsecured")).count());
        var paid = entries.stream().filter(e -> e.condition().equals("paid")).findFirst().orElseThrow();
        assertFalse(paid.eligible(n -> true, n -> true, false, false, false, false, 5));
        assertTrue(paid.eligible(n -> true, n -> true, false, false, true, true, 5));
        var unsecured = entries.stream().filter(e -> e.condition().equals("unsecured")).findFirst().orElseThrow();
        assertFalse(unsecured.eligible(n -> true, n -> true, false, false, true, true, 5));
    }

    @Test void previousStockUpgradesOnlyMessagesAndOnlyOnce() {
        var legacy = yaml("presentation-legacy-1.4.246.yml");
        var live = yaml("config.yml");
        live.set("auto-messages.messages", legacy.get("auto-messages.messages"));
        live.set("auto-messages.enabled", false);
        live.set("auto-messages.interval-seconds", 777);
        live.set("auto-messages.random-order", false);
        live.set("auto-messages.minimum-players", 7);
        live.set("auto-messages.prefix", "Свой префикс: ");
        live.set("tablist.pinned-names", List.of("Owner"));
        var fresh = yaml("config.yml");
        String before = live.saveToString();
        assertEquals(28, legacy.getList("auto-messages.messages").size());
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.246.yml"));
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertEquals(List.of("auto-messages.messages"), changes);
        assertEquals(before, live.saveToString(), "выбор изменений ничего не пишет");
        for (String key : changes) live.set(key, fresh.get(key));
        assertFalse(live.getBoolean("auto-messages.enabled"));
        assertEquals(777, live.getInt("auto-messages.interval-seconds"));
        assertFalse(live.getBoolean("auto-messages.random-order"));
        assertEquals(7, live.getInt("auto-messages.minimum-players"));
        assertEquals("Свой префикс: ", live.getString("auto-messages.prefix"));
        assertEquals(List.of("Owner"), live.getStringList("tablist.pinned-names"));
        assertEquals(44, ChatConfig.fromBukkitConfig(live).autoMessageEntries().size());
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), fresh).isEmpty());
    }

    @Test void editedPartialEmptyAndMissingListsAreNotOverwritten() {
        var legacy = yaml("presentation-legacy-1.4.246.yml");
        var fresh = yaml("config.yml");
        var edited = new ArrayList<Object>(legacy.getList("auto-messages.messages"));
        edited.set(0, Map.of("text", "Личное сообщение владельца"));
        for (List<?> custom : List.<List<?>>of(edited, List.of("Свой текст"), List.of())) {
            var live = yaml("config.yml");
            live.set("auto-messages.messages", custom);
            assertFalse(PresentationMigration.replacements(live, List.of(legacy), fresh).contains("auto-messages.messages"));
            assertEquals(custom, live.getList("auto-messages.messages"));
        }
        var missing = new YamlConfiguration();
        missing.setDefaults(fresh);
        assertFalse(PresentationMigration.replacements(missing, List.of(legacy), fresh).contains("auto-messages.messages"));
    }

    @Test void fallbackDoesNotAdvertiseAnExpiredTrialOrRequirePayment() {
        var fallback = ChatConfig.defaultAutoMessages();
        assertEquals(4, fallback.size());
        String text = String.join("\n", fallback);
        assertTrue(text.contains("/wiki"));
        assertFalse(text.contains("Пробные дни"));
        assertFalse(text.contains("/pass"));
        assertFalse(text.contains("%pass_left%"));
    }
}
