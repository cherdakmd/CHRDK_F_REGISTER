package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
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
    private ClaimMenuGui menu;
    private ClaimTeleportService teleports;
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    /** Активные подсветки границ: игрок → задача (чтобы не плодить таймеры). */
    private final Map<UUID, BukkitTask> borderTasks = new ConcurrentHashMap<>();
    /** Последний приват игрока и кулдаун уведомлений о границах. */
    private final ClaimNoticeTracker claimNotices = new ClaimNoticeTracker();
    /** Персональный выключатель уведомлений (/claim notices) хранится в данных игрока. */
    private NamespacedKey noticesKey;
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
        if (coreApi != null) {
            try {
                coreApi.registerProfile("claims", new ClaimsProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_Claims] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }
        // Ядро 1.4.106+: случайный спавн спрашивает, занят ли чанк и есть ли у игрока приват.
        // Через отдельный мост: со старым ядром модуль продолжает работать без этой связи.
        try {
            ClaimsSpawnBridge.register(this);
        } catch (Throwable throwable) {
            getLogger().warning("[CHRDK_Claims] мост случайного спавна недоступен "
                    + "(нужно ядро 1.4.106+): " + throwable);
        }
        if (coreApi == null) {
            getLogger().warning("[CHRDK_Claims] API CHRDK_Core не найден (нужно ядро 1.4.6+): "
                    + "проверка авторизации и бонус лимита за проходку недоступны.");
        }

        this.noticesKey = new NamespacedKey(this, "notices");
        Bukkit.getPluginManager().registerEvents(new ClaimProtectionListener(this), this);
        // Меню приватов и телепорт к ним: оба слушателя живут до выключения плагина.
        this.menu = new ClaimMenuGui(this);
        this.teleports = new ClaimTeleportService(this);
        Bukkit.getPluginManager().registerEvents(menu, this);
        Bukkit.getPluginManager().registerEvents(teleports, this);
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
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("claims");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        if (teleports != null) {
            teleports.shutdown();
        }
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
        // Старый config.yml получает новые стоковые значения уведомлений один раз.
        // Файл правится построчно: комментарии и порядок ключей сохраняются.
        if (ClaimNoticeMigration.apply(getDataFolder().toPath().resolve("config.yml"),
                getDataFolder().toPath(), getLogger())) {
            reloadConfig();
        }
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = ClaimsConfig.parse(getConfig(), getLogger());
    }

    /** После авторизации доставляем отложенные вести (долги, утраты, продажи) и напоминаем о ренте. */
    @EventHandler
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        // Стоящий на привате игрок должен увидеть, куда попал, не делая шага.
        refreshNoticeLater(player);
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

    /**
     * Вход на сервер: через секунду сообщаем, на чьей земле игрок оказался.
     * Задержка нужна и для серверов без обязательной авторизации, и чтобы
     * ядро успело перенести новичка на случайную точку.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshNoticeLater(event.getPlayer(), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        bypass.remove(id);
        cancelBorders(id);
        claimNotices.clear(id);
    }

    /**
     * Телепорт — тоже переход границы: после прыжка сообщаем и о выходе из
     * прежнего привата, и о входе в новый. Точка читается следующим тиком,
     * когда сервер уже перенёс игрока.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        refreshNoticeLater(event.getPlayer());
    }

    /**
     * Смерть сама точку не меняет, но запоминать приват смерти бесполезно:
     * возрождение всё равно произойдёт в другом месте, и «выход» из привата,
     * где игрок погиб, сообщится уже там.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getEntity() != null) {
            claimNotices.forget(event.getEntity().getUniqueId());
        }
    }

    /** Возрождение: игрок появился в новой точке — сообщаем, где он оказался. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        refreshNoticeLater(event.getPlayer());
    }

    /**
     * Уведомления на границе приватов: вход и выход, свой чанк, участник или
     * посторонний. Обработчик вызывается на каждое движение, поэтому сначала
     * сравниваются чанки — память трогается только на реальной границе.
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
            updateClaimNotice(event.getPlayer(), to);
        }
    }

    /**
     * Пересчитывает уведомление следующим тиком. Нужно там, где позиция игрока
     * ещё не финальная в момент события: телепорт, возрождение, вход на сервер.
     * Два тика — чтобы ядро успело перенести игрока после авторизации.
     */
    private void refreshNoticeLater(Player player) {
        refreshNoticeLater(player, 2L);
    }

    /** То же с явной задержкой: вход на сервер ждёт секунду, события — два тика. */
    private void refreshNoticeLater(Player player, long delayTicks) {
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                updateClaimNotice(player, player.getLocation());
            }
        }, delayTicks);
    }

    /** Пересчитывает приват в новом чанке и отправляет уведомления о переходе. */
    private void updateClaimNotice(Player player, Location to) {
        if (!config.enterNotice && !config.leaveNotice) {
            return;
        }
        if (!service.isAllowed(player)) {
            return;
        }
        String world = to.getWorld().getName();
        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;
        Claim claim = config.worldEnabled(world) ? store.claimAt(world, chunkX, chunkZ) : null;
        ClaimNotice.Spot spot = claim == null ? null : new ClaimNotice.Spot(
                world, chunkX, chunkZ, claim.owner, claim.salePrice,
                claim.roleOf(player.getName()), claim.paidUntil, claim.greeting);
        long cooldown = config.noticeCooldown * 1000L;
        for (ClaimNoticeTracker.Notice notice : claimNotices.update(
                player.getUniqueId(), System.currentTimeMillis(), spot, cooldown)) {
            sendClaimNotice(player, notice);
        }
    }

    /** Отправляет одно уведомление в выбранный канал (actionbar/chat/both). */
    private void sendClaimNotice(Player player, ClaimNoticeTracker.Notice notice) {
        boolean entering = notice.entering();
        if (entering ? !config.enterNotice : !config.leaveNotice) {
            return;
        }
        ClaimNotice.Spot spot = notice.spot();
        ClaimNotice.Audience audience = spot.audience(player.getName());
        if (audience == ClaimNotice.Audience.OWNER && !config.noticeOwn) {
            return;
        }
        if (audience == ClaimNotice.Audience.MEMBER && !config.noticeMembers) {
            return;
        }
        if (!noticesEnabled(player)) {
            return;
        }
        String key = ClaimNotice.key(
                entering ? ClaimNotice.Kind.ENTER : ClaimNotice.Kind.LEAVE,
                audience, spot.forSale());
        String paid = spot.paidUntil() > 0 ? config.formatDate(spot.paidUntil()) : "";
        Object[] placeholders = {
                "owner", spot.owner(),
                "x", String.valueOf(spot.x()),
                "z", String.valueOf(spot.z()),
                "world", spot.world(),
                "price", String.valueOf(spot.salePrice()),
                "role", spot.role() == null ? "" : service.roleName(spot.role()),
                "paid", paid,
                // Для своего привата дописываем дату оплаты, только если она известна.
                "paid-note", paid.isEmpty() ? "" : " · оплачен до " + paid
        };
        if (config.noticeMode.chat()) {
            messages().send(player, key, placeholders);
        }
        if (config.noticeMode.actionBar()) {
            player.sendActionBar(messages().get(key, placeholders));
        }
        if (entering && audience != ClaimNotice.Audience.OWNER
                && config.noticeMode.chat() && !spot.greeting().isBlank()) {
            messages().send(player, "enter-greeting", "text", spot.greeting());
        }
    }

    /** Включены ли у игрока уведомления о границах (по умолчанию — да). */
    public boolean noticesEnabled(Player player) {
        if (noticesKey == null) {
            return true;
        }
        Byte value = player.getPersistentDataContainer().get(noticesKey, PersistentDataType.BYTE);
        return value == null || value != 0;
    }

    /** Переключает уведомления и возвращает новое состояние. */
    public boolean toggleNotices(Player player) {
        return setNotices(player, !noticesEnabled(player));
    }

    /** Явно включает/выключает уведомления; состояние переживает перезапуск сервера. */
    public boolean setNotices(Player player, boolean enabled) {
        if (noticesKey != null) {
            player.getPersistentDataContainer().set(noticesKey, PersistentDataType.BYTE,
                    (byte) (enabled ? 1 : 0));
        }
        return enabled;
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

    /** Меню приватов (/claim menu). */
    public ClaimMenuGui menu() {
        return menu;
    }

    /** Телепорт к приватам (/claim tp и клик в меню). */
    public ClaimTeleportService teleports() {
        return teleports;
    }
}
