package ru.cherdakmd.chrdkbp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CHRDK_BattlePass — сезон с волнами заданий и двумя ветками наград
 * (бесплатная + VIP для владельцев ранга). Награды — часы буста через API ядра (1.4.184).
 *
 * <p>Версия 1.4.109-SNAPSHOT расширяет аддон: новые типы заданий (PLACE, SMELT,
 * ENCHANT, BREED), фильтры мира и права, честная игра (креатив и «поставил-сломал»),
 * очки и рейтинг, меню, оповещения о волнах и админские команды.</p>
 */
public final class BattlePassPlugin extends JavaPlugin implements Listener {

    private volatile Messages messages;
    private volatile BattlePassScreen screen;
    private volatile BattlePassSettings settings;
    private volatile BattlePassConfig.Season season;
    private volatile ChrdkRegisterApi coreApi;
    private BattlePassStore store;
    private BattlePassClaims claims;
    private BattlePassMenu menu;
    private BattlePassGuide guide;
    BattlePassGuide guide(){return guide;}
    private BattlePassFx fx;
    private volatile BattlePassRecentPlaces recentPlaces;
    private volatile List<String> problems = List.of();
    private BukkitTask autosaveTask;
    private BukkitTask announceTask;

    private boolean supportsRewardReceipts() {
        try { return ChrdkRegisterApi.class.getField("API_VERSION").getInt(null)>=36; }
        catch(ReflectiveOperationException e){return false;}
    }
    boolean canUse(Player player) {
        return player!=null && player.isOnline() && !player.isDead() && !player.hasMetadata("NPC")
                && player.hasPermission("chrdkbp.use") && coreApi!=null && coreApi.isAuthenticated(player) && store.healthy();
    }
    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new BattlePassStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if(coreApi==null || !supportsRewardReceipts()) {
            getLogger().severe("Нужен Core API36 (1.4.281-rc.1+): безопасная выдача наград battlepass");
            Bukkit.getPluginManager().disablePlugin(this);return;
        }
        reloadBattlePassConfig();

