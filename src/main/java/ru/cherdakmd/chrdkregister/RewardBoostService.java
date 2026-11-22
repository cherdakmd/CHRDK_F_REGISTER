package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.UUID;

/** Совместимость старых выдач: часы больше не усиливают опыт и автоматически переводятся в /bal. */
final class RewardBoostService implements Listener {
    private final RegisterPlugin plugin;
    private final AccessManager access;
    private final RegistrationStore store;
    private BukkitTask task;
    private static final int LEGACY_BANK_HOURS = 0;

    RewardBoostService(RegisterPlugin plugin, AccessManager access) {
        this.plugin = plugin;
        this.access = access;
        this.store = access.store();
    }

    void reload() { }

    void start() {
        var migration = new BoostMigrationRunner(store, plugin.bank().store(), plugin.getLogger());
        // Main thread: порядок относительно выдач старых аддонов, сохранения и shutdown.
        // Один финансовый переход в секунду, до 16 просмотренных аккаунтов.
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (plugin.bank().enabled()) migration.tick();
        }, 20L, 20L);
    }

    void close() { if (task != null) task.cancel(); }
    void pause(UUID id) { }
    void resume(Player player) { }

    private boolean authenticated(Player player) {
        return player != null && player.isOnline()
                && access.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED;
    }

    ChrdkRegisterApi.BoostGrant grantOnce(UUID uuid, String name, int hours, String receipt) {
        if (!Bukkit.isPrimaryThread()) return ChrdkRegisterApi.BoostGrant.FAILED;
        Player player = Bukkit.getPlayer(uuid);
        if (!authenticated(player) || !player.getName().equals(name)) return ChrdkRegisterApi.BoostGrant.FAILED;
        return store.grantRewardBoostOnce(uuid, name, hours, LEGACY_BANK_HOURS, receipt);
    }

    /** Старые аддоны могут завершить ранее принятые выдачи; записанные часы попадут в очередь компенсации. */
    void grant(String name, int hours, String source) {
        if (!AccessManager.isValidMinecraftName(name) || hours <= 0) return;
        long before = store.rewardBoostRemaining(name);
        long remaining = store.grantRewardBoost(name, hours, LEGACY_BANK_HOURS);
        long added = Math.max(0L, remaining - before);
        Runnable notify = () -> {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null && added > 0) plugin.messages().send(online, "reward-boost.conversion-queued");
        };
        if (Bukkit.isPrimaryThread()) notify.run();
        else Bukkit.getScheduler().runTask(plugin, notify);
        access.audit().log("REWARD_BOOST_CONVERSION", "gameplay", name, "",
                AuditLog.details("source", source == null ? "reward" : source,
                        "addedMillis", added, "remainingMillis", remaining));
    }

    static long minutes(long millis) { return millis / 60_000 + (millis % 60_000 > 0 ? 1 : 0); }

    /** Сохранённые часы являются обязательством компенсации, а не действующим эффектом. */
    long experience(String name, String channel, long base, long current) { return current; }
}
