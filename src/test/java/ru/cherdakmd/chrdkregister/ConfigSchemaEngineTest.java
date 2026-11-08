package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Схема версий конфигов: новые ключи дописываются, владельческие правки и
 * комментарии сохраняются, повторный старт ничего не меняет.
 */
final class ConfigSchemaEngineTest {

    private static final String CONFIG_MARKER = "config-version";
    private static final String MESSAGES_MARKER = "messages-version";

    private static ConfigSchemaEngine.Spec config(int target,
                                                  Map<Integer, ConfigSchemaEngine.Step> steps) {
        return new ConfigSchemaEngine.Spec("config.yml", CONFIG_MARKER,
                ConfigSchemaEngine.MarkerMode.KEY, target, steps);
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

    @Test
    void addsMissingSectionWithCommentsAndKeepsOwnerValues() {
        String reference = """
                # Шапка эталона
                general:
                  locale: "ru"
                  enabled: true

                # Раздел, добавленный в новом релизе
                prestige:
                  enabled: true
                  xp-per-step: 0.03
                """;
        String user = """
                # Мой комментарий владельца
                general:
                  locale: "en"
                  enabled: false
                """;

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(1, Map.of()));

        assertTrue(result.changed());
        assertTrue(result.text().contains("# Мой комментарий владельца"));
        assertTrue(result.text().contains("# Раздел, добавленный в новом релизе"));
        assertTrue(result.text().contains("config-version: 1"));
        YamlConfiguration live = yaml(result.text());
        assertEquals("en", live.getString("general.locale"));
        assertFalse(live.getBoolean("general.enabled"));
        assertTrue(live.getBoolean("prestige.enabled"));
        assertEquals(0.03, live.getDouble("prestige.xp-per-step"), 1e-9);
    }

    @Test
    void secondRunIsIdempotentAndDoesNotRestoreDeletedKey() {
        String reference = """
                general:
                  locale: "ru"
                prestige:
                  enabled: true
                  xp-per-step: 0.03
                """;
        String user = """
                general:
                  locale: "en"
                """;
        ConfigSchemaEngine.Spec spec = config(1, Map.of());
        ConfigSchemaEngine.Result first = ConfigSchemaEngine.apply(reference, user, spec);
        assertTrue(first.changed());

        String ownerEdited = first.text().replace("  xp-per-step: 0.03", "");
        ConfigSchemaEngine.Result second = ConfigSchemaEngine.apply(reference, ownerEdited, spec);

        assertFalse(second.changed());
        assertFalse(ownerEdited.contains("xp-per-step"));
        assertTrue(ownerEdited.contains("config-version: 1"));
    }

    @Test
    void addsKeyInsideExistingSectionWithoutTouchingNeighbours() {
        String reference = """
                general:
                  locale: "ru"
                  enabled: true
                  refresh-ticks: 40
                """;
        String user = """
                general:
                  locale: "en"
                  enabled: false
                """;

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(1, Map.of()));

