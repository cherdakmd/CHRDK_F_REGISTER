package ru.cherdakmd.chrdkanticheat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пины эталона Grim-конфигов ({@code anticheat/grim/}): все проверки Grim 2.x
 * покрыты ровно одной группой, команды — только мост chrdkac, значения
 * config.yml не ослаблены. Список проверок — из
 * {@code ac/grim/grimac/checks/impl} Grim 2.0.
 */
class GrimConfigsTest {

    /** Все проверки Grim 2.0 (имена из @CheckData). */
    private static final List<String> GRIM_CHECKS = List.of(
            "AimDuplicateLook", "AimModulo360", "AirLiquidBreak", "AirLiquidPlace",
            "AntiExplosion", "AntiKB", "BadPacketsA", "BadPacketsB", "BadPacketsC",
            "BadPacketsD", "BadPacketsE", "BadPacketsF", "BadPacketsG", "BadPacketsH",
            "BadPacketsI", "BadPacketsJ", "BadPacketsK", "BadPacketsL", "BadPacketsM",
            "BadPacketsN", "BadPacketsO", "BadPacketsP", "BadPacketsQ", "BadPacketsR",
            "BadPacketsS", "BadPacketsU", "BadPacketsV", "BadPacketsX", "BadPacketsY",
            "BadPacketsZ", "Baritone", "ChatA", "ChatB", "ChatC", "ChatD",
            "CrashA", "CrashB", "CrashC", "CrashD", "CrashE", "CrashF", "CrashG",
            "CrashH", "CrashI", "DuplicateRotPlace", "ElytraA", "ElytraB", "ElytraC",
            "ElytraD", "ElytraE", "ElytraF", "ElytraG", "ElytraH", "ElytraI",
            "ExploitA", "ExploitB", "FabricatedPlace", "FarBreak", "FarPlace",
            "FastBreak", "GroundSpoof", "Hitboxes", "InvalidBreak",
            "InvalidInteractCursor", "InvalidInteractTarget", "InvalidPlaceCursor",
            "InvalidPlaceFace", "MultiActionsA", "MultiActionsB", "MultiActionsC",
            "MultiActionsD", "MultiActionsE", "MultiActionsF", "MultiActionsG",
            "MultiBreak", "MultiInteractA", "MultiInteractB", "MultiPlace",
            "NegativeTimer", "NoFall", "NoSlow", "NoSwingBreak", "PacketOrderA",
            "PacketOrderB", "PacketOrderC", "PacketOrderD", "PacketOrderE",
            "PacketOrderF", "PacketOrderG", "PacketOrderH", "PacketOrderI",
            "PacketOrderJ", "PacketOrderK", "PacketOrderL", "PacketOrderM",
            "PacketOrderN", "PacketOrderO", "PacketOrderP", "Phase", "PositionBreakA",
            "PositionBreakB", "PositionPlace", "Post", "Reach", "RotationBreak",
            "RotationPlace", "SelfInteract", "Simulation", "SprintA", "SprintB",
            "SprintC", "SprintD", "SprintE", "SprintF", "SprintG", "TickTimer",
            "Timer", "TimerLimit", "TransactionOrder", "VehicleA", "VehicleB",
            "VehicleC", "VehicleD", "VehicleE", "VehicleF", "VehicleTimer",
            "WrongBreak");

    /** Альтернативные имена проверок (как в @CheckData). */
    private static final Map<String, String> ALIASES = Map.of("AntiKB", "AntiKnockback");

    private static final String BRIDGE = "1:1 chrdkac flag %player% %check_name% %vl% %verbose%";

    private static Path grimDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++) {
            Path candidate = dir.resolve("anticheat").resolve("grim");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("папка anticheat/grim не найдена");
    }

    /** Матч группы как в PunishmentManager Грима: подстрока + липкие исключения !имя. */
    private static Set<String> matchGroup(List<String> entries) {
        Set<String> matched = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        for (String raw : entries) {
            boolean negate = raw.startsWith("!");
            String needle = (negate ? raw.substring(1) : raw).toLowerCase(Locale.ROOT);
            for (String check : GRIM_CHECKS) {
                String alt = ALIASES.getOrDefault(check, "").toLowerCase(Locale.ROOT);
                if (check.toLowerCase(Locale.ROOT).contains(needle) || alt.contains(needle)) {
                    if (negate) {
                        excluded.add(check);
                    } else {
                        matched.add(check);
                    }
                }
            }
            matched.removeAll(excluded);
        }
        return matched;
    }

    @Test
    void everyGrimCheckIsCoveredByExactlyOneGroup() {
        ConfigurationSection punishments =
                YamlConfiguration.loadConfiguration(grimDir().resolve("punishments.yml").toFile())
                        .getConfigurationSection("Punishments");
        Map<String, Integer> coverage = new HashMap<>();
        for (String group : new ArrayList<>(punishments.getKeys(false))) {
            for (String check : matchGroup(punishments.getStringList(group + ".checks"))) {
                coverage.merge(check, 1, Integer::sum);
            }
        }
        for (String check : GRIM_CHECKS) {
            assertEquals(1, coverage.getOrDefault(check, 0),
                    "проверка " + check + " должна попасть ровно в одну группу");
        }
    }

    @Test
    void commandsUseOnlyTheBridgeAndNothingElse() throws IOException {
        YamlConfiguration file =
                YamlConfiguration.loadConfiguration(grimDir().resolve("punishments.yml").toFile());
        ConfigurationSection punishments = file.getConfigurationSection("Punishments");
        assertTrue(punishments.getKeys(false).size() >= 8, "группы не должны исчезать");
        for (String group : new ArrayList<>(punishments.getKeys(false))) {
            assertEquals(List.of(BRIDGE), punishments.getStringList(group + ".commands"),
                    "группа " + group + " шлёт срабатывания только в мост chrdkac");
            int window = punishments.getInt(group + ".remove-violations-after");
            assertTrue(window >= 1 && window <= 3600,
                    "группа " + group + ": окно нарушений в пределах 1..3600");
        }
        String raw = Files.readString(grimDir().resolve("punishments.yml"));
        for (String banned : List.of("[alert]", "[webhook]", "[proxy]", "kick ", "ban ")) {
            assertFalse(raw.contains(banned),
                    "эталон не исполняет других команд: " + banned);
        }
    }

    @Test
    void configStaysStrict() {
        YamlConfiguration grim =
                YamlConfiguration.loadConfiguration(grimDir().resolve("config.yml").toFile());
        assertEquals("V2", grim.getString("config-flavor"), "конфиг должен соответствовать Grim 2.x");
        assertTrue(grim.getBoolean("alerts.print-to-console"), "флаги видит и консоль");
        assertFalse(grim.getBoolean("experimental-checks"), "экспериментальные проверки выключены");
        assertFalse(grim.getBoolean("disable-default-resync-handler"), "десинк блоков чинится по миру сервера");
        assertTrue(grim.getBoolean("client-brand.disconnect-blacklisted-forge-versions"),
                "Forge со встроенными Reach-читами отключается");
        assertTrue(grim.getBoolean("Reach.block-impossible-hits"), "невозможные удары отменяются");
        assertEquals(1, grim.getInt("Simulation.setback-violation-threshold"),
                "предиктивный сетбэк с первого нарушения");
        assertEquals(1, grim.getInt("Phase.setbackvl"), "залезание в блоки карается сетбэком");
    }
}
