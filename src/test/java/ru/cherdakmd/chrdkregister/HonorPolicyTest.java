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
    void conflictsFoundInReviewAreFixed() throws Exception {
        String plugin = source("RegisterPlugin.java");
        assertTrue(plugin.contains("loadWithStatus(honorConfig)"));
        assertTrue(plugin.contains("auditInitialGrants(loaded.seeded())"),
                "стартовые выдачи обязаны попадать в аудит и сохраняться при старте");
        assertTrue(plugin.contains("new GuiChatInput(this)"));

        String service = source("HonorService.java");
        assertTrue(service.contains("auditInitialGrants"));
        assertTrue(service.contains("editGrantReason"));
        assertTrue(service.contains("AuditLog.HONOR_REVOKE, grant.player()"),
                "удаление звания пишет отдельный revoke на каждого игрока");
        assertFalse(service.contains("\"reason\", \"join\""),
                "алиас reason больше не подменяет описание звания");

        String chat = source("ChatService.java");
        assertTrue(chat.contains("config().showInTab()"), "show-in-tab теперь реально работает");
        assertTrue(chat.contains("config().overrideColor()"), "градиент не теряется за палитрой");
        assertTrue(chat.contains("chosenPaidColor"), "купленный цвет каталога не отбирается");

        String gui = source("HonorGui.java");
        assertFalse(gui.contains("AsyncChatEvent"), "GUI не владеет чатом сам");
        assertTrue(gui.contains("GRANT_REASON_EDIT"));
        assertFalse(source("AdminPanelGui.java").contains("AsyncChatEvent"),
                "админ-панель не владеет чатом сама");
        assertTrue(source("GuiChatInput.java").contains("onInventoryOpen"));

        assertTrue(source("LabelStyleService.java").contains("view(playerName, true)"),
                "TAB получает отдельный контекст оформления");
        assertTrue(source("api/ChrdkRegisterApi.java").contains("Эффективная ступень привилегий"),
                "javadoc rankLevel больше не обещает только донатный ранг");
        assertTrue(source("AccessManager.java").contains("int level = effectiveRankLevel(player.getName())"));
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
