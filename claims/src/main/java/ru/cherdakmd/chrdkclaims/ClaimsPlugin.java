package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.events.PassExpiredEvent;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHRDK_Claims — приваты чанков за алмазные предметы: покупка территории,
 * ежесуточная рента из казны, налог на сделки, продажа игрокам, полная защита.
 */
public final class ClaimsPlugin extends JavaPlugin implements Listener {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60; // минута
    private static final long UPKEEP_INTERVAL_TICKS = 20L * 60 * 60; // час

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile ClaimsConfig config;
    private ClaimStore store;
    private ClaimsService service;
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private BukkitTask saveTask;
    private BukkitTask upkeepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new ClaimStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.service = new ClaimsService(this);
        reloadClaimsConfig();
        if (coreApi == null) {
            getLogger().warning("[CHRDK_Claims] API CHRDK_Core не найден (нужно ядро 1.4.6+): "
                    + "проверка авторизации и бонус лимита за проходку недоступны.");
        }

        Bukkit.getPluginManager().registerEvents(new ClaimProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand claim = getCommand("claim");
        PluginCommand admin = getCommand("claimadmin");
        if (claim != null) {
            ClaimsCommandHandler handler = new ClaimsCommandHandler(this);
            claim.setExecutor(handler);
            claim.setTabCompleter(handler);
        }
        if (admin != null) {
            ClaimsAdminCommandHandler handler = new ClaimsAdminCommandHandler(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }

        // рента добегает простой сервера: прогон при включении и дальше раз в час
        upkeepTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> service.processUpkeep(System.currentTimeMillis()),
                100L, UPKEEP_INTERVAL_TICKS);
        saveTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> store.saveAsync(this), SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
        getLogger().info("CHRDK_Claims включён: приватов " + store.all().size()
                + ", цена чанка " + config.chunkPrice + ", рента " + config.upkeepPerDay + "/сут.");
    }

    @Override
    public void onDisable() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (store != null) {
            store.markDirty();
            store.save();
        }
        getLogger().info("CHRDK_Claims выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadClaimsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = ClaimsConfig.parse(getConfig(), getLogger());
    }

    /** После авторизации доставляем отложенные вести (долги, утраты, продажи) и напоминаем о ренте. */
    @EventHandler
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        List<String> notices = store.pollNotices(player.getName());
        if (!notices.isEmpty()) {
            messages().send(player, "notices-join", "count", String.valueOf(notices.size()));
            for (String notice : notices) {
                messages().send(player, "notice-line", "text", notice);
            }
        }
        service.sendRentReminder(player);
    }

    /** После истечения проходки лимит уменьшается: предупреждаем, если чанков стало больше нормы. */
    @EventHandler
    public void onPassExpired(PassExpiredEvent event) {
        Player player = Bukkit.getPlayerExact(event.getPlayerName());
        if (player == null) {
            return;
        }
        int claims = store.count(player.getName());
        int limit = ClaimMath.effectiveLimit(config.baseChunks, config.passBonusChunks, false);
        if (claims > limit) {
            messages().send(player, "limit-shrunk",
                    "claims", String.valueOf(claims), "limit", String.valueOf(limit));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bypass.remove(event.getPlayer().getUniqueId());
    }

    public boolean isBypassing(Player player) {
        return player.hasPermission("chrdkclaims.admin") && bypass.contains(player.getUniqueId());
    }

    /** @return включён ли обход после переключения. */
    public boolean toggleBypass(Player player) {
        if (!bypass.add(player.getUniqueId())) {
            bypass.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public ClaimsConfig config() {
        return config;
    }

    public ClaimStore store() {
        return store;
    }

    public ClaimsService service() {
        return service;
    }
}
