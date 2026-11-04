package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.HomeClaimPolicy;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.282: граница «дом ↔ приват» и место её вызова в командах/кровати. */
class HomeClaimGuardTest {

    @Test void absentPolicyDoesNotBlockHomes() {
        assertTrue(HomeClaimGuard.allowed(null, "Steve", "world", 10, 10));
    }

    @Test void policyDecisionIsHonouredWithExactArguments() {
        String[] seen = new String[4];
        HomeClaimPolicy deny = (player, world, x, z) -> {
            seen[0] = player;
            seen[1] = world;
            seen[2] = String.valueOf(x);
            seen[3] = String.valueOf(z);
            return false;
        };
        assertFalse(HomeClaimGuard.allowed(deny, "Steve", "world", 33, -17));
        assertArrayEquals(new String[]{"Steve", "world", "33", "-17"}, seen);
        assertTrue(HomeClaimGuard.allowed((player, world, x, z) -> true, "Steve", "world", 33, -17));
    }

    @Test void blockCoordinatesUseFloorNotTruncation() {
        assertEquals(-1, HomeClaimGuard.blockOf(-0.5));
        assertEquals(-17, HomeClaimGuard.blockOf(-16.5));
        assertEquals(0, HomeClaimGuard.blockOf(0.5));
        assertEquals(16, HomeClaimGuard.blockOf(16.5));
    }

    /** Проверка до котировки и повторно после consume: за 30 с владелец чанка мог смениться. */
    @Test void setHomeChecksBeforeQuoteAndAfterConfirm() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java"));
        int first = source.indexOf("HomeClaimPolicy claimPolicy = HomeClaimGuard.policy();");
        int offer = source.indexOf("confirmations.offer");
        int consume = source.indexOf("confirmations.consume");
        int repeat = source.indexOf("HomeClaimGuard.allowed(claimPolicy", consume);
        assertTrue(first > 0 && first < offer, "проверка обязана быть до выдачи согласия");
        assertTrue(repeat > consume, "проверка обязана повторяться после успешного confirm");
        assertTrue(source.contains("\"home.foreign-claim\""));
        assertTrue(source.contains("\"home.foreign-claim-teleport\""));
    }

    /** Телепорт тоже закрыт: старые точки не удаляем, но не даём к ним прийти. */
    @Test void homeAndBedHaveTheSameBoundary() throws Exception {
        String commands = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java"));
        assertEquals(2, count(commands, "HomeClaimGuard.allowed(HomeClaimGuard.policy()"),
                "handleHome и executeHome (до и во время warmup)");
        String bed = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkteleports/BedHomeListener.java"));
        assertEquals(3, count(bed, "HomeClaimGuard.allowed("),
                "сон, импорт кровати при входе, возрождение");
        assertTrue(bed.contains("\"home.foreign-claim-bed\""));
    }

    private static int count(String text, String needle) {
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
    }
}
