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
    private volatile int maxBankHours = 24;
    void reload() { maxBankHours = Math.clamp(plugin.getConfig().getInt("reward-boost.max-bank-hours", 24), 1, 24 * 365); }

    RewardBoostService(RegisterPlugin plugin, AccessManager access) {
        this.plugin = plugin;
        this.access = access;
        this.store = access.store();
        this.clock = new RewardBoostClock(store::consumeRewardBoost);
        reload();
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

    ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.BoostGrant grantOnce(UUID uuid,String name,int hours,String receipt) {
        if(!Bukkit.isPrimaryThread())return ChrdkRegisterApi.BoostGrant.FAILED;
        Player player=Bukkit.getPlayer(uuid);
        if(!authenticated(player) || !player.getName().equals(name))return ChrdkRegisterApi.BoostGrant.FAILED;
        clock.settle(name,System.nanoTime());
        return store.grantRewardBoostOnce(uuid,name,hours,maxBankHours,receipt);
    }

    void grant(String name, int hours, String source) {
        if (!AccessManager.isValidMinecraftName(name) || hours <= 0) return;
        // Само пополнение синхронно и потокобезопасно: аддон может сохранить claim сразу после API.
        // На основной поток откладывается только сообщение, а не заслуженная награда.
        clock.settle(name, System.nanoTime());
        long before = store.rewardBoostRemaining(name);
        long remaining = store.grantRewardBoost(name, hours, maxBankHours);
        long added = Math.max(0L, remaining - before);
        Runnable notify = () -> {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) plugin.messages().send(online, added > 0 ? "reward-boost.granted" : "reward-boost.full",
                    "minutes", (added + 59_999) / 60_000, "left", (remaining + 59_999) / 60_000);
        };
        if (Bukkit.isPrimaryThread()) notify.run();
        else Bukkit.getScheduler().runTask(plugin, notify);
        access.audit().log("REWARD_BOOST", "gameplay", name, "",
                AuditLog.details("source", source == null ? "reward" : source,
                        "addedMillis", added, "remainingMillis", remaining, "percent", RewardBoostMath.PERCENT));
    }

    long experience(String name, String channel, long base, long current) {
        if (!AccessManager.isValidMinecraftName(name) || !Bukkit.isPrimaryThread()) return current;
        Player player = Bukkit.getPlayerExact(name);
        if (!authenticated(player)) return current;
        clock.sample(player.getUniqueId(), name, System.nanoTime());
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
