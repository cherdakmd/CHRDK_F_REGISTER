package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.Locale;

/**
 * CHRDK_Treasures — русские клады: 5 редкостей, 14 регионов и 62 находки.
 *
 * <p>Клад выпадает за честную добычу блока. Редкость зависит от породы, глубины
 * и биома, а также от прогрессии: редкие клады открываются после накопленного
 * числа добытых блоков, поэтому новичок не получает мифический предмет из
 * первого камня. Внутри редкости с шансом {@code item-chance} выпадает именная
 * находка, иначе — ванильная добыча.</p>
 *
 * <p>Атрибутные бонусы находок запечены в предметы и работают без пульса;
 * ауры (регенерация, дыхание, огнестойкость) пересобираются раз в
 * {@code general.aura-refresh-ticks}. Прогресс и коллекция живут в
 * {@code treasures.json} по UUID и пишутся асинхронно.</p>
 */
public final class TreasuresPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile TreasuresConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private TreasureStore store;
    private TreasureItems items;
    private TreasureService service;
    private TreasureAuras auras;
    private TreasureListener listener;
    private TreasuresGui gui;
    private BukkitTask saveTask;
    private BukkitTask auraTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new TreasureStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.items = new TreasureItems(this);
        // Конфиг читается до листенера: антинакрутка берёт из него TTL и лимит.
        reloadTreasuresConfig();
        this.service = new TreasureService(this, store);
        this.auras = new TreasureAuras(this);
        this.listener = new TreasureListener(this, service);
        this.gui = new TreasuresGui(this, service);

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        TreasureCommandHandler commands = new TreasureCommandHandler(this, service, gui);
        PluginCommand treasures = getCommand("treasures");
        PluginCommand admin = getCommand("treasuresadmin");
        if (treasures != null) {
            treasures.setExecutor(commands);
            treasures.setTabCompleter(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }

        registerApi();
        startTasks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.rememberName(player);
            auras.apply(player);
        }

        getLogger().info("CHRDK_Treasures включён: находок " + TreasureCatalog.size()
                + ", регионов " + TreasureRegion.values().length
                + ", редкостей " + TreasureTier.values().length
                + ", добыча кладов " + (config.enabled() ? "включена" : "выключена") + ".");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (auraTask != null) {
            auraTask.cancel();
            auraTask = null;
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Treasures выключен.");
    }

    /**
     * Перечитывает config.yml и messages.yml, не трогая прогресс игроков.
     *
     * <p>Вызывается при старте (когда листенера ещё нет) и командой
     * {@code /treasuresadmin reload}. Период аур мог измениться — задачи
     * перезапускаются, а уже наложенные эффекты доживают свой срок.</p>
     */
    public void reloadTreasuresConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"),
                getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = TreasuresConfig.parse(getConfig(), getLogger());
        if (listener != null) {
            listener.reloadAntiCheat();
        }
        if (service != null && auras != null && config.enabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                auras.apply(player);
            }
        }
        if (saveTask != null) {
            startTasks();
        }
    }

    /** Асинхронное сохранение и пульс аур; вызывается при старте и после reload. */
    private void startTasks() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (auraTask != null) {
            auraTask.cancel();
        }
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::saveState, 100L, 100L);
        long period = Math.max(1, config.auraRefreshTicks());
        this.auraTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAuras,
                period, period);
    }

    private void refreshAuras() {
        if (!config.enabled() || auras == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                auras.apply(player);
            } catch (RuntimeException exception) {
                getLogger().warning("Не удалось обновить ауры " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void saveState() {
        try {
            if (store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить клады: " + exception.getMessage());
        }
    }

    /** Регистрация публичного API: снаружи виден только интерфейс. */
    public void registerApi() {
        Bukkit.getServicesManager().register(ChrdkTreasuresApi.class,
                new TreasuresApiImpl(service, items), this, ServicePriority.Normal);
    }

    public Messages messages() {
        return messages;
    }

    public TreasuresConfig config() {
        return config;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public TreasureStore store() {
        return store;
    }

    public TreasureItems items() {
        return items;
    }

    public TreasureService treasures() {
        return service;
    }

    public TreasureAuras auras() {
        return auras;
    }

    public TreasuresGui gui() {
        return gui;
    }

    /** Тонкая обёртка: аддоны не видят состояние и не могут его испортить. */
    private record TreasuresApiImpl(TreasureService service, TreasureItems items)
            implements ChrdkTreasuresApi {

        @Override
        public long mined(Player player) {
            return service.mined(player);
        }

        @Override
        public long found(Player player) {
            return service.found(player);
        }

        @Override
        public long tierFound(Player player, String tierId) {
            TreasureTier tier = TreasureTier.byId(tierId);
            return tier == null ? 0L : service.tierCount(player, tier);
        }

        @Override
        public int collection(Player player) {
            return service.foundItems(player);
        }

        @Override
        public int totalItems() {
            return TreasureCatalog.size();
        }

        @Override
        public int itemCount(Player player, String itemId) {
            return service.itemCount(player, itemId);
        }

        @Override
        public boolean hasItem(Player player, String itemId) {
            return service.hasItem(player, itemId);
        }

        @Override
        public double effect(Player player, String kind) {
            if (kind == null || kind.isBlank()) {
                return 0.0;
            }
            TreasureEffect.Kind parsed;
            try {
                parsed = TreasureEffect.Kind.valueOf(
                        kind.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                return 0.0;
            }
            return items.sum(player, parsed);
        }

        @Override
        public boolean give(Player player, String itemId) {
            TreasureItemDef def = TreasureCatalog.find(itemId);
            return def != null && service.giveItem(player, def);
        }

        @Override
        public String region(Location location) {
            if (location == null || location.getWorld() == null) {
                return null;
            }
            return service.regionOf(location.getBlock()).id();
        }
    }
}
