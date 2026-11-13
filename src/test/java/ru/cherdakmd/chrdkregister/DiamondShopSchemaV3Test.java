package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Переход config-schema diamond-shop v2 → v3: новые ключи дневных акций должны
 * аддитивно дописаться в конфиг владельца вместе с комментариями, не тронув его
 * значения; повторный старт и удаление ключа после перехода файл не меняют.
 */
final class DiamondShopSchemaV3Test {

    private static final Path RESOURCES = Path.of("diamond-shop/src/main/resources");
    private static final String CONFIG_MARKER = "config-version";
    private static final String MESSAGES_MARKER = "messages-version";

    private static String read(String name) throws IOException {
        return Files.readString(RESOURCES.resolve(name));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
        } catch (Exception error) {
            throw new AssertionError("Нечитаемый YAML: " + text, error);
        }
        return configuration;
    }

    private static ConfigSchemaEngine.Spec configSpec() {
        return new ConfigSchemaEngine.Spec("config.yml", CONFIG_MARKER,
                ConfigSchemaEngine.MarkerMode.KEY, 5, Map.of());
    }

    private static ConfigSchemaEngine.Spec messagesSpec() {
        return new ConfigSchemaEngine.Spec("messages.yml", MESSAGES_MARKER,
                ConfigSchemaEngine.MarkerMode.COMMENT, 4, Map.of());
    }

    /** Убирает только корневую секцию promotions:, сохраняя соседние разделы. */
    private static String withoutPromotions(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("promotions:".equals(lines.get(i))) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "в тексте должна быть секция promotions:");
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(" ") && !line.startsWith("#") && !line.isBlank()
                    && line.contains(":")) {
                end = i;
                break;
            }
        }
        lines.subList(start, end).clear();
        return String.join("\n", lines);
    }

    /** Владелец на v2: секции ещё нет, но есть его правка и маркер версии. */
    private static String ownerConfigV2(String reference) {
        String body = withoutPromotions(reference)
                .replace("  spread-percent: 10.0", "  spread-percent: 12.5");
        return CONFIG_MARKER + ": 2\nowner-note: \"моя правка\"\n" + body;
    }

    /** Владелец на v2: трёх новых текстов нет, один старый текст изменён вручную. */
    private static String ownerMessagesV2(String reference) {
        List<String> kept = new ArrayList<>();
        for (String line : reference.split("\n", -1)) {
            if (line.contains("\"promo-bonus-paid\":")
                    || line.contains("\"promo-line\":")
                    || line.contains("\"promo-tag\":")) {
                continue;
            }
            if (line.contains("\"market-busy\":")) {
                kept.add("    \"market-busy\": \"МОЯ ПРАВКА\"");
            } else {
                kept.add(line);
            }
        }
        return "# " + MESSAGES_MARKER + ": 2  — версия схемы текстов; не удаляйте.\n\n"
                + String.join("\n", kept);
    }

    @Test
    void schemaDeclaresV3ForBothFiles() throws IOException {
        YamlConfiguration schema = yaml(read("config-schema.yml"));
        assertEquals(5, schema.getInt(CONFIG_MARKER),
                "актуальная схема включает вывод книг из продажи (v5)");
        assertEquals(4, schema.getInt(MESSAGES_MARKER),
                "актуальная схема включает тексты акций и подарка");
        assertEquals(
                List.of("books", "pricing.purchase-multipliers.books-custom",
                        "pricing.purchase-multipliers.books-vanilla"),
                schema.getStringList("migrations.config.5.remove"),
                "v5 удаляет books: и тарифы книг из файла владельца");
    }

    @Test
    void configV2GainsPromotionsWithCommentsAndKeepsOwnerValues() throws IOException {
        String reference = read("config.yml");
        String owner = ownerConfigV2(reference);

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, owner, configSpec());

        assertTrue(result.changed(), "переход v2 → v3 обязан дописать новый раздел");
        assertTrue(result.text().contains(CONFIG_MARKER + ": 5"));
        assertTrue(result.text().contains("owner-note: \"моя правка\""));
        assertTrue(result.text().contains("Ежедневная горячая скупка"),
                "комментарии эталона переносятся вместе с блоком");

        YamlConfiguration live = yaml(result.text());
        assertEquals(12.5, live.getDouble("pricing.spread-percent"), 1e-9,
                "значение владельца не перезаписано стоком");
        assertTrue(live.isConfigurationSection("promotions"));
        assertTrue(live.getBoolean("promotions.enabled"));
        assertEquals(14, live.getStringList("promotions.pool").size());
        assertEquals(2, live.getInt("promotions.rotation-schema"));
        assertEquals(5, live.getInt("promotions.featured-per-window"));
        assertEquals(50, live.getInt("promotions.hot-buyback-percent"));
        assertEquals(5000, live.getInt("promotions.max-bonus-per-trade"));
        assertEquals(5000, live.getInt("promotions.max-bonus-per-player-per-day"));
        assertTrue(live.getBoolean("welcome-gift.enabled"));
        assertEquals(10000, live.getInt("welcome-gift.amount"));
        // Каталог магазина — СПИСОК из 248 записей, а не секция YAML.
        List<?> items = live.getList("items");
        assertNotNull(items, "каталог владельца остался на месте");
        assertEquals(248, items.size(), "каталог владельца остался на месте");
    }

    @Test
    void afterV3DeletedSectionIsNotRestoredOnNextStart() throws IOException {
        String reference = read("config.yml");
        ConfigSchemaEngine.Result first = ConfigSchemaEngine.apply(
                reference, ownerConfigV2(reference), configSpec());
        assertTrue(first.changed());

        String ownerDeletedAgain = withoutPromotions(first.text());
        ConfigSchemaEngine.Result second = ConfigSchemaEngine.apply(
                reference, ownerDeletedAgain, configSpec());

        assertFalse(second.changed(), "файл с актуальным маркером v3 не переписывается");
        assertFalse(yaml(ownerDeletedAgain).isConfigurationSection("promotions"),
                "владелец вправе удалить секцию после перехода");
    }

    /** v5: книги чар сняты с продажи — миграция remove вычищает books: с backup. */
    @Test
    void configV5RemovesBooksFromOwnerFile() throws IOException {
        YamlConfiguration schema = yaml(read("config-schema.yml"));
        List<String> removals = schema.getStringList("migrations.config.5.remove");
        ConfigSchemaEngine.Spec spec = new ConfigSchemaEngine.Spec("config.yml", CONFIG_MARKER,
                ConfigSchemaEngine.MarkerMode.KEY, 5,
                Map.of(5, new ConfigSchemaEngine.Step(Map.of(), Set.copyOf(removals))));

        String reference = read("config.yml");
        String owner = CONFIG_MARKER + ": 4\n"
                + "owner-note: \"моя правка\"\n"
                + "books:\n"
                + "  enabled: true\n"
                + "  custom:\n"
                + "    prices:\n"
                + "      common: 80\n"
                + "pricing:\n"
                + "  purchase-multipliers:\n"
                + "    books-custom: 1\n"
                + "    books-vanilla: 1\n";

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, owner, spec);

        assertTrue(result.changed(), "переход v4 → v5 обязан удалить книги");
        YamlConfiguration live = yaml(result.text());
        assertFalse(live.isConfigurationSection("books"),
                "секция books: удалена из файла владельца");
        assertFalse(live.contains("pricing.purchase-multipliers.books-custom"),
                "тарифы книг удалены");
        assertFalse(live.contains("pricing.purchase-multipliers.books-vanilla"),
                "тарифы книг удалены");
        assertTrue(result.text().contains("owner-note: \"моя правка\""),
                "правки владельца целы");
        assertTrue(result.text().contains(CONFIG_MARKER + ": 5"));

        ConfigSchemaEngine.Result again = ConfigSchemaEngine.apply(reference, result.text(), spec);
        assertFalse(again.changed(), "повторный старт файл не переписывает");
    }

    @Test
    void messagesV2GainsThreeTextsWithoutAddingRootKeys() throws IOException {
        String reference = read("messages.yml");
        String owner = ownerMessagesV2(reference);

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, owner, messagesSpec());

        assertTrue(result.changed());
        assertTrue(result.text().startsWith("# " + MESSAGES_MARKER + ": 4"));
        YamlConfiguration live = yaml(result.text());
        assertEquals(Set.of("messages"), new HashSet<>(live.getKeys(false)),
                "маркер остаётся комментарием, новый корневой ключ не появляется");
        assertEquals("МОЯ ПРАВКА", live.getString("messages.trading.market-busy"),
                "владельческий текст не перезаписан");
        assertTrue(live.getString("messages.trading.promo-bonus-paid", "").contains("%bonus%"));
        assertTrue(live.getString("messages.trading.gui.promo-line", "").contains("%items%"));
        assertEquals("★ Горячий товар · 6 ч", plainTag(live.getString("messages.trading.gui.promo-tag")));
    }

    @Test
    void messagesSecondStartIsIdempotent() throws IOException {
        String reference = read("messages.yml");
        ConfigSchemaEngine.Result first = ConfigSchemaEngine.apply(
                reference, ownerMessagesV2(reference), messagesSpec());
        assertTrue(first.changed());
        assertFalse(ConfigSchemaEngine.apply(reference, first.text(), messagesSpec()).changed());
    }

    private static String plainTag(String text) {
        // MiniMessage-тег в эталоне: <gold>★ Горячий товар · 6 ч</gold>.
        return text == null ? "" : text.replace("<gold>", "").replace("</gold>", "");
    }
}
