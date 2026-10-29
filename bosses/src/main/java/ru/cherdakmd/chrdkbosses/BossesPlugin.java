package ru.cherdakmd.chrdkbosses;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Bosses — боссы с фазами и зоны опасности (1.4.162).
 *
 * <p>Босс — это не просто моб с большим здоровьем: у него есть фазы, которые
 * меняют бой, способности, арена и честное деление награды по вкладу. Зоны
 * опасности добавляют миру мест, где страшно и выгодно: эффекты и капель урона
 * в обмен на надбавку к опыту.</p>
 *
 * <p>Аддон не трогает экономику: награда — опыт, расходники и трофей. Монеты
 * остаются охоте, алмазы — магазинам и кладам.</p>
 */
public final class BossesPlugin extends JavaPlugin {

    private BossConfig config;
    private Messages messages;
    private BossStore store;
    private BossService service;
    private BossesGui gui;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;
    private ru.cherdakmd.chrdkregister.api.MoneyLoot moneyLoot;
    public ru.cherdakmd.chrdkregister.api.MoneyLoot moneyLoot(){return moneyLoot;}
    private long startedAt;
    private long ticks;

    @Override
    public void onEnable() {
        try {
            this.startedAt = System.currentTimeMillis();
            saveDefaultConfig();
            this.config = BossConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new BossStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new BossService(this, store, config);
            this.gui = new BossesGui(this);
            getServer().getPluginManager().registerEvents(new BossesListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            moneyLoot=new ru.cherdakmd.chrdkregister.api.MoneyLoot(this,()->coreApi,this::messages,"bosses-loot");
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("bosses", new BossesProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Bosses] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            getLogger().info("CHRDK_Bosses включён: боссов " + config.bossList().size()
                    + ", зон " + config.activeZones().size()
                    + ", авто-спавн " + (config.autoSpawn() ? "включён" : "выключен") + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Bosses не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if(moneyLoot!=null)moneyLoot.close();
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("bosses");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
            this.coreApi = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        if (service != null) {
            service.despawnAll();
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Bosses выключен.");
    }

    private void tick() {
        if (service == null) {
            return;
        }
        service.tick();
        ticks++;
        if (config.checkSeconds() > 0 && ticks % config.checkSeconds() == 0) {
            service.autoSpawn();
        }
    }

    /** Перечитывает конфиг, не ломая идущие бои. */
    public void reloadBosses() {
        reloadConfig();
        this.config = BossConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        service.applyConfig(config);
        gui.refresh();
    }

    private void registerCommands() {
        BossesCommandHandler handler = new BossesCommandHandler(this);
        for (String name : new String[]{"boss", "bossadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
                continue;
            }
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new BossesPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkbosses_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    /* --------------------------------- запись --------------------------------- */

    /** Помечает данные изменёнными: запись уйдёт через две секунды, вне главного потока. */
    public void requestSave() {
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
        } catch (RuntimeException error) {
            getLogger().warning("Не удалось сохранить bosses.json: " + error.getMessage());
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    public BossConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public BossStore store() {
        return store;
    }

    public BossService service() {
        return service;
    }

    public BossesGui gui() {
        return gui;
    }

    /** Когда плагин включился: по этому времени работает пауза первого спавна. */
    public long startedAt() {
        return startedAt;
    }
}
