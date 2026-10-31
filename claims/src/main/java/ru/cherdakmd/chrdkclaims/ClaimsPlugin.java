package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
 * ежесуточная рента с личного /bal, налог на сделки, продажа игрокам, полная защита.
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
    private ClaimFinance finance;
    private BukkitTask financeTask;
    ClaimFinance finance() { return finance; }
    boolean financeReadReady() { return finance!=null&&finance.readReady(); }
    boolean financeReady() { return finance!=null&&!finance.busy()&&finance.binding(); }
    private ClaimsService service;
    private ClaimAudit audit;
    private ClaimMenuGui menu;
    private ClaimTeleportService teleports;
    private ru.cherdakmd.chrdkregister.api.PaidActions teleportPayments;
    private BukkitTask teleportFinanceTask;
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    /** Активные подсветки границ: игрок → задача (чтобы не плодить таймеры). */
    private static final class BorderSession {
        final boolean manual;
        int left;
        BukkitTask task;
        ClaimBorders.Outline outline;
        String world;
        long revision = -1;
        long cursor;
        BorderSession(boolean manual, int pulses) { this.manual = manual; this.left = pulses; }
    }
    private final Map<UUID, BorderSession> borderTasks = new ConcurrentHashMap<>();
    private final ClaimBorderEntryTracker borderEntries = new ClaimBorderEntryTracker();
    private static final Particle.DustOptions OWN_BORDER = new Particle.DustOptions(Color.fromRGB(80, 255, 80), 1.8f);
    private static final Particle.DustOptions FOREIGN_BORDER = new Particle.DustOptions(Color.fromRGB(255, 170, 30), 1.8f);
    private static final Particle.DustOptions FREE_BORDER = new Particle.DustOptions(Color.fromRGB(0, 210, 255), 1.8f);
    /** Последний приват игрока и кулдаун уведомлений о границах. */
    private final ClaimNoticeTracker claimNotices = new ClaimNoticeTracker();
    /** Персональный выключатель уведомлений (/claim notices) хранится в данных игрока. */
    private NamespacedKey noticesKey;
    private BukkitTask saveTask;
    private BukkitTask upkeepTask;
    private BukkitTask rentalTask;
    private BukkitTask moneyMigrationTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadClaimsConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 30) throw new IllegalStateException("Нужен Core API 30+");
            teleportPayments = new ru.cherdakmd.chrdkregister.api.PaidActions(this,this::coreApi,this::messages,"claim-teleports");
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Приваты остановлены: платёжный протокол недоступен; данные не сброшены",error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        teleportFinanceTask=Bukkit.getScheduler().runTaskTimer(this,teleportPayments::tick,100L,100L);
        this.store = new ClaimStore(getDataFolder().toPath(), getLogger());
        store.load();
        store.membershipIndex(this::clanMembership);
        this.audit = new ClaimAudit(getDataFolder().toPath(), getLogger());
        this.service = new ClaimsService(this);
        reloadClaimsConfig();
        var bank=new ClaimBankBridge(this); store.accounts(bank);
        finance=new ClaimFinance(store,bank); store.finance=finance;
        financeTask=Bukkit.getScheduler().runTaskTimer(this,()->{ if(!finance.recover()) getLogger().fine("Финансы Claims ожидают банк или завершение canonical WAL"); },1L,100L);
        var names = new java.util.LinkedHashSet<String>(store.legacyTreasuries().keySet());
        for (var claim : store.all()) { names.add(claim.owner); if (!claim.tenant.isBlank()) names.add(claim.tenant); }
        var pendingNames = names.iterator();
        moneyMigrationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if(!financeReady()) return;
            for (int i=0; i<4 && pendingNames.hasNext(); i++) {
                String name = pendingNames.next();
                if (!store.fundsReady(name)) getLogger().warning("Казна " + name + " пока не перенесена: банк/UUID/предел счёта недоступен. Остаток сохранён, рента этого владельца приостановлена.");
            }
            if (!pendingNames.hasNext()) { moneyMigrationTask.cancel(); moneyMigrationTask = null; }
        }, 1L, 20L);
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
                    + "проверка авторизации и бонус лимита за ранг недоступны.");
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
        rentalTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> service.processRentals(System.currentTimeMillis()), 80L, 1200L);
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
        if(financeTask!=null) financeTask.cancel();
        if (teleportFinanceTask!=null) teleportFinanceTask.cancel();
        if (moneyMigrationTask != null) { moneyMigrationTask.cancel(); moneyMigrationTask = null; }
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
        if (rentalTask != null) rentalTask.cancel();
        if (upkeepTask != null) {
            upkeepTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        borderTasks.values().forEach(session -> session.task.cancel());
        borderTasks.clear();
        borderEntries.clearAll();
        if (store != null) {
            store.markDirty();
            store.save();
        }
        getLogger().info("CHRDK_Claims выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadClaimsConfig() {
        reloadConfig();
        ru.cherdakmd.chrdkregister.api.EconomyTariffMigration.apply(this);
        // Старый config.yml получает новые стоковые значения уведомлений один раз.
        // Файл правится построчно: комментарии и порядок ключей сохраняются.
        if (ClaimNoticeMigration.apply(getDataFolder().toPath().resolve("config.yml"),
                getDataFolder().toPath(), getLogger())) {
            reloadConfig();
        ru.cherdakmd.chrdkregister.api.EconomyTariffMigration.apply(this);
        }
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = ClaimsConfig.parse(getConfig(), getLogger());
    }

    /** После авторизации доставляем отложенные вести (долги, утраты, продажи) и напоминаем о ренте. */
    @EventHandler
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        // Повторный вход после авторизации — новый визуальный сеанс.
        borderEntries.clear(player.getUniqueId());
        // Визуализация не зависит от готовности финансового журнала.
        refreshNoticeLater(player);
        if(!financeReady()) return;
        store.fundsReady(player.getName());
        List<String> notices = store.pollNotices(player.getName());
        if (!notices.isEmpty()) {
            messages().send(player, "notices-join", "count", String.valueOf(notices.size()));
            for (String notice : notices) {
                messages().send(player, "notice-line", "text", notice);
            }
        }
        service.sendRentReminder(player);
    }

    /** После истечения ранга лимит уменьшается: предупреждаем, если чанков стало больше нормы. */
    @EventHandler
    public void onPassExpired(PassExpiredEvent event) {
        Player player = Bukkit.getPlayerExact(event.getPlayerName());
        if (player == null) {
            return;
        }
        int claims = store.count(player.getName());
        int limit = config.rankChunks.isEmpty()
                ? ClaimMath.effectiveLimit(config.baseChunks, config.passBonusChunks, false)
                : config.rankChunks.get(0);
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
        borderEntries.clear(id);
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
            UUID id = event.getEntity().getUniqueId();
            claimNotices.forget(id);
            borderEntries.clear(id);
            cancelBorders(id);
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
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
        if (!service.isAllowed(player)) {
            borderEntries.clear(player.getUniqueId());
            cancelBorders(player.getUniqueId());
            return;
        }
        String world = to.getWorld().getName();
        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;
        Claim claim = config.worldEnabled(world) ? store.claimAt(world, chunkX, chunkZ) : null;
        updateOwnBorders(player, world, claim);
        if (!config.enterNotice && !config.leaveNotice) return;
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
                // Игроку — координаты блока в центре привата, а не номер чанка:
                // чанк -139 читается как «где-то посередине», блок -2216 совпадает
                // с F3, миникартой и точкой телепорта к привату.
                "x", String.valueOf(spot.blockX()),
                "z", String.valueOf(spot.blockZ()),
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

    /** Автопоказ только владельцу и только при входе в другую связную территорию. */
    private void updateOwnBorders(Player player, String world, Claim claim) {
        UUID id = player.getUniqueId();
        if (!config.borderOnOwnEntry || !canShowBorders(player) || claim == null || !claim.isOwner(player.getName())) {
            borderEntries.clear(id);
            BorderSession active = borderTasks.get(id);
            if (active != null && !active.manual) cancelBorders(id);
            return;
        }
        boolean entered = borderEntries.entered(id, player.getName(), claim,
                (x, z) -> store.claimAt(world, x, z));
        BorderSession active = borderTasks.get(id);
        // Автоматический вход не перезапускает уже включённый ручной показ.
        if (entered && (active == null || !active.manual)) startBorders(player, false);
    }

    private boolean canShowBorders(Player player) {
        return player.isOnline() && !player.isDead() && service.isAllowed(player)
                && (player.hasPermission("chrdkclaims.use") || player.hasPermission("chrdkclaims.admin"))
                && config.worldEnabled(player.getWorld().getName());
    }

    /** Переключает текущий показ; повтор команды выключает и автоматическую подсветку. */
    public boolean toggleBorders(Player player) {
        UUID id = player.getUniqueId();
        if (borderTasks.containsKey(id)) {
            cancelBorders(id);
            return false;
        }
        if (!canShowBorders(player)) return false;
        startBorders(player, true);
        return true;
    }

    private void startBorders(Player player, boolean manual) {
        UUID id = player.getUniqueId();
        cancelBorders(id);
        BorderSession session = new BorderSession(manual, Math.max(1, config.borderSeconds * 2));
        session.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!canShowBorders(player) || session.left-- <= 0
                    || (!session.manual && !config.borderOnOwnEntry)) {
                cancelBorders(id);
                return;
            }
            drawBorder(player, session);
        }, 0L, BORDER_PERIOD_TICKS);
        borderTasks.put(id, session);
    }

    private void cancelBorders(UUID id) {
        BorderSession session = borderTasks.remove(id);
        if (session != null) session.task.cancel();
    }

    /** Весь внешний контур, три высоты и искры. Только получателю, без загрузки чанков. */
    private void drawBorder(Player player, BorderSession session) {
        Location base = player.getLocation();
        String world = base.getWorld().getName();
        int x = base.getBlockX() >> 4, z = base.getBlockZ() >> 4;
        Claim current = store.claimAt(world, x, z);
        if (!session.manual && (current == null || !current.isOwner(player.getName()))) {
            cancelBorders(player.getUniqueId());
            return;
        }
        long revision = store.borderRevision();
        if (session.outline == null || !world.equals(session.world) || revision != session.revision
                || !session.outline.contains(x, z)) {
            // Не сбрасываем обход из-за оплаты/аренды: частые изменения метаданных
            // не должны постоянно вытеснять дальние рёбра из бюджета частиц.
            if (session.outline == null || !world.equals(session.world) || !session.outline.contains(x, z)) session.cursor = 0;
            session.outline = ClaimBorders.outline(x, z, (cx, cz) -> store.claimAt(world, cx, cz));
            session.world = world;
            session.revision = revision;
        }
        Particle.DustOptions dust = current == null ? FREE_BORDER
                : current.isOwner(player.getName()) ? OWN_BORDER : FOREIGN_BORDER;
        var frame = ClaimBorderParticles.frame(session.outline.edges(), session.cursor);
        session.cursor = frame.nextCursor();
        for (var point : frame.points()) {
            if (point.spark()) {
                player.spawnParticle(Particle.ELECTRIC_SPARK, point.x(), base.getY() + point.height(), point.z(),
                        1, 0, 0, 0, 0, null, true);
            } else {
                player.spawnParticle(Particle.DUST, point.x(), base.getY() + point.height(), point.z(),
                        1, 0, 0, 0, 0, dust, true);
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

    ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.Membership clanMembership(String name) {
        try {
            if (coreApi == null) return null;
            var index = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.class);
            return index == null ? null : index.membership(name);
        } catch (RuntimeException | LinkageError error) { return null; }
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
    public ru.cherdakmd.chrdkregister.api.PaidActions teleportPayments() { return teleportPayments; }
    @EventHandler(priority=org.bukkit.event.EventPriority.LOWEST)
    public void onTeleportFinanceAuth(PlayerAuthCompletedEvent event) { teleportPayments.recover(event.getPlayer()); }
    public ClaimTeleportService teleports() {
        return teleports;
    }
}
