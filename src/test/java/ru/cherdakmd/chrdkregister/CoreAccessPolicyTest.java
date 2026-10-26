package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обязательная 2FA и группа проходки «только за покупку» (1.4.105).
 *
 * <p>Проверяются чистые контракты: значения по умолчанию и переключатели конфига,
 * набор команд стадии привязки, политика выдачи LuckPerms-группы и то, что тестовая
 * оплата продлевает проходку, но не считается покупкой.</p>
 */
class CoreAccessPolicyTest {

    private static PluginConfig config(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return PluginConfig.fromBukkitConfig(configuration);
    }

    @Test
    void mandatoryTwoFactorIsOnByDefaultAndRespectsTheGlobalSwitch() throws Exception {
        PluginConfig defaults = PluginConfig.fromBukkitConfig(new YamlConfiguration());
        assertTrue(defaults.totpEnabled(), "2FA включена по умолчанию");
        assertTrue(defaults.totpRequired(), "2FA обязательна для всех по умолчанию");
        assertTrue(defaults.passGroupPaidOnly(), "группа проходки — только за покупку по умолчанию");

        assertFalse(config("totp:\n  required: false\n").totpRequired(),
                "totp.required: false возвращает добровольную 2FA");
        PluginConfig disabled = config("totp:\n  enabled: false\n  required: true\n");
        assertFalse(disabled.totpRequired(), "без totp.enabled обязательность не действует");
        assertFalse(config("integrations:\n  pass-group-paid-only: false\n").passGroupPaidOnly(),
                "старое поведение группы можно вернуть ключом");
    }

    @Test
    void enrollmentStageKeepsOnlyAuthenticationCommands() {
        for (String allowed : List.of("2fa", "totp", "twofactor", "login", "l", "pass", "logout", "session", "captcha")) {
            assertTrue(PlayerRestrictionListener.isCommandAllowed(
                            AccessManager.PlayerStage.NEEDS_2FA_SETUP, allowed),
                    "в стадии привязки нужна команда " + allowed);
        }
        for (String blocked : List.of("register", "reg", "spawn", "home", "gamemode", "pay", "ah")) {
            assertFalse(PlayerRestrictionListener.isCommandAllowed(
                            AccessManager.PlayerStage.NEEDS_2FA_SETUP, blocked),
                    "до привязки 2FA команда " + blocked + " должна быть закрыта");
        }
        assertEquals("привязка 2FA",
                PlaceholderValues.describeStage(AccessManager.PlayerStage.NEEDS_2FA_SETUP));
        assertEquals("needs_2fa_setup",
                PlaceholderValues.stageId(AccessManager.PlayerStage.NEEDS_2FA_SETUP));
    }

    @Test
    void passGroupIsGrantedOnlyForRealGrants() {
        assertFalse(LuckPermsPassSync.grantsGroup(false, true),
                "тестовый период и /pass testpay группу не выдают");
        assertTrue(LuckPermsPassSync.grantsGroup(true, true), "покупка выдаёт группу");
        assertFalse(LuckPermsPassSync.grantsGroup(false, false),
                "при pass-group-paid-only: false группа выдаётся и в тестовом режиме");
        assertEquals("group.pass", LuckPermsPassSync.groupNodeName(" pass "));
    }

    @Test
    void testPaymentExtendsPassButDoesNotCountAsPurchase(@TempDir Path tempDir) throws Exception {
        RegistrationStore store = new RegistrationStore(tempDir, Logger.getAnonymousLogger());
        PluginConfig config = PluginConfig.fromBukkitConfig(new YamlConfiguration());
        List<Boolean> paidFlags = new ArrayList<>();
        List<String> notified = new ArrayList<>();
        DonationService service = new DonationService(
                store,
                () -> config,
                new AuditLog(tempDir, Logger.getAnonymousLogger(), config.timeZone()),
                new NotificationService(() -> config, Logger.getAnonymousLogger()),
                Logger.getAnonymousLogger(),
                Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()),
                (playerName, passUntil, sourceDescription, paid) -> {
                    notified.add(playerName);
                    paidFlags.add(paid);
                });

