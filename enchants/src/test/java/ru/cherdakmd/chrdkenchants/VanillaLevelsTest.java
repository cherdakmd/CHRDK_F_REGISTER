package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Надванильные уровни ванильных чар (2.0.16): «защита X» переживает совмещение
 * двух предметов и растёт до потолка {@code rules.vanilla-level-cap}, входные
 * уровни не режутся.
 */
class VanillaLevelsTest {

    @Test
    void overLevelMergeLivesInItsOwnListenerWithMinimalInfluence() throws Exception {
        String listener = source("VanillaMergeListener");
        assertTrue(listener.contains("priority = EventPriority.LOWEST"),
                "влияем до механик кузницы: их пары перезаписывают результат после нас");
        assertTrue(listener.contains("ourBook(left) || ourBook(right)"),
                "пара с нашей книгой живёт в AnvilApplyListener");
        assertTrue(listener.contains("AnvilPrice.strengthens"),
                "слияние без единого усиления не выдаёт результат");
        assertTrue(listener.contains("event.setResult(null)"),
                "зажатое ванилью падение чар не считается изменением");
        assertTrue(listener.contains("sameOutsideEnchants"),
                "ремонт и имя не отменяют результат");
        assertTrue(listener.contains("conflictsWith"),
                "конфликты решает ванильная наковальня");
        assertTrue(listener.contains("canEnchantItem"),
                "новая чара должна подходить вещи");
        assertTrue(listener.contains("AnvilCosts.price"),
                "выдуманный результат обязан иметь цену");
    }

    @Test
    void levelsAreWrittenIgnoringPlatformClamp() throws Exception {
        String levels = source("VanillaLevels");
        assertTrue(levels.contains("Math.max(levelCap, enchantment.getMaxLevel())"),
                "потолок слияния — не ниже ванильного максимума");
        assertTrue(levels.contains("addStoredEnchant(entry.getKey(), entry.getValue(), true)"));
        assertTrue(levels.contains("addEnchant(entry.getKey(), entry.getValue(), true)"));
        assertTrue(levels.contains("static void restore("),
                "зажатые ванилью уровни результата поднимаются обратно");
    }

    @Test
    void capIsConfigurableAndWiredEverywhere() throws Exception {
        assertTrue(source("EnchantsConfig").contains("rules.vanilla-level-cap"),
                "предел — в конфиге правил");
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("vanilla-level-cap: 10"));
        assertTrue(source("AnvilApplyListener").contains("VanillaLevels.restore"),
                "наши пары тоже возвращают надванильные уровни");
        assertTrue(source("EnchantsPlugin").contains("new VanillaMergeListener(this)"));
    }

    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
}
