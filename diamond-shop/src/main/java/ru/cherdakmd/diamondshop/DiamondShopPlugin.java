package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_DiamondShop — аддон к CHRDK_F_REGISTER: магазин обмена ресурсов с динамическими
 * ценами, валютой являются алмаз во всех его видах (diamond/руды/блок).
 *
 * <p>Ядро получаем через {@code ServicesManager} (паттерн Vault): без api магазин отвечает
 * отказом, но не падает. Продажи доступны только авторизованным игрокам —
 * {@code api.isAuthenticated(player)} на каждой команде и клике.</p>
 */
public final class DiamondShopPlugin extends JavaPlugin implements org.bukkit.event.Listener {

    private volatile ChrdkRegisterApi coreApi;
    private volatile ru.cherdakmd.chrdkskills.ChrdkSkillsApi skillsApi;
    private volatile ShopService shopService;
    private ShopStore store;
    private ShopMarketGui gui;
    private ShopQuantityGui quantities;
    private ShopAdminGui adminGui;
    private ShopOverrides overrides;
    /** Прогресс дневных акций живёт отдельным файлом и переживает /shopadmin reload. */
    private ShopPromoStore promoStore;
    private ForgeShopPrices forgePrices;
    private ForgeShopService forgeShop;
    private ForgeShopGui forgeShopGui;
    private SkillPotionShopGui potionShopGui;
    public ShopQuantityGui quantities(){return quantities;}
    public ShopAdminGui shopAdminGui(){return adminGui;}
    public ShopOverrides overrides(){return overrides;}
    public ForgeShopService forgeShop(){return forgeShop;}
    public ForgeShopGui forgeShopGui(){return forgeShopGui;}
    public SkillPotionShopGui potionShopGui(){return potionShopGui;}
    /** Принять черновик GUI: записать shop-overrides.json и перечитать каталог. */
    public void applyOverrides(ShopOverrides draft) {
        overrides.replaceWith(draft);
        overrides.save();
        reloadShopConfig();
    }
    private volatile Messages messages;
    private BukkitTask saveTask;
    private BukkitTask historyTask;
    private BukkitTask migrationTask;
    private BukkitTask recoveryTask;

    @Override
    public void onEnable() {
        try { if(ChrdkRegisterApi.class.getField("API_VERSION").getInt(null)<43) throw new IllegalStateException("Нужен Core API 43+"); }
        catch(Exception e) { getLogger().severe("Магазин выключен: нужен актуальный CHRDK_Core API 29+"); Bukkit.getPluginManager().disablePlugin(this); return; }
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        ShopCatalogMigration.apply(this);
        reloadShopConfig(); // stores первичную инициализацию всех сервисов

        Bukkit.getPluginManager().registerEvents(this,this);
        recoveryTask=Bukkit.getScheduler().runTaskTimer(this,()->shopService.recoverPending(),1L,20L);
        this.gui = new ShopMarketGui(this);
        this.adminGui = new ShopAdminGui(this);
        this.quantities=new ShopQuantityGui(this);
        getServer().getPluginManager().registerEvents(quantities,this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(adminGui, this);
        Bukkit.getPluginManager().registerEvents(forgeShopGui, this);
        if (this.potionShopGui == null) this.potionShopGui = new SkillPotionShopGui(this);
        Bukkit.getPluginManager().registerEvents(potionShopGui, this);

        ShopCommandHandler commands = new ShopCommandHandler(this);
        for (String name : List.of("shop", "shopadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commands);
                command.setTabCompleter(commands);
            }
        }

        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (store.isDirty()) {
                store.saveAsync();
            }
        }, 6000L, 6000L);

        // точка истории цен раз в интервал из конфига (по умолчанию час)
        long historyTicks = Math.clamp(
                shopService.config().historyIntervalMillis() / 50, 6000L, 1728000L);
        this.historyTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () ->
                shopService.config().items().forEach(item -> shopService.recordPricePoint(item)),
                historyTicks, historyTicks);

        var legacy = store.legacyBalances().entrySet().iterator();
        migrationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (int i = 0; i < 4 && legacy.hasNext(); i++) {
                var entry = legacy.next();
                if (!shopService.migrate(entry.getKey(), entry.getValue()))
                    getLogger().warning("Старый счёт " + entry.getKey() + " пока не перенесён: проверьте Core/лимит/исходную сумму. Он сохранён, магазин повторит проверку при обращении.");
            }
            if (!legacy.hasNext()) { migrationTask.cancel(); migrationTask = null; }
        }, 1L, 20L);
        getLogger().info("CHRDK_DiamondShop включён: товаров " + shopService.config().items().size()
                + ", ядро " + (coreApi == null ? "НЕ найдено!" : "v" + coreApi.pluginVersion()) + ".");
    }

    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST)
    public void onAuthenticated(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) {
        if(shopService!=null&&store.pending()!=null&&store.pending().trade().owner().equals(event.getPlayer().getUniqueId())) shopService.recoverPending();
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.closeAll();
        if (adminGui != null) adminGui.closeAll();
        if(recoveryTask!=null) { recoveryTask.cancel(); recoveryTask=null; }
        if (migrationTask != null) { migrationTask.cancel(); migrationTask = null; }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (historyTask != null) {
            historyTask.cancel();
            historyTask = null;
        }
        if (store != null) {
            store.saveNow(); // принудительно, даже если не dirty-флага не было снято
        }
        getLogger().info("CHRDK_DiamondShop выключен.");
    }

    /** Перечитывает config.yml/messages.yml и пересоздаёт сервис магазина. */
    public void reloadShopConfig() {
        if (gui != null) gui.closeAll();
        if (adminGui != null) adminGui.closeAll();
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.skillsApi = Bukkit.getServicesManager().load(
                ru.cherdakmd.chrdkskills.ChrdkSkillsApi.class);
        // Перечитываем файл при каждом reload: владелец мог поправить его вручную.
        this.overrides = ShopOverrides.load(getDataFolder().toPath(), getLogger());
        if (this.store == null) {
            this.store = new ShopStore(getDataFolder().toPath(), getLogger());
            this.store.load();
        }
        ShopConfig.ShopConfigData data = ShopConfig.parse(getConfig(), getLogger(), overrides);
        if (this.promoStore == null) {
            this.promoStore = new ShopPromoStore(getDataFolder().toPath(), getLogger());
            this.promoStore.load();
        }
        ShopPromoConfig.Settings promoSettings = ShopPromoConfig.from(getConfig(), data, getLogger());
        ShopPromoService promos = new ShopPromoService(promoSettings, promoStore);
        this.shopService = new ShopService(data, store, this::coreApi, this::skillsApi, promos);
        this.forgePrices = ForgeShopPrices.parse(getConfig(), getLogger());
        if (this.forgeShopGui == null) this.forgeShopGui = new ForgeShopGui(this);
        this.forgeShop = new ForgeShopService(this, forgePrices);
    }

    /** API ядра; {@code null}, если основной плагин не загружен (очередь загрузки). */
    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    /** Мягкий API CHRDK_Skills; {@code null}, если аддон не установлен. */
    public ru.cherdakmd.chrdkskills.ChrdkSkillsApi skillsApi() {
        return skillsApi;
    }

    public ShopService shopService() {
        return shopService;
    }

    public Messages messages() {
        return messages;
    }

    public ShopMarketGui gui() {
        return gui;
    }
}
