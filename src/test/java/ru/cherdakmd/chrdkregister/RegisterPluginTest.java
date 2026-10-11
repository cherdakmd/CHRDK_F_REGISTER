package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterPluginTest {

    @Test
    void passwordHasherVerifiesAndSaltsCorrectly() {
        String hash1 = PasswordHasher.hash("MySecretPass123!");
        String hash2 = PasswordHasher.hash("MySecretPass123!");
        assertNotEquals(hash1, hash2, "Each hash should use a random salt");
        assertTrue(PasswordHasher.verify("MySecretPass123!", hash1));
        assertTrue(PasswordHasher.verify("MySecretPass123!", hash2));
        assertFalse(PasswordHasher.verify("WrongPass", hash1));
        assertFalse(PasswordHasher.verify("", hash1));
    }

    @Test
    void totpServiceMatchesRfcVectorsAndHandlesWindowAndReplay() {
        // RFC 4226 / RFC 6238 standard 20-byte ASCII test secret: "12345678901234567890"
        byte[] rawSecret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String base32Secret = TotpService.encodeBase32(rawSecret);
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", base32Secret);
        assertArrayEquals(rawSecret, TotpService.decodeBase32(base32Secret));
        assertArrayEquals(rawSecret, TotpService.decodeBase32("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq="));

        // RFC 4226 Appendix D & RFC 6238 Appendix B test vectors (6 digits, SHA1)
        assertEquals("755224", TotpService.generateCodeAtStep(rawSecret, 0L));
        assertEquals("287082", TotpService.generateCode(base32Secret, 59_000L));
        assertEquals("081804", TotpService.generateCode(base32Secret, 1_111_111_109_000L));
        assertEquals("050471", TotpService.generateCode(base32Secret, 1_111_111_111_000L));
        assertEquals("005924", TotpService.generateCode(base32Secret, 1_234_567_890_000L));
        assertEquals("279037", TotpService.generateCode(base32Secret, 2_000_000_000_000L));

        // Time window & replay protection check
        long epochMillis = 1_234_567_890_000L; // step 41152263 -> "005924"
        assertTrue(TotpService.verifyCode(base32Secret, "005 924", epochMillis, 1));
        assertTrue(TotpService.verifyCode(base32Secret, "005924", epochMillis + 30_000L, 1));
        assertFalse(TotpService.verifyCode(base32Secret, "005924", epochMillis + 60_000L, 1));

        OptionalLong matchedStep = TotpService.verifyAndGetStep(base32Secret, "005924", epochMillis, 1, -1L);
        assertTrue(matchedStep.isPresent());
        assertEquals(41152263L, matchedStep.getAsLong());

        // Replaying the same or earlier time step must be rejected
        assertTrue(TotpService.verifyAndGetStep(base32Secret, "005924", epochMillis, 1, matchedStep.getAsLong()).isEmpty());

        // Generated secret & URI format
        String randomSecret = TotpService.generateSecret();
        assertEquals(32, randomSecret.length());
        assertEquals(39, TotpService.formatSecretForDisplay(randomSecret).length());
        String uri = TotpService.buildOtpAuthUri("CHRDK", "TestPlayer", randomSecret);
        assertTrue(uri.startsWith("otpauth://totp/CHRDK:TestPlayer?secret=" + randomSecret));
        assertTrue(uri.contains("&issuer=CHRDK&algorithm=SHA1&digits=6&period=30"));
    }

    @Test
    void storeHandlesManualAndDonatePayPassesSessionsAndTotp2fa(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("test");
        RegistrationStore store = new RegistrationStore(tempDir, logger);
        long now = 1_700_000_000_000L;
        long thirtyDays = Duration.ofDays(30).toMillis();

        assertFalse(store.hasActivePass("CherdakPlayer", now));

        // Grant pass via DonatePay
        Optional<RegistrationStore.PlayerAccount> granted = store.grantPassFromDonation(
                "CherdakPlayer", now, now, thirtyDays, false);
        assertTrue(granted.isPresent());
        assertEquals(now + thirtyDays, granted.get().passUntil);
        assertTrue(store.hasActivePass("cherdakplayer", now + 1000));
        assertFalse(store.hasActivePass("CherdakPlayer", now + thirtyDays + 1));

        // Extend pass manually by another 30 days
        RegistrationStore.PlayerAccount extended = store.grantPassManual("CherdakPlayer", now + 5000, thirtyDays);
        assertEquals(now + thirtyDays + thirtyDays, extended.passUntil);

        // Password registration and IP session
        UUID uuid = UUID.randomUUID();
        assertFalse(store.isRegistered("CherdakPlayer"));
        assertTrue(store.registerPassword("CherdakPlayer", uuid, "Secret123", "192.0.2.10", now));
        assertTrue(store.isRegistered("CherdakPlayer"));
        assertTrue(store.verifyPassword("CherdakPlayer", "Secret123"));
        assertFalse(store.verifyPassword("CherdakPlayer", "wrong"));

        long thirtyMinutes = Duration.ofMinutes(30).toMillis();
        assertTrue(store.hasValidIpSession("CherdakPlayer", "192.0.2.10", now + Duration.ofMinutes(15).toMillis(), thirtyMinutes));
        assertFalse(store.hasValidIpSession("CherdakPlayer", "198.51.100.7", now + Duration.ofMinutes(5).toMillis(), thirtyMinutes));
        assertFalse(store.hasValidIpSession("CherdakPlayer", "192.0.2.10", now + Duration.ofMinutes(31).toMillis(), thirtyMinutes));

        // TOTP 2FA setup, login verification, replay protection, and persistence
        assertFalse(store.isTotpEnabled("CherdakPlayer"));
        String totpSecret = TotpService.generateSecret();
        long setupStep = TotpService.getTimeStep(now);
        assertTrue(store.enableTotp("CherdakPlayer", totpSecret, setupStep, now));
        assertTrue(store.isTotpEnabled("CherdakPlayer"));
        assertEquals(1, store.getTotpEnabledCount());

        // Same time step used during setup cannot be replayed immediately
        String setupCode = TotpService.generateCode(totpSecret, now);
        assertFalse(store.verifyTotp("CherdakPlayer", setupCode, now, 0));

        // Next 30-second window code succeeds once and then blocks replay
        long nextWindowMillis = now + 30_000L;
        String nextCode = TotpService.generateCode(totpSecret, nextWindowMillis);
        assertTrue(store.verifyTotp("CherdakPlayer", nextCode, nextWindowMillis, 1));
        assertFalse(store.verifyTotp("CherdakPlayer", nextCode, nextWindowMillis, 1));

        // Reload from disk and verify persistence of pass, password, and 2FA
        RegistrationStore reloaded = new RegistrationStore(tempDir, logger);
        assertTrue(reloaded.hasActivePass("CherdakPlayer", now));
        assertTrue(reloaded.verifyPassword("CherdakPlayer", "Secret123"));
        assertTrue(reloaded.isTotpEnabled("CherdakPlayer"));

        long thirdWindowMillis = now + 60_000L;
        String thirdCode = TotpService.generateCode(totpSecret, thirdWindowMillis);
        assertTrue(reloaded.verifyTotp("CherdakPlayer", thirdCode, thirdWindowMillis, 1));

        // Disable 2FA and verify
        assertTrue(reloaded.disableTotp("CherdakPlayer"));
        assertFalse(reloaded.isTotpEnabled("CherdakPlayer"));
        assertEquals(0, reloaded.getTotpEnabledCount());
    }

    @Test
    void parseDonatePayTransactionsExtractsDonorNicknameAndAmount() throws Exception {
        String sampleJson = """
                {
                  "status": "success",
                  "data": [
                    {
                      "id": 501,
                      "type": "donation",
                      "status": "success",
                      "sum": "150.00",
                      "vars": {
                        "name": "TestPlayer_01",
                        "comment": "За проходку"
                      },
                      "created_at": {
                        "date": "2026-10-11 19:15:30.000000",
                        "timezone_type": 3,
                        "timezone": "Europe/Moscow"
                      }
                    },
                    {
                      "id": 500,
                      "type": "donation",
                      "status": "success",
                      "sum": "100",
                      "vars": {
                        "name": "UnderpaidUser"
                      },
                      "created_at": {
                        "date": "2026-10-11 19:10:00",
                        "timezone": "Europe/Moscow"
                      }
                    }
                  ]
                }
                """;

        List<DonatePayClient.Transaction> transactions = DonatePayClient.parseTransactionsResponse(sampleJson);
        assertEquals(2, transactions.size());
        assertEquals(500L, transactions.get(0).id());
        assertEquals(501L, transactions.get(1).id());

        DonatePayClient.Transaction validTx = transactions.get(1);
        assertEquals("TestPlayer_01", validTx.donorName());
        assertEquals(0, validTx.amount().compareTo(BigDecimal.valueOf(150)));
        assertTrue(validTx.isSuccessfulDonation());
    }

    @Test
    void commandRestrictionsMatchPlayerStage() {
        assertEquals("register", PlayerRestrictionListener.extractCommandLabel("/register pass1 pass1"));
        assertEquals("reg", PlayerRestrictionListener.extractCommandLabel("/chrdk_f_register:reg pass1 pass1"));
        assertEquals("login", PlayerRestrictionListener.extractCommandLabel("/login pass1"));
        assertEquals("2fa", PlayerRestrictionListener.extractCommandLabel("/2fa 123456"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "pass"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "register"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "login"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "2fa"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "spawn"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "register"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "reg"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "login"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "2fa"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "login"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "l"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "register"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "2fa"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "2fa"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "totp"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "twofactor"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "login"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "pass"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "register"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_2FA, "spawn"));
    }

    @Test
    void chatConfigDefaultsAndChannelParsing() {
        ChatConfig defaults = new ChatConfig();
        assertTrue(defaults.enabled());
        assertEquals(ChatConfig.Channel.LOCAL, defaults.defaultChannel());
        assertEquals(100.0, defaults.localRadius(), 0.001);
        assertEquals("!", defaults.quickGlobalPrefix());
        assertEquals(1_000, defaults.cooldownMillis());
        assertTrue(defaults.privateMessagesEnabled());
        assertTrue(defaults.autoMessagesEnabled());
        assertFalse(defaults.autoMessages().isEmpty());
        assertFalse(defaults.autoMessagesPrefix().isEmpty());

        assertEquals(ChatConfig.Channel.GLOBAL, ChatConfig.Channel.parse("GLOBAL", ChatConfig.Channel.LOCAL));
        assertEquals(ChatConfig.Channel.LOCAL, ChatConfig.Channel.parse("local", ChatConfig.Channel.GLOBAL));
        assertEquals(ChatConfig.Channel.GLOBAL, ChatConfig.Channel.parse("global", ChatConfig.Channel.LOCAL));
        assertEquals(ChatConfig.Channel.LOCAL, ChatConfig.Channel.parse("", ChatConfig.Channel.LOCAL));
        assertEquals(ChatConfig.Channel.GLOBAL, ChatConfig.Channel.parse("нечто", ChatConfig.Channel.GLOBAL));

        assertEquals(ChatConfig.Channel.GLOBAL, ChatConfig.Channel.LOCAL.toggled());
        assertEquals(ChatConfig.Channel.LOCAL, ChatConfig.Channel.GLOBAL.toggled());
    }

    @Test
    void chatFormattingHelpersWork() {
        String[] segments = ChatService.splitFormat("&7[Л] &f%player%&7: &f%message%",
                Map.of("%player%", "CherdakPlayer", "%world%", "world"));
        assertEquals("&7[Л] &fCherdakPlayer&7: &f", segments[0]);
        assertEquals("", segments[1]);

        String[] privateMessage = ChatService.splitFormat(
                "&7[&d%sender% &7→ &d%target%&7] &f%message% &7(конец)",
                Map.of("%sender%", "Sender", "%target%", "Target"));
        assertEquals("&7[&dSender &7→ &dTarget&7] &f", privateMessage[0]);
        assertEquals(" &7(конец)", privateMessage[1]);

        // A format without %message% keeps the whole line in the head part
        String[] withoutPlaceholder = ChatService.splitFormat("&6[Г] %player%", Map.of("%player%", "Steve"));
        assertEquals("&6[Г] Steve", withoutPlaceholder[0]);
        assertEquals("", withoutPlaceholder[1]);

        assertEquals(NamedTextColor.WHITE, ChatService.extractTrailingColor("&7[Л] &fИгрок&7: &f", NamedTextColor.GRAY));
        assertEquals(NamedTextColor.GOLD, ChatService.extractTrailingColor("&6[Г] ", NamedTextColor.WHITE));
        assertEquals(NamedTextColor.GRAY, ChatService.extractTrailingColor("без цветовых кодов", NamedTextColor.GRAY));
        assertEquals(NamedTextColor.WHITE, ChatService.extractTrailingColor("&7Текст&r", NamedTextColor.GRAY));

        // Caps protection
        assertEquals("привет всем", ChatService.applyCapsRule("ПРИВЕТ ВСЕМ", 70));
        assertEquals("Привет всем", ChatService.applyCapsRule("Привет всем", 70));
        assertEquals("OK", ChatService.applyCapsRule("OK", 70));

        // Duplicate protection
        assertTrue(ChatService.isDuplicate("Привет", "привет"));
        assertFalse(ChatService.isDuplicate("Привет", "привет всем"));
        assertFalse(ChatService.isDuplicate("", ""));

        // Mentions
        assertTrue(ChatService.hasMention("привет @CherdakPlayer как дела", "CherdakPlayer"));
        assertTrue(ChatService.hasMention("привет @cherdakplayer", "CherdakPlayer"));
        assertFalse(ChatService.hasMention("привет @CherdakPlayer123", "CherdakPlayer"));
        assertFalse(ChatService.hasMention("привет всем", "CherdakPlayer"));
    }

    @Test
    void chatMessageBodyHandlesColorsAndMentions() {
        ChatConfig cfg = new ChatConfig();

        Component plain = ChatService.buildMessageBody(
                "привет всем", false, cfg, true, NamedTextColor.WHITE, name -> false);
        assertEquals("привет всем", ChatService.plainText(plain));

        // Without the color permission the codes stay visible
        Component literal = ChatService.buildMessageBody(
                "&cпривет", false, cfg, true, NamedTextColor.WHITE, name -> false);
        assertEquals("&cпривет", ChatService.plainText(literal));

        // With the color permission the codes are applied
        Component colored = ChatService.buildMessageBody(
                "&cпривет", true, cfg, true, NamedTextColor.WHITE, name -> false);
        assertEquals("привет", ChatService.plainText(colored));
        assertFalse(colored.children().isEmpty());
        assertEquals(NamedTextColor.RED, colored.children().get(0).color());

        // Mentions are highlighted and keep the message text intact
        Component withMention = ChatService.buildMessageBody(
                "привет @Steve, как дела?", false, cfg, true, NamedTextColor.WHITE,
                name -> name.equalsIgnoreCase("Steve"));
        assertEquals("привет @Steve, как дела?", ChatService.plainText(withMention));

        // Disabled highlight keeps the mention as plain text
        Component withoutHighlight = ChatService.buildMessageBody(
                "привет @Steve", false, cfg, false, NamedTextColor.WHITE, name -> true);
        assertEquals("привет @Steve", ChatService.plainText(withoutHighlight));
    }

    @Test
    void storeKeepsIgnoreLists(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("test-ignore");
        RegistrationStore store = new RegistrationStore(tempDir, logger);
        store.recordPlayerSeen(UUID.randomUUID(), "CherdakPlayer");
        store.recordPlayerSeen(UUID.randomUUID(), "GrieferOne");

        assertFalse(store.isIgnoring("CherdakPlayer", "GrieferOne"));
        assertTrue(store.addIgnoredPlayer("CherdakPlayer", "grieferone"));
        assertTrue(store.isIgnoring("CherdakPlayer", "GrieferOne"));
        assertTrue(store.isIgnoring("cherdakplayer", "grieferone"));
        assertFalse(store.addIgnoredPlayer("CherdakPlayer", "GrieferOne"));
        assertEquals(Set.of("grieferone"), store.getIgnoredPlayers("CherdakPlayer"));
        assertEquals(Set.of("GrieferOne"), store.getIgnoredDisplayNames("CherdakPlayer"));
        assertFalse(store.isIgnoring("GrieferOne", "CherdakPlayer"));

        // The ignore list survives a reload from disk
        RegistrationStore reloaded = new RegistrationStore(tempDir, logger);
        assertTrue(reloaded.isIgnoring("CherdakPlayer", "GrieferOne"));
        assertEquals(Set.of("GrieferOne"), reloaded.getIgnoredDisplayNames("CherdakPlayer"));
        assertTrue(reloaded.removeIgnoredPlayer("CherdakPlayer", "GrieferOne"));
        assertFalse(reloaded.isIgnoring("CherdakPlayer", "GrieferOne"));
        assertFalse(reloaded.removeIgnoredPlayer("CherdakPlayer", "GrieferOne"));
        assertTrue(reloaded.getIgnoredPlayers("CherdakPlayer").isEmpty());
    }
}
