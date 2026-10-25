package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.UUID;

/**
 * CHRDK_Pets — уровни, клички и бонусы для уже приручённых ванильных кошек и
 * волков.
 *
 * <p>Модуль не спавнит питомцев: он подбирает существующих и ведёт их прогресс
 * в {@code pets.json}. Поэтому установка не меняет мир, удаление не оставляет
 * за собой сущностей, а прогресс не теряется при выключении аддона.</p>
 */
public final class PetsPlugin extends JavaPlugin {

    private PetsConfig config;
    private ChrdkRegisterApi coreApi;
    private Messages messages;
    private PetStore store;
    private PetService service;
    private PetAura aura;
    private BukkitTask refreshTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = PetsConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new PetStore(getDataFolder().toPath(), getLogger());
            store.load();
            int purged = store.purgeDead(config.keepDeadDays(), System.currentTimeMillis());
            if (purged > 0) {
                getLogger().info("Удалено записей о погибших питомцах: " + purged);
            }
            this.service = new PetService(this);
            this.aura = new PetAura(this);
            getServer().getPluginManager().registerEvents(new PetListener(this), this);
            registerCommand("pet");
            registerCommand("petsadmin");
            getServer().getServicesManager().register(PetsApi.class, new PetsApiImpl(this),
                    this, ServicePriority.Normal);
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("pets", new PetsProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Pets] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            int adopted = service.adoptLoaded();
            scheduleTasks();
            getLogger().info("CHRDK_Pets включён: питомцев в базе " + store.size()
                    + ", подобрано при старте " + adopted + ", максимум уровня "
                    + config.curve().maxLevel() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Pets не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("pets");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
            this.coreApi = null;
        }
        cancelTasks();
        if (aura != null) {
            aura.clearAll();
        }
        if (service != null) {
            int released = service.releaseBonuses();
            if (released > 0) {
                getLogger().info("Снято бонусов с питомцев: " + released + ".");
            }
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Pets выключен.");
    }

    /** Перечитывает config.yml и messages.yml, пересобирает пульс и ауру. */
    public int reloadAll() {
        cancelTasks();
        reloadConfig();
        this.config = PetsConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        int adopted = service == null ? 0 : service.adoptLoaded();
        scheduleTasks();
        if (aura != null) {
            aura.refreshAll();
        }
        return adopted;
    }

    private void registerCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
            return;
        }
        PetCommandHandler handler = new PetCommandHandler(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private void scheduleTasks() {
        this.refreshTask = getServer().getScheduler().runTaskTimer(this,
                () -> {
                    try {
                        aura.refreshAll();
                    } catch (RuntimeException exception) {
                        getLogger().warning("Ошибка ауры питомцев: " + exception.getMessage());
                    }
                }, 20L, Math.max(10, config.refreshTicks()));
        long period = Math.max(5, config.saveIntervalSeconds()) * 20L;
        this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                this::saveNow, period, period);
    }

    private void cancelTasks() {
        if (refreshTask != null) {
            refreshTask.cancel();
            this.refreshTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            this.saveTask = null;
        }
    }

    private void saveNow() {
        try {
            if (store != null && store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить pets.json: " + exception.getMessage());
        }
    }

    /** Уровень питомца по UUID; 0 — записи нет. */
    public int levelOf(UUID petId) {
        PetStore.PetData data = store.get(petId);
        return data == null ? 0 : PetCurve.levelFor(data.xp(), config.curve());
    }

    public PetsConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public PetStore store() {
        return store;
    }

    public PetService service() {
        return service;
    }

    public PetAura aura() {
        return aura;
    }
}
