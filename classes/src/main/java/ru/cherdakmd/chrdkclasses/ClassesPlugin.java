package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Classes — четыре класса и деревья талантов поверх навыков (1.4.164).
 *
 * <p>Класс не качается отдельно: уровень выводится из суммы уровней десяти
 * навыков, а очки — «выданные минус потраченные». Поэтому новый счётчик
 * прогресса не появляется, а класс и прокачка не могут разъехаться.</p>
 *
 * <p>Эффекты разделены на два вида. Атрибутные (здоровье, броня, скорость,
 * сопротивление отбрасыванию) висят транзитными модификаторами и обновляются
 * при входе, возрождении и изменении дерева. Урон, лечение и насыщение
 * считаются в событиях и пульсе: их нельзя выразить атрибутом без побочных
 * эффектов на другие аддоны.</p>
 */
public final class ClassesPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile ClassConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private ClassStore store;
    private TalentService service;
    private SkillsBridge skillsBridge;
    private ClassEffects effects;
    private ClassesGui gui;
    private BukkitTask saveTask;
    private BukkitTask pulseTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = ClassConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new ClassStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.skillsBridge = new SkillsBridge(this);
            this.service = new TalentService(config, store, skillsBridge);
            this.effects = new ClassEffects(this);
            this.gui = new ClassesGui(this);

            getServer().getPluginManager().registerEvents(new ClassListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            registerProfile();

            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            this.pulseTask = getServer().getScheduler().runTaskTimer(this,
                    this::pulse, 100L, config.pulseTicks());

            getLogger().info("CHRDK_Classes включён: классов " + ClassCatalog.classes().size()
                    + ", талантов " + (ClassCatalog.classes().size()
                    * ClassCatalog.TALENTS_PER_CLASS)
                    + ", навыки: " + (skillsBridge.available() ? "да" : "нет"));
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Classes не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("classes");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
            this.coreApi = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        if (effects != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                effects.clear(player);
            }
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Classes выключен.");
    }

    /** Перечитывает конфиг и тексты, не трогая прогресс игроков. */
    public void reloadClasses() {
        reloadConfig();
        this.config = ClassConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        service.applyConfig(config);
        // Навыки могли включиться после старта — ищем сервис заново.
        skillsBridge.reset();
        for (Player player : Bukkit.getOnlinePlayers()) {
            effects.apply(player);
        }
    }

    /** Один такт: лечение и насыщение вне боя. */
    private void pulse() {
        if (!config.enabled() || !config.effectsEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            effects.pulse(player, now);
        }
    }

    private void registerCommands() {
        ClassesCommandHandler handler = new ClassesCommandHandler(this);
        for (String name : new String[]{"class", "classadmin"}) {
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
            new ClassesPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkclasses_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    private void registerProfile() {
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (coreApi == null) {
            return;
        }
        try {
            coreApi.registerProfile("classes", new ClassesProfile(this));
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("[CHRDK_Classes] раздел профиля недоступен "
                    + "(нужно ядро 1.4.156+): " + error);
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
            getLogger().warning("Не удалось сохранить classes.json: " + error.getMessage());
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    public ClassConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public ClassStore store() {
        return store;
    }

    public TalentService service() {
        return service;
    }

    public SkillsBridge skillsBridge() {
        return skillsBridge;
    }

    public ClassEffects effects() {
        return effects;
    }

    public ClassesGui gui() {
        return gui;
    }
}
