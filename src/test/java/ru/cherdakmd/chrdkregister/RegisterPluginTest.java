package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

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
    void storeHandlesManualAndDonatePayPassesAndSessions(@TempDir Path tempDir) {
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

        // Reload from disk and verify persistence
        RegistrationStore reloaded = new RegistrationStore(tempDir, logger);
        assertTrue(reloaded.hasActivePass("CherdakPlayer", now));
        assertTrue(reloaded.verifyPassword("CherdakPlayer", "Secret123"));
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

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "pass"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "register"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "login"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NO_PASS, "spawn"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "register"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "reg"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER, "login"));

        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "login"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "l"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_LOGIN, "register"));
    }
}