        assertTrue(result.changed());
        assertTrue(result.text().contains("locale: \"en\""));
        assertEquals(40, yaml(result.text()).getInt("general.refresh-ticks"));
        assertFalse(yaml(result.text()).getBoolean("general.enabled"));
    }

    @Test
    void migrationMapRenamesAndRemovesWithoutLosingOwnerValue() {
        String reference = """
                general:
                  locale: "ru"
                  renamed: 5
                """;
        String user = """
                config-version: 1
                general:
                  locale: "en"
                  old: 7
                  dead: true
                """;
        ConfigSchemaEngine.Step step = new ConfigSchemaEngine.Step(
                Map.of("general.old", "general.renamed"), Set.of("general.dead"));

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(2, Map.of(2, step)));

        assertTrue(result.changed());
        assertTrue(result.text().contains("config-version: 2"));
        assertFalse(result.text().contains("dead: true"));
        assertEquals(7, yaml(result.text()).getInt("general.renamed"));
        assertEquals("en", yaml(result.text()).getString("general.locale"));
    }

    @Test
    void newerSchemaIsNeverDowngraded() {
        String reference = """
                general:
                  locale: "ru"
                prestige:
                  enabled: true
                """;
        String user = """
                config-version: 3
                general:
                  locale: "en"
                """;

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(1, Map.of()));

        assertFalse(result.changed());
        assertFalse(result.text().contains("prestige"));
        assertTrue(result.report().stream().anyMatch(line -> line.contains("новее")));
    }

    @Test
    void messagesMarkerIsACommentSoFlattenStillWorks() {
        String reference = """
                messages:
                  greeting: "<green>Привет</green>"
                  farewell: "<gray>Пока</gray>"
                """;
        String user = """
                messages:
                  greeting: "<gold>Свой текст</gold>"
                """;
        ConfigSchemaEngine.Spec spec = new ConfigSchemaEngine.Spec("messages.yml",
                MESSAGES_MARKER, ConfigSchemaEngine.MarkerMode.COMMENT, 1, Map.of());

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user, spec);

        assertTrue(result.changed());
        assertTrue(result.text().startsWith("# messages-version: 1"));
        assertTrue(result.text().contains("<gold>Свой текст</gold>"));
        YamlConfiguration live = yaml(result.text());
        assertEquals(Set.of("messages"), live.getKeys(false));
        assertEquals(2, live.getConfigurationSection("messages").getKeys(false).size());
        // Повторный запуск не должен дописать второй комментарий-маркер.
        ConfigSchemaEngine.Result again = ConfigSchemaEngine.apply(reference, result.text(), spec);
        assertFalse(again.changed());
    }

    @Test
    void ownerIndentIsPreservedWhenAddingNewSection() {
        String reference = """
                general:
                  locale: "ru"
                prestige:
                  enabled: true
                """;
        String user = """
                general:
                    locale: "en"
                """;

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(1, Map.of()));

        assertTrue(result.changed());
        assertTrue(result.text().contains("prestige:\n    enabled: true"));
        assertEquals("en", yaml(result.text()).getString("general.locale"));
    }

    @Test
    void mixedIndentKeysAtSamePositionProduceReadableYaml() {
        // Все три отсутствующих ключа вставляются в одну точку — конец секции
        // trading. Два из них глубже (внутри gui) и обязаны пойти ПЕРВЫМИ:
        // иначе внешний скаляр «закроет» секцию и YAML не прочитается.
        String reference = """
                messages:
                  "trading":
                    "outer-new": "ONE"
                    "gui":
                      "inner-new-one": "TWO"
                      "inner-new-two": "THREE"
                  "admin":
                    "usage": "..."
                """;
        String user = """
                messages:
                  "trading":
                    "gui":
                      "old": "OLD"
                  "admin":
                    "usage": "..."
                """;
        ConfigSchemaEngine.Spec spec = new ConfigSchemaEngine.Spec("messages.yml",
                MESSAGES_MARKER, ConfigSchemaEngine.MarkerMode.COMMENT, 1, Map.of());

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user, spec);

        assertTrue(result.changed(), "смешанные отступы не должны ронять YAML");
        YamlConfiguration live = yaml(result.text());
        assertEquals("ONE", live.getString("messages.trading.outer-new"));
        assertEquals("TWO", live.getString("messages.trading.gui.inner-new-one"));
        assertEquals("THREE", live.getString("messages.trading.gui.inner-new-two"));
        assertEquals("OLD", live.getString("messages.trading.gui.old"));
        assertEquals("...", live.getString("messages.admin.usage"));
    }

    @Test
    void brokenOwnerYamlIsLeftAlone() {
        String reference = """
                general:
                  locale: "ru"
                """;
        String user = "general: [unclosed\n";

        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                config(1, Map.of()));

        assertFalse(result.changed());
        assertEquals(user, result.text());
    }
}
