package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Звание не должно стать тихой дырой в монетизации: покупки, сроки и отчёты
 * магазина остаются источником правды об оплате.
 */
class HonorPolicyTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/" + file));
    }

    @Test
    void paidRankGrantIsStillBlockedAndRankIdStaysPaid() throws Exception {
        String handler = source("RankCommandHandler.java");
        assertTrue(handler.contains("\"paid-only.blocked\""),
                "/rankadmin give не должен разблокировать бесплатную выдачу ранга");
        String api = source("ChrdkRegisterApiImpl.java");
        assertTrue(api.contains("deny(\"grantRank\""), "API выдачи ранга остаётся закрытым");
        assertTrue(api.contains("accessManager.store().rankLevel(playerName, System.currentTimeMillis())"),
                "rankId читает оплаченный ранг, а не эффективную ступень");
        assertTrue(api.contains("plugin.effectiveRankLevel(playerName)"),
                "привилегии для аддонов идут через эффективную ступень");
    }

    @Test
    void purchaseStorageNeverLearnsAboutHonors() throws Exception {
        assertFalse(source("RegistrationStore.java").contains("Honor"),
                "data.json аккаунтов не должен знать о званиях");
        assertFalse(source("ShopService.java").contains("Honor"),
                "доставка магазина не должна выдавать звания");
        String service = source("HonorService.java");
        assertTrue(service.contains("max(оплаченный ранг, grantLevel звания)"));
        assertTrue(service.contains("HONOR_GRANT"));
    }

    @Test
    void nameDecorationUsesHonorWithoutTouchingPurchaseDisplay() throws Exception {
        String chat = source("ChatService.java");
        assertTrue(chat.contains("honorService.active(playerName)"));
        assertTrue(chat.contains("accessManager.effectiveRankLevel(playerName)"));
        String menu = source("RankMenuService.java");
        assertFalse(menu.contains("Honor"), "/rank my показывает покупку, а не звание");
    }

    @Test
    void publicRegistryIsAvailableToEveryoneButEditsRequirePermission() throws Exception {
        String commands = source("HonorCommandHandler.java");
        assertTrue(commands.contains("honors.publicRegistry()"));
        assertTrue(commands.contains("honors.canEdit(sender)"));
        String service = source("HonorService.java");
        assertTrue(service.contains("PERMISSION_ADMIN"));
        assertTrue(service.contains("audit/*.jsonl"));
    }
}
