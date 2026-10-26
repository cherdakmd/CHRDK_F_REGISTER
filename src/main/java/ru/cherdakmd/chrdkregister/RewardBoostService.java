package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Отдельная игровая награда. Никаких passUntil, групп LP, продаж и PassGrantedEvent. */
final class RewardBoostService implements Listener {
    private final RegisterPlugin plugin;
    private final AccessManager access;
    private final RegistrationStore store;
    private final RewardBoostClock clock;
    private final Map<PlayerExpChangeEvent, Integer> bases = new WeakHashMap<>();
    private BukkitTask task;

    RewardBoostService(RegisterPlugin plugin, AccessManager access) {
        this.plugin = plugin;
        this.access = access;
        this.store = access.store();
        this.clock = new RewardBoostClock(store::consumeRewardBoost);
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void close() {
        if (task != null) task.cancel();
        clock.stop(System.nanoTime());
        // AccessManager.shutdown() сохраняет общий store после последнего списания.
    }

    private boolean authenticated(Player player) {
        return player != null && player.isOnline()
                && access.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED;
    }

    private void tick() {
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!authenticated(player)) {
                clock.pause(id, now);
                continue;
            }
            clock.sample(id, player.getName(), now);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        pause(event.getPlayer().getUniqueId());
    }

    void pause(UUID id) {
        clock.pause(id, System.nanoTime());
    }

    void resume(Player player) { clock.resume(player.getUniqueId(), player.getName(), System.nanoTime()); }

    @EventHandler
    public void onAuthentication(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) {
        resume(event.getPlayer());
    }

    void grant(String name, int hours, String source) {
        if (!AccessManager.isValidMinecraftName(name) || hours <= 0) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> grant(name, hours, source));
            return;
        }
        // Списываем уже проведённое онлайн-время ДО пополнения, не задним числом с новой награды.
        tick();
        long before = store.rewardBoostRemaining(name);
        int bankHours = plugin.getConfig().getInt("reward-boost.max-bank-hours", 24);
        long remaining = store.grantRewardBoost(name, hours, bankHours);
        long added = Math.max(0L, remaining - before);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) plugin.messages().send(online, added > 0 ? "reward-boost.granted" : "reward-boost.full",
                "minutes", (added + 59_999) / 60_000, "left", (remaining + 59_999) / 60_000);
        access.audit().log("REWARD_BOOST", "gameplay", name, "",
                AuditLog.details("source", source == null ? "reward" : source,
                        "addedMillis", added, "remainingMillis", remaining, "percent", RewardBoostMath.PERCENT));
    }

    long experience(String name, String channel, long base, long current) {
        if (!Bukkit.isPrimaryThread() || !authenticated(Bukkit.getPlayerExact(name))) return current;
        return store.boostExperience(name, channel, base, RewardBoostMath.limit(base, current));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureExperience(PlayerExpChangeEvent event) {
        bases.put(event, Math.max(0, event.getAmount()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void boostExperience(PlayerExpChangeEvent event) {
        Integer base = bases.remove(event);
        if (base == null || base <= 0) return;
        event.setAmount((int) Math.min(Integer.MAX_VALUE,
                experience(event.getPlayer().getName(), "vanilla", base, event.getAmount())));
    }

    ChrdkRegisterApi.ProfileSection profile(ChrdkRegisterApi.ProfileContext context) {
        long left = store.rewardBoostRemaining(context.playerName());
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("reward-boost.title"), Material.EXPERIENCE_BOTTLE,
                List.of(plugin.messages().get("reward-boost.profile", "left", (left + 59_999) / 60_000)));
    }
}
