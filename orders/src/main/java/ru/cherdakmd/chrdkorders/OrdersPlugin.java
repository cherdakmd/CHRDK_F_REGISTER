package ru.cherdakmd.chrdkorders;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Orders — доска заказов от ботов (1.4.154).
 *
 * <p>Заказы приходят от ботов-записей конфига, а не от игроков: сервер не
 * зависит от онлайна и чужих цен. Доска общая, заказы живут по времени, а
 * награда — алмазы и редкие дни проходки. Прогресс (репутация и сдачи) лежит
 * в {@code orders.json} и переживает перезапуск.</p>
 */
public final class OrdersPlugin extends JavaPlugin {

    private OrdersConfig config;
    private Messages messages;
    private OrdersStore store;
    private OrdersService service;
    private OrdersGui gui;
    private volatile ChrdkRegisterApi coreApi;
    private BukkitTask boardTask;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = OrdersConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            this.store = new OrdersStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new OrdersService(this);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("orders", new OrdersProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Orders] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            this.gui = new OrdersGui(this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            if (config.enabled()) {
                service.ensureBoard();
            } else {
                getLogger().info("Доска заказов выключена в config.yml.");
            }
            scheduleTasks();
            getLogger().info("CHRDK_Orders включён: заказов на доске " + store.size()
                    + " из " + config.boardSize() + ", ботов " + config.bots().size()
                    + ", категорий " + config.categories().size() + ", игроков в базе "
                    + store.players() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Orders не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("orders");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        cancelTasks();
        if (gui != null) {
            gui.closeAll();
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Orders выключен.");
    }

    /**
     * Перечитывает config.yml и messages.yml, не трогая заказы и прогресс.
     * Уже висящие заказы остаются как есть: это снимки, а не ссылки на конфиг.
     */
    public void reloadOrders() {
        reloadConfig();
        this.config = OrdersConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (config.enabled()) {
            service.ensureBoard();
        }
    }

    private void registerCommands() {
        OrderCommandHandler handler = new OrderCommandHandler(this);
        for (String name : new String[]{"orders", "ordersadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
                continue;
            }
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    /** PlaceholderAPI — мягкая интеграция: без плагина доска работает как обычно. */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new OrdersPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkorders_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    private void scheduleTasks() {
        long refresh = Math.max(20L, OrdersService.refreshSeconds() * 20L);
        this.boardTask = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                service.ensureBoard();
            } catch (RuntimeException exception) {
                getLogger().warning("Не удалось обновить доску заказов: " + exception.getMessage());
            }
        }, refresh, refresh);
        this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                this::saveNow, 100L, 100L);
    }

    private void cancelTasks() {
        if (boardTask != null) {
            boardTask.cancel();
            this.boardTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            this.saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            this.queuedSave = null;
        }
    }

    /** Просит сохранить данные в ближайшие секунды, не чаще одного раза за 2 с. */
    void requestSave() {
        if (saveQueued || !isEnabled()) {
            return;
        }
        saveQueued = true;
        this.queuedSave = getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            saveQueued = false;
            queuedSave = null;
            saveNow();
        }, 40L);
    }

    private void saveNow() {
        try {
            if (store != null && store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить orders.json: " + exception.getMessage());
        }
    }

    public OrdersConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public OrdersStore store() {
        return store;
    }

    public OrdersService service() {
        return service;
    }

    public OrdersGui gui() {
        return gui;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }
}
