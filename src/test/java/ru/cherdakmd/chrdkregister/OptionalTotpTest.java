package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class OptionalTotpTest {
    @TempDir Path dir;
    private Path config(String text) throws Exception {
        return Files.writeString(dir.resolve("config.yml"), text);
    }
    private YamlConfiguration read(Path file) throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(file.toFile()); return yaml;
    }
    @Test void mandatoryConfigMigratesWithExactBackupAndKeepsCustomSettings() throws Exception {
        String old = "# keep\ntotp:\n  enabled: true\n  required: true\n  issuer: MyServer\nauth:\n  session-skips-2fa: true\ncustom:\n  value: 17\n";
        Path file = config(old);
        assertTrue(OptionalTotpMigration.migrate(file));
        assertEquals(old, Files.readString(dir.resolve(OptionalTotpMigration.BACKUP)));
        var yaml = read(file);
        assertTrue(yaml.getBoolean("totp.enabled"));
        assertFalse(yaml.getBoolean("totp.required"));
        assertFalse(yaml.getBoolean("auth.session-skips-2fa"));
        assertTrue(yaml.getBoolean(OptionalTotpMigration.MARKER));
        assertEquals("MyServer", yaml.getString("totp.issuer"));
        assertEquals(17, yaml.getInt("custom.value"));
    }
    @Test void absentLegacyRequirementAndSessionBypassUseSafeNewPolicy() throws Exception {
        Path file = config("auth:\n  session-skips-2fa: true\n");
        assertTrue(OptionalTotpMigration.migrate(file));
        PluginConfig parsed = PluginConfig.fromBukkitConfig(read(file));
        assertFalse(parsed.totpRequired()); assertTrue(parsed.totpEnabled());
        assertFalse(parsed.sessionSkips2fa());
    }
    @Test void alreadyOptionalConfigAlsoKeepsEnrolledAccountsProtected() throws Exception {
        Path file = config("totp:\n  required: false\nauth:\n  session-skips-2fa: true\n");
        assertTrue(OptionalTotpMigration.migrate(file));
        assertFalse(read(file).getBoolean("auth.session-skips-2fa"));
    }
    @Test void explicitlyDisabledTotpIsNotReenabled() throws Exception {
        Path file = config("totp:\n  enabled: false\n  required: true\n");
        assertTrue(OptionalTotpMigration.migrate(file));
        assertFalse(read(file).getBoolean("totp.enabled"));
    }
    @Test void secondRunPreservesAdministratorsExplicitChoiceAndOriginalBackup() throws Exception {
        Path file = config("totp:\n  required: true\n");
        String original = Files.readString(file);
        OptionalTotpMigration.migrate(file);
        var yaml = read(file); yaml.set("totp.required", true); yaml.set("auth.session-skips-2fa", true);
        yaml.save(file.toFile());
        byte[] edited = Files.readAllBytes(file);
        assertFalse(OptionalTotpMigration.migrate(file));
        assertArrayEquals(edited, Files.readAllBytes(file));
        assertEquals(original, Files.readString(dir.resolve(OptionalTotpMigration.BACKUP)));
    }
    @Test void freshResourceIsAlreadyOptionalWithoutMigrationOrBackup() throws Exception {
        String defaults;
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream); defaults = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Path file = config(defaults);
        assertFalse(OptionalTotpMigration.migrate(file));
        assertFalse(Files.exists(dir.resolve(OptionalTotpMigration.BACKUP)));
        assertFalse(PluginConfig.fromBukkitConfig(read(file)).totpRequired());
    }
    @Test void invalidYamlIsNotReplacedOrBackedUp() throws Exception {
        Path file = config("totp: [broken\n"); byte[] original = Files.readAllBytes(file);
        assertThrows(InvalidConfigurationException.class, () -> OptionalTotpMigration.migrate(file));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve(OptionalTotpMigration.BACKUP)));
    }
    @Test void backupFailureLeavesOriginalConfigurationIntact() throws Exception {
        Path file = config("totp:\n  required: true\n"); byte[] original = Files.readAllBytes(file);
        Files.createDirectory(dir.resolve(OptionalTotpMigration.BACKUP));
        assertThrows(java.io.IOException.class, () -> OptionalTotpMigration.migrate(file));
        assertArrayEquals(original, Files.readAllBytes(file));
    }
    @Test void migrationPreservesRestrictivePosixPermissions() throws Exception {
        Path file = config("totp:\n  required: true\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"));
        var permissions = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(file, permissions);
        OptionalTotpMigration.migrate(file);
        assertEquals(permissions, Files.getPosixFilePermissions(file));
        assertEquals(permissions, Files.getPosixFilePermissions(dir.resolve(OptionalTotpMigration.BACKUP)));
        try (var files = Files.list(dir)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void accountPasswordSecretAndReplayProtectionSurviveConfigurationMigration() throws Exception {
        var store = new RegistrationStore(dir, Logger.getAnonymousLogger());
        long now = 1_800_000_000_000L;
        assertTrue(store.registerPassword("Protected", UUID.randomUUID(), "Secret123!", "192.0.2.1", now, 0));
        String secret = TotpService.generateSecret(); long step = TotpService.getTimeStep(now);
        assertTrue(store.enableTotp("Protected", secret, step - 1, now));
        store.flushBlocking(); byte[] data = Files.readAllBytes(dir.resolve("data.json"));
        OptionalTotpMigration.migrate(config("totp:\n  enabled: true\n  required: true\n"));
        assertArrayEquals(data, Files.readAllBytes(dir.resolve("data.json")));
        assertTrue(store.verifyPassword("Protected", "Secret123!"));
        assertTrue(store.isTotpEnabled("Protected"));
        String code = TotpService.generateCode(secret, now);
        assertFalse(store.verifyTotp("Protected", "bad", now, 1));
        assertTrue(store.verifyTotp("Protected", code, now, 1));
        assertFalse(store.verifyTotp("Protected", code, now, 1), "replay still rejected");
    }
    @Test void enrollmentAndIpSessionsRespectAllModeCombinations() {
        for (boolean enabled : new boolean[]{false, true}) for (boolean required : new boolean[]{false, true})
            for (boolean enrolled : new boolean[]{false, true}) for (boolean skips : new boolean[]{false, true}) {
                assertEquals(enabled && required && !enrolled, TotpAccessPolicy.enrollment(enabled, required, enrolled));
                assertEquals(enabled && (enrolled ? required || !skips : required),
                        TotpAccessPolicy.sessionNeedsSecondStep(enabled, required, enrolled, skips));
            }
        assertTrue(TotpAccessPolicy.sessionNeedsSecondStep(true, false, true, false));
    }
    @Test void reloadCannotReleaseEnrolledOrUnverifiedAccounts() {
        for (boolean oldRequired : new boolean[]{false, true}) for (boolean required : new boolean[]{false, true})
            for (boolean enrolled : new boolean[]{false, true}) for (boolean password : new boolean[]{false, true})
                for (boolean waiting : new boolean[]{false, true})
                    assertEquals(oldRequired && !required && !enrolled && password && waiting,
                            TotpAccessPolicy.releaseEnrollment(oldRequired, required, enrolled, password, waiting));
    }
    @Test void reloadReleaseUsesNormalAuthenticationCleanupSessionAndAntiMultiAlt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java"));
        String method = source.substring(source.indexOf("private void releaseOptionalEnrollment"), source.indexOf("public synchronized void shutdown"));
        assertTrue(method.contains("TotpAccessPolicy.releaseEnrollment"));
        assertTrue(method.contains("store.isTotpEnabled(player.getName())"));
        assertTrue(method.contains("passwordVerifiedPlayers.contains(uuid)"));
        assertTrue(method.contains("enforceAntiMultiAlt("));
        assertTrue(method.contains("pendingTotpSecrets.remove(uuid)"));
        assertTrue(method.contains("store.touchSession("));
        assertTrue(method.contains("completeAuthentication(player, \"two-factor.optional-login-complete\")"));
        assertFalse(method.contains("disableTotp("));
    }
}
