package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PAPI-проход в TAB не должен перетирать анимированные кадры CHRDK. */
class TabExternalPlaceholdersTest {

    @Test
    void tabTokensSurviveThePapiRoundTripAndForeignTokensResolve() {
        String template = "<bold>%tab_brand%</bold> · %chrdk_rank% · <gray>%online%</gray>";
        String masked = TabListService.maskTabTokens(template);
        assertFalse(masked.contains("%tab_brand%"), "кадр должен быть скрыт от PAPI");
        assertTrue(masked.contains("tab_brand"));
        assertEquals(template, TabListService.unmaskTabTokens(masked));

        // Имитация работы PlaceholderAPI: чужой токен заменён, замаскированный кадр не тронут.
        String resolved = TabListService.unmaskTabTokens(masked.replace("%chrdk_rank%", "Хранитель"));
        assertTrue(resolved.contains("%tab_brand%"), "кадр вернулся для Messages.fill");
        assertTrue(resolved.contains("Хранитель"));
        assertTrue(resolved.contains("%online%"), "внутренние подстановки тоже ждут своего fill");
        assertFalse(resolved.contains("%chrdk_rank%"));
    }

    @Test
    void everyStockTabTokenIsMaskedReversibly() {
        for (String key : List.of("brand", "separator", "tip", "clock", "balance",
                "boost", "health", "ping", "world", "mspt")) {
            String token = "%tab_" + key + "%";
            String masked = TabListService.maskTabTokens(token);
            assertFalse(masked.contains("%"), key + " остался видимым для PAPI");
            assertEquals(token, TabListService.unmaskTabTokens(masked), key);
        }
    }

    @Test
    void noPlayerMeansNoBukkitAndNoLostTemplate() {
        String template = "%tab_balance% | %chrdk_rank% | <white>%player%</white>";
        // Без живого игрока метод даже не обращается к Bukkit/PAPI: детерминированный fallback.
        assertSame(template, TabListService.resolveExternalPlaceholders(template, null));
        assertSame(null, TabListService.resolveExternalPlaceholders(null, null));
    }
}
