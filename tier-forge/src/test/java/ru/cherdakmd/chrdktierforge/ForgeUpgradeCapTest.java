package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Потолок свойств ковки на одном предмете: новые не вписываются сверх лимита,
 * уже накрученные ранее не срезаются.
 */
class ForgeUpgradeCapTest {

    @Test
    void inscribeStopsAtConfiguredCapAndExistingItemsKeepTheirProperties() throws Exception {
        String defaults = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdktierforge/ForgeUpgrade.java"));
        assertTrue(defaults.contains("DEFAULT_MAX_PER_ITEM = 7"),
                "по умолчанию на предмете помещается 7 разных свойств ковки");
        assertTrue(defaults.contains("MAX_PER_ITEM_LIMIT = 15"),
                "настройка ограничена разумным потолком");

        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("max-per-item: 7"), "конфиг показывает лимит явно");

        String materials = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdktierforge/ForgeMaterials.java"));
        assertTrue(materials.contains("forge-upgrade-cap"),
                "отказ называет причину: предел свойств на предмете");
        assertTrue(materials.contains("upgradesOf(main).size() >= maxPerItem"),
                "лимит считается по разным установленным свойствам");
        assertTrue(materials.contains("fail(\"forge-upgrade-cap\", \"max\", maxPerItem)"),
                "в сообщение подставляется сам лимит");

        String listener = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdktierforge/ForgeAnvilListener.java"));
        assertTrue(listener.contains("inscribeCapReached"),
                "наковальня не предлагает вписать свойство, когда лимит достигнут");
        assertTrue(listener.contains("forge-upgrade-cap"),
                "пустой список на наковальне объясняет причину");
    }
}
