package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.events.PassExpiredEvent;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.util.List;
import java.util.Map;
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
    /** Период перерисовки границ: два импульса в секунду. */
    private static final long BORDER_PERIOD_TICKS = 10L;

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile ClaimsConfig config;
    private ClaimStore store;
    private ClaimsService service;
    private ClaimAudit audit;
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    /** Активные подсветки границ: игрок → задача (чтобы не плодить таймеры). */
    private final Map<UUID, BukkitTask> borderTasks = new ConcurrentHashMap<>();
    /** Последний чанк игрока «мир:x:z» — чтобы строка действия срабатывала на границе. */
    private final Map<UUID, String> lastChunk = new ConcurrentHashMap<>();
    private BukkitTask saveTask;
    private BukkitTask upkeepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new ClaimStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.audit = new ClaimAudit(getDataFolder().toPath(), getLogger());
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
        borderTasks.values().forEach(BukkitTask::cancel);
        borderTasks.clear();
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
        UUID id = event.getPlayer().getUniqueId();
        bypass.remove(id);
        cancelBorders(id);
        lastChunk.remove(id);
    }

    /**
     * Строка действия при входе на чужой приват: владелец и цена, если чанк продаётся.
     * Обработчик вызывается на каждое движение, поэтому сначала сравниваются чанки —
     * карта «последний чанк» трогается только на реальной границе.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (!to.getWorld().equals(from.getWorld())
                || (from.getBlockX() >> 4) != (to.getBlockX() >> 4)
                || (from.getBlockZ() >> 4) != (to.getBlockZ() >> 4)) {
            String key = to.getWorld().getName() + ':' + (to.getBlockX() >> 4)
                    + ':' + (to.getBlockZ() >> 4);
            UUID id = event.getPlayer().getUniqueId();
            boolean same = key.equals(lastChunk.put(id, key));
            if (same || !config.enterNotice) {
                return;
            }
            Player player = event.getPlayer();
            if (!service.isAllowed(player)) {
                return;
            }
            Claim claim = store.claimAt(to.getWorld().getName(),
                    to.getBlockX() >> 4, to.getBlockZ() >> 4);
            if (claim == null || claim.isOwner(player.getName())) {
                return;
            }
            if (claim.salePrice > 0) {
                player.sendActionBar(messages().get("enter-for-sale",
                        "owner", claim.owner, "price", String.valueOf(claim.salePrice)));
            } else {
                player.sendActionBar(messages().get("enter-foreign", "owner", claim.owner));
            }
        }
    }

    /**
     * Включает/выключает подсветку границ текущего чанка.
     *
     * @return {@code true}, если подсветка включена, {@code false} — выключена
     */
    public boolean toggleBorders(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask active = borderTasks.remove(id);
        if (active != null) {
            active.cancel();
            return false;
        }
        int pulses = Math.max(1, config.borderSeconds * 2);
        int[] left = {pulses};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || left[0]-- <= 0) {
                cancelBorders(id);
                return;
            }
            drawBorder(player);
        }, 0L, BORDER_PERIOD_TICKS);
        borderTasks.put(id, task);
        return true;
    }

    private void cancelBorders(UUID id) {
        BukkitTask task = borderTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** Рисует контур чанка на двух уровнях — видно и на неровном рельефе. */
    private void drawBorder(Player player) {
        Location base = player.getLocation();
        World world = base.getWorld();
        int minX = (base.getBlockX() >> 4) << 4;
        int minZ = (base.getBlockZ() >> 4) << 4;
        int y = base.getBlockY();
        for (int offset = 0; offset <= 16; offset += 2) {
            player.spawnParticle(Particle.END_ROD,
                    new Location(world, minX + offset, y + 0.2, minZ), 1);
            player.spawnParticle(Particle.END_ROD,
                    new Location(world, minX + offset, y + 0.2, minZ + 16), 1);
            player.spawnParticle(Particle.END_ROD,
                    new Location(world, minX, y + 0.2, minZ + offset), 1);
            player.spawnParticle(Particle.END_ROD,
                    new Location(world, minX + 16, y + 0.2, minZ + offset), 1);
            if (offset % 4 == 0) {
                player.spawnParticle(Particle.END_ROD,
                        new Location(world, minX + offset, y + 2.2, minZ), 1);
                player.spawnParticle(Particle.END_ROD,
                        new Location(world, minX + offset, y + 2.2, minZ + 16), 1);
                player.spawnParticle(Particle.END_ROD,
                        new Location(world, minX, y + 2.2, minZ + offset), 1);
                player.spawnParticle(Particle.END_ROD,
                        new Location(world, minX + 16, y + 2.2, minZ + offset), 1);
            }
        }
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

    /** Журнал операций (может быть {@code null} до включения плагина). */
    public ClaimAudit auditLog() {
        return audit;
    }

    /** Пишет событие в журнал, если он включён в конфиге. */
    public void audit(String action, String details) {
        if (audit != null && config != null && config.auditLog) {
            audit.write(action, details);
        }
    }

    public ClaimsService service() {
        return service;
    }
}
