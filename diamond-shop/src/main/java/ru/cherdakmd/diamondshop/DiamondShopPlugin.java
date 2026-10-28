package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
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
public final class DiamondShopPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile ShopService shopService;
    private ShopStore store;
    private ShopGui gui;
    private volatile Messages messages;
    private BukkitTask saveTask;
    private BukkitTask historyTask;
    private BukkitTask migrationTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadShopConfig(); // stores первичную инициализацию всех сервисов

        this.gui = new ShopGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);

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
            for (int i = 0; i < 16 && legacy.hasNext(); i++) {
                var entry = legacy.next();
                if (!shopService.migrate(entry.getKey(), entry.getValue()))
                    getLogger().warning("Старый счёт " + entry.getKey() + " пока не перенесён: проверьте Core/лимит/исходную сумму. Он сохранён, магазин повторит проверку при обращении.");
            }
            if (!legacy.hasNext()) { migrationTask.cancel(); migrationTask = null; }
        }, 1L, 1L);
        getLogger().info("CHRDK_DiamondShop включён: товаров " + shopService.config().items().size()
                + ", ядро " + (coreApi == null ? "НЕ найдено!" : "v" + coreApi.pluginVersion()) + ".");
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.closeAll();
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
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (this.store == null) {
            this.store = new ShopStore(getDataFolder().toPath(), getLogger());
            this.store.load();
        }
        ShopConfig.ShopConfigData data = ShopConfig.parse(getConfig(), getLogger());
        this.shopService = new ShopService(data, store, this::coreApi);
    }

    /** API ядра; {@code null}, если основной плагин не загружен (очередь загрузки). */
    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public ShopService shopService() {
        return shopService;
    }

    public Messages messages() {
        return messages;
    }

    public ShopGui gui() {
        return gui;
    }
}
