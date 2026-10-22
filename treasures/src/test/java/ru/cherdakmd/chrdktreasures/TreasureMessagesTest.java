package ru.cherdakmd.chrdktreasures;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт «код ↔ messages.yml»: у каждой находки, редкости, региона и вида
 * эффекта есть текст, все шаблоны разбираются MiniMessage, имена уникальны.
 */
class TreasureMessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_.-]+%");

    private static final List<String> UI_KEYS = List.of(
            "menu-title", "menu-region-name", "menu-region-lore", "menu-region-click",
            "menu-mythic-name", "menu-mythic-lore", "menu-summary-name", "menu-summary-mined",
            "menu-summary-found", "menu-summary-collection", "menu-summary-next",
            "menu-summary-next-none", "menu-top-name", "menu-top-lore", "menu-sounds-name",
            "menu-particles-name", "menu-announce-name", "menu-toggle-on", "menu-toggle-off",
            "menu-close", "menu-back", "menu-locked-name", "menu-locked-lore", "region-title",
            "mythic-title", "item-found", "top-title", "top-empty", "top-entry-name",
            "top-entry-lore", "treasures-disabled", "no-permission", "player-only",
            "unknown-subcommand", "unknown-filter", "help-header", "help-menu", "help-list",
            "help-stats", "help-top", "help-toggle", "help-footer", "list-header",
            "list-region-line", "list-item-found", "list-item-locked", "list-empty",
            "list-footer", "top-header", "top-line", "stats-header", "stats-mined",
            "stats-tier", "stats-collection", "stats-unlocked", "stats-next",
            "stats-next-none", "stats-next-tier", "stats-footer", "toggle-usage",
            "toggle-on", "toggle-off", "feature-sounds", "feature-particles",
            "feature-announce", "collection-milestone", "treasure-found-named",
            "treasure-found-loot", "treasure-broadcast-named", "treasure-broadcast-loot",
            "double-ore", "admin-reloaded", "admin-player-not-found", "admin-unknown-item",
            "admin-give-failed", "admin-gave", "admin-reset", "admin-info", "admin-usage",
            "menu-summary-rank", "menu-summary-streak", "help-stats-other", "stats-rank",
            "stats-rank-progress", "stats-rank-max", "stats-streak", "stats-set",
            "stats-set-none", "streak-bonus",
            "rank-up", "rank-up-broadcast", "title-found", "title-found-named",
            "title-found-loot", "actionbar-found", "admin-resetall", "admin-resetall-confirm",
            "admin-chance-set", "admin-chance-reset", "admin-chance-unknown-tier",
            "admin-chance-invalid", "help-loot", "loot-header", "loot-summary",
            "loot-tier-count", "loot-tier-header", "loot-line", "loot-line-locked",
            "loot-region-header", "loot-region-tier", "loot-region-line",
            "loot-region-line-locked", "loot-hint", "loot-empty", "loot-truncated",
            "loot-item-name", "loot-item-lore", "item-ability", "item-ability-bow",
            "item-curse", "item-set", "ability-used", "ability-cooldown", "curse-trigger",
            "help-set", "set-header", "set-line", "set-active", "set-none", "set-hint",
            "set-disabled", "set-detail-header", "set-detail-progress", "set-partial",
            "set-full");

    private static ConfigurationSection messages() {
        try (var stream = TreasureMessagesTest.class.getClassLoader()
                .getResourceAsStream("messages.yml")) {
            assertNotNull(stream, "messages.yml не найден в ресурсах");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                ConfigurationSection root = yaml.getConfigurationSection("messages");
                assertNotNull(root, "в messages.yml нет секции messages");
                return root;
            }
        } catch (Exception exception) {
            throw new AssertionError("не удалось прочитать messages.yml", exception);
        }
    }

    private static void assertText(ConfigurationSection root, String key) {
        String value = root.getString(key);
        assertNotNull(value, "нет текста «" + key + "»");
        assertFalse(value.isBlank(), "пустой текст «" + key + "»");
        try {
            MiniMessage.miniMessage().deserialize(PLACEHOLDER.matcher(value).replaceAll("X"));
        } catch (RuntimeException exception) {
            throw new AssertionError("сломанная разметка в «" + key + "»: " + value, exception);
        }
    }

    @Test
    void everyTreasureHasNameAndDescription() {
        ConfigurationSection root = messages();
        Set<String> names = new HashSet<>();
        for (TreasureItemDef def : TreasureRegistry.all()) {
            assertText(root, "treasure." + def.id() + ".name");
            assertText(root, "treasure." + def.id() + ".desc");
            assertTrue(names.add(root.getString("treasure." + def.id() + ".name")),
                    "повторяющееся имя у " + def.id());
            assertTrue(root.getString("treasure." + def.id() + ".desc").length() >= 26,
                    "слишком короткое описание у " + def.id());
        }
        assertEquals(TreasureRegistry.size(), names.size());
    }

    @Test
    void tiersRegionsAndEffectsAreLocalised() {
        ConfigurationSection root = messages();
        for (TreasureTier tier : TreasureTier.values()) {
            assertText(root, "tier." + tier.id());
            assertText(root, "item-name-" + tier.id());
            assertText(root, "item-tier-" + tier.id());
            assertTrue(root.getString("item-name-" + tier.id()).contains("%name%"),
                    "в имени предмета нет %name%: " + tier.id());
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            assertText(root, "region." + region.id());
        }
        for (TreasureEffect.Kind kind : TreasureEffect.Kind.values()) {
            String key = "item-effect-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
            assertText(root, key);
            assertTrue(root.getString(key).contains("%value%"),
                    "в описании эффекта нет %value%: " + key);
        }
        assertText(root, "item-desc");
        assertTrue(root.getString("item-desc").contains("%text%"));
    }

    @Test
    void abilitiesCursesAndSetsAreLocalised() {
        ConfigurationSection root = messages();
        Set<String> abilityNames = new HashSet<>();
        for (TreasureAbility.Kind kind : TreasureAbility.Kind.values()) {
            assertText(root, kind.textKey());
            assertTrue(abilityNames.add(root.getString(kind.textKey())),
                    "повторяющееся имя способности: " + kind.id());
        }
        Set<String> curseNames = new HashSet<>();
        for (TreasureCurse.Kind kind : TreasureCurse.Kind.values()) {
            assertText(root, kind.textKey());
            assertTrue(curseNames.add(root.getString(kind.textKey())),
                    "повторяющееся имя проклятия: " + kind.id());
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            assertText(root, "region." + region.id());
        }
        assertEquals(TreasureAbility.Kind.values().length, abilityNames.size());
        assertEquals(TreasureCurse.Kind.values().length, curseNames.size());
        assertTrue(root.getString("item-ability").contains("%name%"));
        assertTrue(root.getString("item-ability").contains("%cooldown%"));
        assertTrue(root.getString("item-curse").contains("%name%"));
        assertTrue(root.getString("item-set").contains("%set%"));
        assertTrue(root.getString("ability-used").contains("%name%"));
        assertTrue(root.getString("ability-cooldown").contains("%seconds%"));
        assertTrue(root.getString("set-line").contains("%pieces%"));
        assertTrue(root.getString("set-partial").contains("%bonus%"));
    }

    @Test
    void ranksAreLocalisedAndUnique() {
        ConfigurationSection root = messages();
        Set<String> names = new HashSet<>();
        for (TreasureRank rank : TreasureRank.values()) {
            assertText(root, "rank." + rank.id());
            assertTrue(names.add(root.getString("rank." + rank.id())),
                    "повторяющееся имя звания: " + rank.id());
        }
        assertEquals(TreasureRank.values().length, names.size());
    }

    @Test
    void allUiTextsExistAndRender() {
        ConfigurationSection root = messages();
        for (String key : UI_KEYS) {
            assertText(root, key);
        }
    }

    @Test
    void noUnquotedColonsInMessages() throws Exception {
        try (var stream = TreasureMessagesTest.class.getClassLoader()
                .getResourceAsStream("messages.yml")) {
            assertNotNull(stream);
            List<String> lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 0 || trimmed.endsWith(":")) {
                    continue;
                }
                String value = trimmed.substring(colon + 1).trim();
                assertTrue(value.startsWith("\"") || value.startsWith("'"),
                        "значение без кавычек: " + trimmed);
            }
        }
    }
}
