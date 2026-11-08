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
                ConfigSchemaEngine.MarkerMode.KEY, 3, Map.of());
    }

    private static ConfigSchemaEngine.Spec messagesSpec() {
        return new ConfigSchemaEngine.Spec("messages.yml", MESSAGES_MARKER,
                ConfigSchemaEngine.MarkerMode.COMMENT, 3, Map.of());
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
        assertEquals(3, schema.getInt(CONFIG_MARKER),
                "config.yml владельца v2 должен получить новый блок promotions:");
        assertEquals(3, schema.getInt(MESSAGES_MARKER),
                "messages.yml владельца v2 должен получить три новых текста");
    }

    @Test
    void configV2GainsPromotionsWithCommentsAndKeepsOwnerValues() throws IOException {
        String reference = read("config.yml");
        String owner = ownerConfigV2(reference);

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, owner, configSpec());

        assertTrue(result.changed(), "переход v2 → v3 обязан дописать новый раздел");
        assertTrue(result.text().contains(CONFIG_MARKER + ": 3"));
        assertTrue(result.text().contains("owner-note: \"моя правка\""));
        assertTrue(result.text().contains("Дневные акции"),
                "комментарии эталона переносятся вместе с блоком");

        YamlConfiguration live = yaml(result.text());
        assertEquals(12.5, live.getDouble("pricing.spread-percent"), 1e-9,
                "значение владельца не перезаписано стоком");
        assertTrue(live.isConfigurationSection("promotions"));
        assertTrue(live.getBoolean("promotions.enabled"));
        assertEquals(14, live.getStringList("promotions.pool").size());
        assertEquals(3, live.getInt("promotions.featured-per-day"));
        assertEquals(25, live.getInt("promotions.hot-buyback-percent"));
        assertEquals(64, live.getInt("promotions.contract-units"));
        assertEquals(12, live.getInt("promotions.contract-reward"));
        assertEquals(3, live.getInt("promotions.series-kinds"));
        assertEquals(16, live.getInt("promotions.series-reward"));
        assertEquals(32, live.getInt("promotions.max-bonus-per-trade"));
        assertEquals(128, live.getInt("promotions.max-bonus-per-player-per-day"));
        assertEquals(248, live.getConfigurationSection("items").getKeys(false).size(),
                "каталог владельца остался на месте");
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

    @Test
    void messagesV2GainsThreeTextsWithoutAddingRootKeys() throws IOException {
        String reference = read("messages.yml");
        String owner = ownerMessagesV2(reference);

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, owner, messagesSpec());

        assertTrue(result.changed());
        assertTrue(result.text().startsWith("# " + MESSAGES_MARKER + ": 3"));
        YamlConfiguration live = yaml(result.text());
        assertEquals(Set.of("messages"), new HashSet<>(live.getKeys(false)),
                "маркер остаётся комментарием, новый корневой ключ не появляется");
        assertEquals("МОЯ ПРАВКА", live.getString("messages.trading.market-busy"),
                "владельческий текст не перезаписан");
        assertTrue(live.getString("messages.trading.promo-bonus-paid", "").contains("%bonus%"));
        assertTrue(live.getString("messages.trading.gui.promo-line", "").contains("%items%"));
        assertEquals("★ Товар дня", plainTag(live.getString("messages.trading.gui.promo-tag")));
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
        // MiniMessage-тег в эталоне: <gold>★ Товар дня</gold>.
        return text == null ? "" : text.replace("<gold>", "").replace("</gold>", "");
    }
}
