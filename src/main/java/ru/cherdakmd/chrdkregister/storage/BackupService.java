package ru.cherdakmd.chrdkregister.storage;

import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.RegisterPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Bukkit-обвязка: один архив за раз, вне основного потока, без записи при выключении. */
public final class BackupService {
    private final RegisterPlugin plugin;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile BukkitTask task;
    private volatile boolean stopping;
    private volatile BackupArchive archive;

    public BackupService(RegisterPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public final void reload() {
        var config = plugin.getConfig();
        archive = new BackupArchive(plugin.getDataFolder().toPath().getParent(),
                plugin.getDataFolder().toPath().resolve("backups").resolve("snapshots"),
                ZoneId.of(config.getString("general.timezone", "Europe/Moscow")));
        if (task != null) task.cancel();
        task = null;
        if (!config.getBoolean("storage.snapshots.enabled", true)) return;
        long period = Math.clamp(config.getLong("storage.snapshots.interval-minutes", 60L), 5L, 1440L) * 1200L;
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                () -> create("auto", null), period, period);
    }

    public void start() {
        if (plugin.getConfig().getBoolean("storage.snapshots.on-start", true)) {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin,
                    () -> create("start", null), 200L);
        }
    }

    public void stop() {
        stopping = true;
        if (task != null) task.cancel();
        task = null;
    }

    public BackupArchive archive() { return archive; }

    /** Создание асинхронное; sender получает только имя файла и результат проверки. */
    public boolean create(CommandSender sender) {
        if (stopping || !busy.compareAndSet(false, true)) return false;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> createLocked("manual", result -> {
                    if (result instanceof Path path) {
                        sender.sendMessage(plugin.messages().get("backup.snapshot-created", "path", path.getFileName().toString()));
                    } else {
                        sender.sendMessage(plugin.messages().get("backup.snapshot-failed", "error", String.valueOf(result)));
                    }
                }));
        return true;
    }

    private void create(String reason, Consumer<Object> callback) {
        if (stopping || !busy.compareAndSet(false, true)) return;
        createLocked(reason, callback);
    }

    private void createLocked(String reason, Consumer<Object> callback) {
        try {
            plugin.flushForBackup();
            var config = plugin.getConfig();
            BackupArchive.Retention retention = new BackupArchive.Retention(
                    config.getInt("storage.snapshots.keep-hourly", 6),
                    config.getInt("storage.snapshots.keep-daily", 14),
                    config.getInt("storage.snapshots.keep-weekly", 8));
            Path created = archive.create(reason, Instant.now(), retention);
            plugin.getLogger().info("[backup] Проверен снимок " + created.getFileName());
            if (callback != null) complete(() -> callback.accept(created));
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "[backup] Снимок НЕ создан: " + error.getMessage(), error);
            if (callback != null) complete(() -> callback.accept(error.getMessage()));
        } finally {
            busy.set(false);
        }
    }

    public void verify(CommandSender sender, String fileName) {
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(plugin.messages().get("backup.busy"));
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BackupArchive.Verification result = archive.verify(archive.resolve(fileName));
                complete(() -> sender.sendMessage(plugin.messages().get("backup.snapshot-verified",
                        "path", fileName, "files", result.files(), "bytes", result.bytes())));
            } catch (Exception error) {
                complete(() -> sender.sendMessage(plugin.messages().get("backup.snapshot-invalid",
                        "error", error.getMessage())));
            } finally {
                busy.set(false);
            }
        });
    }

    public List<Path> list() {
        try {
            return archive.list();
        } catch (IOException error) {
            plugin.getLogger().warning("[backup] Не удалось прочитать список снимков: " + error.getMessage());
            return List.of();
        }
    }

    private void complete(Runnable callback) {
        if (stopping || !plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, callback);
    }
}
