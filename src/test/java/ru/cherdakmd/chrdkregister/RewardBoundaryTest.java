package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяем все игровые модули, чтобы новый источник не вернул обход оплаты. */
class RewardBoundaryTest {
    @Test void addonsNeverCallPaidGrantApisOrDispatchArbitraryConsoleRewards() throws Exception {
        Pattern paid = Pattern.compile("\\.grant(?:Pass|PassHours|Rank|RankPermanent|Entitlement)\\s*\\(");
        int modules = 0;
        try (var folders = Files.list(Path.of("."))) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                Path sources = folder.resolve("src/main/java");
                if (!Files.isDirectory(sources)) continue;
                modules++;
                try (var paths = Files.walk(sources)) {
                    for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                        String text = Files.readString(source);
                        assertFalse(paid.matcher(text).find(), "paid reward API in " + source);
                        assertFalse(text.contains("dispatchCommand("), "arbitrary console reward in " + source);
                    }
                }
            }
        }
        assertTrue(modules >= 25, "audit must cover all gameplay modules, not only known reward sources");
    }

    @Test void oldAnnouncementsAndMessagesStopPromisingPaidTime() {
        assertEquals("+2 ч. буста", GameplayRewardTexts.message("CHRDK_Streams", "awarded", "+2 ч. ранга"));
        assertEquals("+2 ч. буста", GameplayRewardTexts.message("CHRDK_Achievements", "reward-hours", "+2 ч. проходки"));
        assertEquals("30 дней ранга", GameplayRewardTexts.message("CHRDK_Core", "shop.name", "30 дней ранга"));
        assertTrue(GameplayRewardTexts.announcement("час эфира даёт сутки ранга").contains("час буста"));
        assertFalse(GameplayRewardTexts.announcement("Ваш приветственный ранг активен").contains("приветственный ранг"));
        assertFalse(GameplayRewardTexts.message("CHRDK_BattlePass", "reward", "+2 ч. ранга <gray>до</gray> <aqua>%until%</aqua>").contains("%until%"));
    }

    @Test void paymentSuffixDoesNotBecomeAnOptionalDurationOrGiftSender() {
        String[] raw = {"shopbuy", "Free", "nick-aurora", "30", "payment=trademc:123"};
        assertEquals("trademc:123", PaymentConfirmation.reference(raw));
        assertArrayEquals(new String[] {"shopbuy", "Free", "nick-aurora", "30"}, PaymentConfirmation.arguments(raw));
        assertEquals("", PaymentConfirmation.reference(new String[] {"shopbuy", "Free", "nick-aurora"}));
    }
}
