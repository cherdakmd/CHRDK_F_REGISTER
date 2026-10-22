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
import ru.cherdakmd.chrdkregister.api.ChrdkTierApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * CHRDK_Treasures — русские клады: 5 редкостей, 14 регионов и 196 находок
 * (124 классические и 72 вещи арсенала Mine Treasure).
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
 *
 * <p>Поверх находок работают звания ({@link TreasureRank}), серии
 * ({@link TreasureStreak}) и оформление ({@link TreasureShowcase}); шансы
 * можно переопределить файлом {@code chances.yml}, не трогая комментарии
 * {@code config.yml}. У сильных находок есть активные способности по ПКМ
 * ({@link TreasureAbilities}), проклятия-расплаты ({@link TreasureCurses}) и
 * сетовые бонусы за 2 и 4 предмета региона ({@link TreasureSets}).
 * PlaceholderAPI — мягкая интеграция: без плагина аддон работает как обычно.</p>
 */
public final class TreasuresPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile TreasuresConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private TreasureStore store;
    private TreasureOverrides overrides;
    private TreasureShowcase showcase;
    private TreasureItems items;
    private TreasureService service;
    private TreasureSets sets;
    private TreasureAuras auras;
    private TreasureAbilities abilities;
    private TreasureCurses curses;
    private TreasureListener listener;
    private TreasuresGui gui;
    private BukkitTask saveTask;
    private BukkitTask auraTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new TreasureStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.overrides = new TreasureOverrides(getDataFolder().toPath(), getLogger());
        this.overrides.load();
        this.items = new TreasureItems(this);
        this.showcase = new TreasureShowcase(this);
        // Конфиг читается до листенера: антинакрутка берёт из него TTL и лимит.
        reloadTreasuresConfig();
        this.service = new TreasureService(this, store);
        this.sets = new TreasureSets(this);
        this.auras = new TreasureAuras(this);
        this.abilities = new TreasureAbilities(this);
        this.curses = new TreasureCurses(this);
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
        registerPlaceholders();
        startTasks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.rememberName(player);
            auras.apply(player);
        }

        getLogger().info("CHRDK_Treasures включён: находок " + TreasureRegistry.size()
                + " (арсенал " + TreasureArsenal.size() + ")"
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
        if (overrides != null) {
            overrides.load();
        }
        this.config = TreasuresConfig.parse(getConfig(),
                overrides == null ? java.util.Map.of() : overrides.asMap(), getLogger());
        reloadLootTable();
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
                new TreasuresApiImpl(service, items, sets, abilities), this, ServicePriority.Normal);
    }

    /**
     * Перечитывает {@code loot.yml}; при отсутствии создаёт файл из ресурса.
     * Пустая или битая таблица не остаётся без добычи — включается встроенная.
     */
    private void reloadLootTable() {
        try {
            Path file = getDataFolder().toPath().resolve(TreasureLoot.FILE_NAME);
            if (!Files.isRegularFile(file)) {
                saveResource(TreasureLoot.FILE_NAME, false);
            }
            TreasureLoot.Table table = TreasureLoot.load(file, getLogger());
            if (table.version() < TreasureLoot.CURRENT_VERSION) {
                // Старый файл не перезаписываем: дополняем его новыми записями,
                // чтобы обновление принесло предметы и сохранило правки админа.
                int before = table.size();
                table = table.withMissing(TreasureLoot.resource());
                int added = table.size() - before;
                if (added > 0) {
                    getLogger().info("[CHRDK_Treasures] loot.yml от старой версии: добавлено "
                            + added + " записей. Полный новый файл — в JAR; чтобы заменить,"
                            + " удалите plugins/CHRDK_Treasures/loot.yml и перезапустите сервер.");
                }
            }
            TreasureLoot.install(table);
        } catch (RuntimeException exception) {
            getLogger().warning("[CHRDK_Treasures] Не удалось загрузить "
                    + TreasureLoot.FILE_NAME + ": " + exception.getMessage());
            TreasureLoot.install(TreasureLoot.builtin());
        }
        getLogger().info("[CHRDK_Treasures] Лут-таблиц: "
                + TreasureLoot.active().size() + ".");
    }

    /** PlaceholderAPI — мягкая интеграция: без плагина аддон работает как обычно. */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new TreasurePlaceholders(this).register();
            getLogger().info("[CHRDK_Treasures] PlaceholderAPI: доступны %chrdktreasures_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("[CHRDK_Treasures] Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
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

    /**
     * Тир-система CHRDK_TierForge (1.4.141) — мягкая зависимость: аддон может
     * загрузиться позже, выгрузиться при {@code /reload} или отсутствовать.
     * {@code null} — находки выдаются без тира, весь остальной клад работает.
     */
    public ChrdkTierApi tierApi() {
        try {
            return Bukkit.getServicesManager().load(ChrdkTierApi.class);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    public TreasureStore store() {
        return store;
    }

    public TreasureOverrides overrides() {
        return overrides;
    }

    public TreasureShowcase showcase() {
        return showcase;
    }

    public TreasureSets sets() {
        return sets;
    }

    public TreasureAbilities abilities() {
        return abilities;
    }

    public TreasureCurses curses() {
        return curses;
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
    private record TreasuresApiImpl(TreasureService service, TreasureItems items,
                                    TreasureSets sets, TreasureAbilities abilities)
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
            return TreasureRegistry.size();
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
            TreasureItemDef def = TreasureRegistry.find(itemId);
            return def != null && service.giveItem(player, def);
        }

        @Override
        public String rank(Player player) {
            return service.rank(player).id();
        }

        @Override
        public int streak(Player player) {
            return service.streak(player);
        }

        @Override
        public int bestStreak(Player player) {
            return service.bestStreak(player);
        }

        @Override
        public String activeSet(Player player) {
            TreasureSet set = sets.active(player);
            return set == null ? null : set.region().id();
        }

        @Override
        public int setPieces(Player player, String regionId) {
            TreasureRegion region = TreasureRegion.byId(regionId);
            return region == null ? 0 : sets.pieces(player, region);
        }

        @Override
        public long abilityCooldown(Player player, String itemId) {
            TreasureItemDef def = TreasureRegistry.find(itemId);
            if (def == null || def.ability() == null) {
                return 0L;
            }
            return abilities.remaining(player, def);
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