        long now = 1_700_000_000_000L;
        DonationService.Outcome test = service.process(
                new DonatePayClient.Transaction(-now, "TestPlayer", BigDecimal.valueOf(500),
                        "success", "donation", now, true),
                now, false, RegistrationStore.DONATION_SOURCE_DONATEPAY);
        assertFalse(test.granted(), "тест никогда не выдаёт платных прав");
        assertFalse(store.hasActivePass("TestPlayer", now + 1));
        assertTrue(store.getAccount("TestPlayer").isEmpty(), "даже платный аккаунт не создаётся");
        assertTrue(notified.isEmpty());
        assertTrue(paidFlags.isEmpty());
        assertFalse(service.approve(-now, "TestPlayer", 0, "Admin", now + 1));

        DonationService.Outcome real = service.process(
                new DonatePayClient.Transaction(42L, "TestPlayer", BigDecimal.valueOf(500),
                        "success", "donation", now + 1_000L),
                now + 1_000L, false, RegistrationStore.DONATION_SOURCE_DONATEPAY);
        assertTrue(real.granted());
        assertEquals(1, store.getAccount("TestPlayer").orElseThrow().paymentsCount,
                "реальная оплата засчитывается как покупка");
        assertTrue(store.getAccount("TestPlayer").orElseThrow().totalPaidKopecks > 0);
        assertEquals(List.of(true), paidFlags);
        assertEquals("donatepay", store.getAccount("TestPlayer").orElseThrow().lastGrantSource,
                "реальная оплата ставит источник donatepay");
    }

    @Test
    void testPaymentOfTrialAccountKeepsItTrialOnly(@TempDir Path tempDir) {
        RegistrationStore store = new RegistrationStore(tempDir, Logger.getAnonymousLogger());
        long now = 1_700_000_000_000L;
        store.registerPassword("TrialPlayer", java.util.UUID.randomUUID(), "Secret123", "192.0.2.10",
                now, 14);
        assertFalse(store.getAccount("TrialPlayer").orElseThrow().hasRank(now));

        store.grantPassFromDonation("TrialPlayer", now, now, 30L * 86_400_000L, false, true);
        RegistrationStore.PlayerAccount account = store.getAccount("TrialPlayer").orElseThrow();
        assertEquals(0, account.paymentsCount, "тестовая оплата не делает из пробного аккаунта платного");
        assertEquals("", account.lastGrantSource);
        assertFalse(account.hasRank(now), "тест не выдаёт платных прав");
    }
    @Test
    void manualApprovalRequiresRealPendingReceiptAndCannotBeRepeated(@TempDir Path dir) {
        var store = new RegistrationStore(dir, Logger.getAnonymousLogger());
        var config = PluginConfig.fromBukkitConfig(new YamlConfiguration());
        var service = new DonationService(store, () -> config,
                new AuditLog(dir, Logger.getAnonymousLogger(), config.timeZone()),
                new NotificationService(() -> config, Logger.getAnonymousLogger()),
                Logger.getAnonymousLogger(),
                Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()), null);
        long now = 1_800_000_000_000L;
        var receipt = new RegistrationStore.DonationRecord();
        receipt.id = 123;
        receipt.donorName = "Paid";
        receipt.amountKopecks = 500_00L;
        receipt.status = RegistrationStore.DonationStatus.PENDING_REVIEW.name();
        store.recordDonation(receipt);
        assertFalse(service.approve(123, "Paid", 3650, "Admin", now), "no arbitrary extension beyond paid tariff");
        assertTrue(service.approve(123, "Paid", 0, "Admin", now));
        long until = store.getAccount("Paid").orElseThrow().passUntil;
        assertTrue(until > now);
        assertFalse(service.approve(123, "Paid", 0, "Admin", now + 1));
        assertEquals(until, store.getAccount("Paid").orElseThrow().passUntil);
        assertFalse(store.reservePayment("donatepay:123", "RCON", "shopbuy Paid duplicate", now));
        var small = new RegistrationStore.DonationRecord();
        small.id = 124;
        small.donorName = "Free";
        small.amountKopecks = 1;
        small.status = RegistrationStore.DonationStatus.PENDING_AMOUNT.name();
        store.recordDonation(small);
        assertFalse(service.claim(124, "Free", "ip", now).isEmpty(), "must not announce activation after failed approval");
        assertFalse(store.hasActivePass("Free", now));
    }

}