        this.claims = new BattlePassClaims(this);
        this.menu = new BattlePassMenu(this);
        this.fx = new BattlePassFx(this);
        this.guide = new BattlePassGuide(this);
        Bukkit.getPluginManager().registerEvents(guide,this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(menu, this);

        PluginCommand command = getCommand("battlepass");
        if (command != null) {
            BattlePassCommandHandler handler = new BattlePassCommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("bpadmin");
        if (admin != null) {
            BattlePassAdminCommandHandler handler = new BattlePassAdminCommandHandler(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }
        startTasks();
        if (coreApi != null) {
            try {
                coreApi.registerProfile("battlepass", new BattlePassProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_BattlePass] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }
        getLogger().info("CHRDK_BattlePass включён: волн " + season.weeks().size()
                + ", заданий " + missionCount() + ".");
    }

    @Override
    public void onDisable() {
        if(guide!=null)guide.closeAll();
        if(menu!=null)menu.closeAll();
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("battlepass");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (announceTask != null) {
            announceTask.cancel();
            announceTask = null;
        }
        if (store != null) {
            store.save();
        }
        getLogger().info("CHRDK_BattlePass выключен.");
    }

    /** Перечитать config.yml и messages.yml, не перезапуская сервер. */
    public void reloadBattlePassConfig() {
        if(guide!=null)guide.closeAll();
        if(menu!=null)menu.closeAll();
        reloadConfig();
        try(var stream=getResource("battlepass-default-legacy-1.4.280.yml")) {
            if(stream!=null) {
                var legacy=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
                if(BattlePassConfig.stockUpgrade(getConfig().getList("battlepass.weeks"),legacy.getList("battlepass.weeks"))) {
                    try(var current=getResource("config.yml")) {
                        var defaults=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(current,java.nio.charset.StandardCharsets.UTF_8));
                        getConfig().set("battlepass.weeks",defaults.getList("battlepass.weeks"));saveConfig();
                        getLogger().info("Стоковые награды battlepass усилены; ID, цели и прогресс сохранены.");
                    }
                }
            }
        } catch(java.io.IOException e){getLogger().warning("Миграция стокового battlepass не подтверждена: "+e.getMessage());}

        this.messages = Messages.load(this,
                getConfig().getString("general.locale", "ru"), getLogger());
        this.settings = BattlePassSettings.parse(getConfig());
        this.screen = new BattlePassScreen(this.messages, this.settings);
        this.recentPlaces = new BattlePassRecentPlaces(
                settings.placedMemorySeconds() * 1000L, 20_000);

        List<Map<?, ?>> rawWeeks = rawWeeks();
        List<BattlePassConfig.WeekDef> weeks;
        try {
            BattlePassConfig.ParseResult parsed = BattlePassConfig.parseWeeksDetailed(rawWeeks);
            weeks = parsed.weeks();
            problems = parsed.problems();
            for (String problem : problems) {
                getLogger().warning("[CHRDK_BattlePass] " + problem);
            }
        } catch (IllegalStateException exception) {
            getLogger().severe("[CHRDK_BattlePass] Ошибка конфига: " + exception.getMessage());
            weeks = List.of();
            problems = List.of(exception.getMessage());
        }
        try {
            this.season = BattlePassConfig.season(
                    getConfig().getString("battlepass.season-name", "Сезон"),
                    getConfig().getString("battlepass.season-start", "2026-01-01"),
                    getConfig().getString("battlepass.season-end", ""),
                    weeks);
        } catch (IllegalStateException exception) {
            getLogger().severe("[CHRDK_BattlePass] " + exception.getMessage());
            this.season = new BattlePassConfig.Season("Сезон", 0L, weeks);
            List<String> merged = new ArrayList<>(problems);
            merged.add(exception.getMessage());
            problems = List.copyOf(merged);
        }
        if (claims != null) {
            startTasks();
        }
    }

    /** Сырые волны из конфига — для разбора и админской диагностики. */
    List<Map<?, ?>> rawWeeks() {
        List<Map<?, ?>> raw = new ArrayList<>();
        for (Object entry : getConfig().getList("battlepass.weeks", List.of())) {
            if (entry instanceof Map<?, ?> map) {
                raw.add(map);
            }
        }
        return raw;
    }

    private void startTasks() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (announceTask != null) {
            announceTask.cancel();
        }
        int seconds = settings.autosaveSeconds();
        autosaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            BattlePassStore current = store;
            if (current != null) {
                current.saveIfDirty();
            }
        }, 20L * seconds, 20L * seconds);
        announceTask = getServer().getScheduler().runTaskTimer(this,
                this::announceWaves, 100L, 20L * 30L);
    }

    // ----------------------------------------------------------- слушатели --

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        BattlePassRecentPlaces places = recentPlaces;
        if (places != null && settings.ignorePlayerPlaced()
                && places.consume(event.getPlayer().getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis())) {
            return; // этот блок игрок поставил сам — прогресс не накручиваем
        }
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.MINE,
                block.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        org.bukkit.block.Block block = event.getBlockPlaced();
        BattlePassRecentPlaces places = recentPlaces;
        if (places != null && settings.ignorePlayerPlaced()) {
            places.record(event.getPlayer().getUniqueId(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(), System.currentTimeMillis());
        }
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.PLACE,
                block.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        recordProgress(killer, BattlePassConfig.MissionType.KILL,
                event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            recordProgress(player, BattlePassConfig.MissionType.BREED,
                    event.getEntity().getType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        recordProgress(event.getEnchanter(), BattlePassConfig.MissionType.ENCHANT,
                event.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.SMELT,
                event.getItemType().name(), Math.max(1, event.getItemAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        recordProgress(player, BattlePassConfig.MissionType.CRAFT,
                result.getType().name(), Math.max(1, result.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Entity caught = event.getCaught();
        String key = caught instanceof Item item
                ? item.getItemStack().getType().name()
                : "any";
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.FISH, key, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        store.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        announceWave(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (store.isDirty() && isEnabled()) {
            try {
                getServer().getScheduler().runTaskAsynchronously(this, store::saveIfDirty);
            } catch (RuntimeException exception) {
                // сервер уже останавливается — финальное сохранение сделает onDisable
                getLogger().fine("Отложенное сохранение батлпасса отменено: "
                        + exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------- прогресс --

    /** Прогресс по всем подходящим заданиям открытых волн. Возвращает выполненные сейчас. */
    List<BattlePassConfig.MissionDef> recordProgress(Player player,
                                                     BattlePassConfig.MissionType type,
                                                     String actualKey, int delta) {
        List<BattlePassConfig.MissionDef> completedNow = new ArrayList<>();
        BattlePassConfig.Season current = season;
        if (current == null || current.empty() || !canUse(player)) {
            return completedNow;
        }
        long now = System.currentTimeMillis();
        if (current.ended(now)) {
            return completedNow; // сезон закончился — прогресс заморожен
        }
        BattlePassSettings fair = settings;
        if (!fair.countCreative() && player.getGameMode() == GameMode.CREATIVE) {
            return completedNow;
        }
        store.rememberName(player.getUniqueId(), player.getName());
        String worldName = player.getWorld().getName().toLowerCase(Locale.ROOT);
        for (int weekIndex = 0; weekIndex < current.weeks().size(); weekIndex++) {
            if (!BattlePassConfig.isUnlocked(current, weekIndex, now)) {
                break; // волны упорядочены по unlock-day
            }
            for (BattlePassConfig.MissionDef mission : current.weeks().get(weekIndex).missions()) {
                if (mission.type() != type
                        || !BattlePassConfig.matchesTypeKey(mission, actualKey, fair.matchVariants())) {
                    continue;
                }
                if (mission.restrictedWorld() && !mission.worlds().contains(worldName)) {
                    continue;
                }
                if (mission.restrictedPermission() && !player.hasPermission(mission.permission())) {
                    continue;
                }
                int before = store.progressOf(player.getUniqueId(), mission.id());
                if (before >= mission.target()) {
                    continue; // уже выполнено — зачем дёргать диск
                }
                int after = store.addProgress(player.getUniqueId(), mission.id(),
                        delta, mission.target());
                if (before < mission.target() && after >= mission.target()) {
                    completedNow.add(mission);
                    player.sendMessage(screen.missionDone("battlepass", mission));
                    fx.completed(player);
                }
            }
        }
        return completedNow;
    }

    // ---------------------------------------------------------- оповещения --

    /** Проверить у всех онлайн-игроков, не открылась ли новая волна. */
    private void announceWaves() {
        BattlePassConfig.Season current = season;
        if (current == null || current.empty() || !settings.announceNewWaves()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            announceWave(player);
        }
    }

    /**
     * Сообщить игроку о новой волне один раз. Первое знакомство (lastWave = 0)
     * проходит молча: новичку не нужно поздравление об «открытии» первой волны.
     */
    private void announceWave(Player player) {
        BattlePassConfig.Season current = season;
        if (current == null || current.empty() || !settings.announceNewWaves() || !canUse(player)) {
            return;
        }
        int week = BattlePassConfig.currentWeek(current, System.currentTimeMillis());
        if (week <= 0) {
            return;
        }
        int seen = store.lastWave(player.getUniqueId());
        if (week <= seen) {
            return;
        }
        store.setLastWave(player.getUniqueId(), week);
        if (seen > 0) {
            messages.send(player, "wave-opened", "week", week);
            fx.wave(player, week);
        }
    }

    // -------------------------------------------------------------- геттеры --

    public Messages messages() {
        return messages;
    }

    public BattlePassScreen screen() {
        return screen;
    }

    public BattlePassSettings settings() {
        return settings;
    }

    public BattlePassConfig.Season season() {
        return season;
    }

    public BattlePassStore store() {
        return store;
    }

    public BattlePassClaims claims() {
        return claims;
    }

    public BattlePassMenu menu() {
        return menu;
    }

    public BattlePassFx fx() {
        return fx;
    }

    ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    /** Активна ли ранг: от неё зависит доступность VIP-наград. */
    boolean vipActive(Player player) {
        ChrdkRegisterApi api = coreApi;
        return api != null && player != null && api.hasActivePass(player.getName());
    }

    /**
     * Активен ли боевой пропуск: куплен товар каталога из настроек премиум-трека.
     *
     * <p>Каталог может измениться после покупки — срок живёт в аккаунте ядра,
     * поэтому батлпасс каждый раз спрашивает ядро, а не помнит флаг у себя.</p>
     */
    boolean premiumActive(Player player) {
        BattlePassSettings cfg = settings;
        ChrdkRegisterApi api = coreApi;
        if (cfg == null || !cfg.premiumEnabled() || api == null || player == null) {
            return false;
        }
        if (api.rankLevel(player.getName()) >= getConfig().getInt("battlepass.premium.min-rank", 4)) {
            return true; // премиум-трек включён в ранги IV–V (1.4.167)
        }
        return BattlePassPremium.active(true,
                api.entitlementUntil(player.getName(), cfg.premiumProduct()),
                System.currentTimeMillis());
    }

    /** До какого момента активна ранг игрока; 0 — ядро недоступно. */
    long passUntil(Player player) {
        ChrdkRegisterApi api = coreApi;
        return api == null || player == null ? 0L : api.passUntil(player.getName());
    }

    /** Проблемы разбора конфига с последней перезагрузки. */
    List<String> problems() {
        return problems;
    }

    /** Сколько заданий в сезоне (для логов и сводок). */
    int missionCount() {
        return season == null ? 0
                : season.weeks().stream().mapToInt(week -> week.missions().size()).sum();
    }

    static String shortLabel() {
        return "bp";
    }
}
