package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/** Однократный переход старых конфигов на добровольную привязку, без изменения аккаунтов. */
final class OptionalTotpMigration {
    static final String MARKER = "migrations.optional-totp-1-4-211";
    static final String BACKUP = "config-before-optional-2fa-1.4.211.yml";

    static void apply(JavaPlugin plugin) {
        try {
            if (migrate(plugin.getDataFolder().toPath().resolve("config.yml"))) {
                plugin.reloadConfig();
                plugin.getLogger().info("2FA теперь добровольна; существующие привязки сохранены. Бэкап: " + BACKUP);
            }
        } catch (IOException | InvalidConfigurationException | SecurityException failure) {
            // Не печатаем YAML/значения конфига: исключение парсера может содержать секреты.
            // Не отключаем ядро авторизации при ошибке диска — сохраняем прежний режим.
            plugin.getLogger().severe("Не удалось сохранить переход на добровольную 2FA ("
                    + failure.getClass().getSimpleName() + "). Конфиг не заменён; проверьте доступ к файлам. "
                    + "Для ручного перехода: totp.required: false, auth.session-skips-2fa: false.");
        }
    }

    static boolean migrate(Path file) throws IOException, InvalidConfigurationException {
        byte[] original = Files.readAllBytes(file);
        YamlConfiguration live = new YamlConfiguration();
        live.options().parseComments(true);
        live.loadFromString(new String(original, StandardCharsets.UTF_8));
        if (live.getBoolean(MARKER, false)) return false;

        Path backup = file.resolveSibling(BACKUP);
        if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (!Files.isRegularFile(backup)) throw new IOException("Backup is not a regular file");
        live.set("totp.required", false);
        live.setComments("totp.required", java.util.List.of("2FA по желанию: /2fa setup → /2fa confirm <код>.",
                "Уже привязанные аккаунты по-прежнему подтверждают код; true — обязательная привязка для всех."));
        // Раньше required:true игнорировал этот ключ. Не включаем скрытый обход кода
        // после смены режима; безопасное значение также для старых optional-конфигов.
        live.set("auth.session-skips-2fa", false);
        live.set(MARKER, true);

        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "config-optional-2fa-", ".tmp");
        try {
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
                Files.setPosixFilePermissions(temp, permissions);
            } catch (UnsupportedOperationException ignored) { /* Файловая система без POSIX. */ }
            Files.writeString(temp, live.saveToString(), StandardCharsets.UTF_8);
            if (!Arrays.equals(original, Files.readAllBytes(file)))
                throw new IOException("Configuration changed during migration");
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    private OptionalTotpMigration() { }
}
