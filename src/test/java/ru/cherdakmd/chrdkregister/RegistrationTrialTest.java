package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationTrialTest {
    @TempDir Path dir;
    private static final long NOW = 1_800_000_000_000L;
    private static final long FOURTEEN_DAYS = 14 * 86_400_000L;
    private static final UUID UUID1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private RegistrationStore store() { return new RegistrationStore(dir, Logger.getAnonymousLogger()); }
    @Test void firstJoinDoesNotStartClockOrUnlockPlay() {
        var s=store(); s.recordPlayerSeen(UUID1,"Newbie");
        assertTrue(s.isTrialEligible("Newbie",UUID1));assertFalse(s.hasActivePass("Newbie",NOW));
        assertFalse(s.isRegistered("Newbie"));
        assertTrue(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER,"register"));
        assertFalse(PlayerRestrictionListener.isCommandAllowed(AccessManager.PlayerStage.NEEDS_REGISTER,"ah"));
    }
    @Test void legacyTrialArgumentsNeverGrantPaidRights() {
        for (int days : new int[] {0, 3, 14, 365}) {
            String name = "Newbie" + days;
            UUID id = UUID.randomUUID();
            var s = store();
            assertTrue(s.registerPassword(name, id, "StrongPassword123", "ip", NOW, days));
            var account = store().getAccount(name).orElseThrow();
            assertTrue(account.isRegistered());
            assertTrue(account.trialConsumed);
            assertEquals(0, account.passUntil);
            assertEquals(0, account.trialUntil);
            assertEquals(0, account.trialGrantedAt);
            assertEquals(0, account.effectiveRank(NOW));
            assertFalse(account.hasRank(NOW));
        }
    }
    @Test void repeatRegistrationJoinResetAndRenameDoNotRenew() {
        var s=store();assertTrue(s.registerPassword("Newbie",UUID1,"StrongPassword123","ip",NOW,14));
        assertFalse(s.registerPassword("NEWBIE",UUID1,"OtherPassword123","ip",NOW+1000,14));
        s.recordPlayerSeen(UUID1,"NEWBIE");assertEquals(0,s.getAccount("Newbie").orElseThrow().passUntil);
        assertTrue(s.resetPassword("Newbie"));assertFalse(s.isTrialEligible("newbie",UUID1));
        assertTrue(s.registerPassword("Newbie",UUID1,"OtherPassword123","ip",NOW+2000,14));
        assertEquals(0,s.getAccount("Newbie").orElseThrow().passUntil);
        assertFalse(store().isTrialEligible("ChangedName",UUID1));
    }
    @Test void revokedTrialCannotBeClaimedAgain() {
        var s=store();s.registerPassword("Newbie",UUID1,"StrongPassword123","ip",NOW,14);
        assertFalse(s.revokePass("Newbie"));s.resetPassword("Newbie");
        assertFalse(s.isTrialEligible("Newbie",UUID1));assertFalse(store().isTrialEligible("Other",UUID1));
    }
    @Test void paidPassIsNeitherShortenedNorExtendedByRegistration() {
        var s=store();s.recordPlayerSeen(UUID1,"Paid");s.grantPassManual("Paid",NOW,30*86_400_000L);
        assertFalse(s.isTrialEligible("OtherName",UUID1));
        assertTrue(s.registerPassword("Paid",UUID1,"StrongPassword123","ip",NOW,14));
        var a=s.getAccount("Paid").orElseThrow();assertEquals(NOW+30*86_400_000L,a.passUntil);assertEquals(0,a.trialGrantedAt);
    }
    @Test void failedAndDisabledRegistrationDoesNotGiveTrial() {
        var s=store();assertFalse(s.registerPassword("Newbie",UUID1,"","ip",NOW,14));
        assertTrue(s.isTrialEligible("Newbie",UUID1));
        assertTrue(s.registerPassword("Newbie",UUID1,"StrongPassword123","ip",NOW,0));
        assertFalse(s.hasActivePass("Newbie",NOW));assertFalse(s.isTrialEligible("ChangedName",UUID1));
    }
    @Test void paidExtensionStartsAfterTrial() {
        var s=store();s.registerPassword("Newbie",UUID1,"StrongPassword123","ip",NOW,14);
        s.grantPassManual("Newbie",NOW+1000,30*86_400_000L);
        assertEquals(NOW+1000+30*86_400_000L,s.getAccount("Newbie").orElseThrow().passUntil);
    }
    @Test void migrationExcludesLegacyRegisteredAccountsWithoutChangingPasses() throws Exception {
        var s=store();s.recordPlayerSeen(UUID1,"Legacy");s.registerPassword("Legacy",UUID1,"StrongPassword123","ip",NOW);
        var json=JsonParser.parseString(Files.readString(dir.resolve("data.json"))).getAsJsonObject();
        json.addProperty("version",3);json.remove("trialUsedUuids");
        var a=json.getAsJsonObject("accounts").getAsJsonObject("legacy");
        a.remove("trialConsumed");a.remove("trialGrantedAt");a.remove("trialUntil");
        Files.writeString(dir.resolve("data.json"),json.toString());Files.deleteIfExists(dir.resolve("data.json.sha256"));
        var loaded=store();assertTrue(loaded.isRegistered("Legacy"));assertFalse(loaded.isTrialEligible("OtherName",UUID1));
        loaded.resetPassword("Legacy");assertFalse(loaded.isTrialEligible("Legacy",UUID1));
        assertEquals(0,loaded.getAccount("Legacy").orElseThrow().passUntil);
    }
    @Test void configDefaultsApplyToExistingConfigAndCanBeDisabled() throws Exception {
        var yaml=new YamlConfiguration();var c=PluginConfig.fromBukkitConfig(yaml);
        assertFalse(c.trialEnabled());assertEquals(14,c.trialDays());
        yaml.loadFromString("pass:\n  trial:\n    enabled: false\n    days: -1\n");
        c=PluginConfig.fromBukkitConfig(yaml);assertFalse(c.trialEnabled());assertEquals(1,c.trialDays());
    }
}
