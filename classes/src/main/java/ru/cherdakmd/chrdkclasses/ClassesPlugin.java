package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Четыре класса с собственным PvE-XP, ранговыми деревьями и двумя выбранными умениями.
 * Атрибуты применяются транзитными модификаторами, урон — в событиях,
 * лечение и насыщение — пульсом. Запись упорядочена и выполняется асинхронно.
 */
public final class ClassesPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile ClassConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private ClassStore store;
    private TalentService service;
    private SkillsBridge skillsBridge;
    private RanksBridge ranksBridge;
    private AchievementsBridge achievementsBridge;
    private ClassEffects effects;
    private AbilityService abilities;
    private ClassTag tag;
    private final ClassProgress progress = new ClassProgress();
    private ClassesGui gui;
    private DevelopmentGui developmentGui;
    private PveProgressListener gameplay;
    ChrdkRegisterApi coreApi() { return coreApi; }
    DevelopmentGui developmentGui() { return developmentGui; }
    PveProgressListener gameplay() { return gameplay; }
    boolean ready(Player player) {
        if (player == null || coreApi == null || !coreApi.isAuthenticated(player)
                || !config.enabled()) return false;
        boolean ready = service.development().prepare(player.getUniqueId(), System.currentTimeMillis());
        if (ready && store.isDirty()) requestSave();
        return ready;
    }
    /** 1.4.182: обязательный выбор класса — меню и напоминания. */
    private ClassOnboarding onboarding;
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
            this.ranksBridge = new RanksBridge(this);
            this.achievementsBridge = new AchievementsBridge(this);
            this.service = new TalentService(config, store, skillsBridge, ranksBridge,
                    achievementsBridge);
            service.enableDevelopment(LegacyRulesFile.loadOrCreate(getDataFolder().toPath(),config,getLogger()));
            this.developmentGui = new DevelopmentGui(this);
            this.gameplay = new PveProgressListener(this);
            this.effects = new ClassEffects(this);
            this.abilities = new AbilityService(this);
            this.tag = new ClassTag(this);
            this.gui = new ClassesGui(this);
            this.onboarding = new ClassOnboarding(this);

            getServer().getPluginManager().registerEvents(new ClassListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(onboarding, this);
            getServer().getPluginManager().registerEvents(developmentGui, this);
            getServer().getPluginManager().registerEvents(gameplay, this);
            registerCommands();
            registerPlaceholders();
            registerProfile();

            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            this.pulseTask = getServer().getScheduler().runTaskTimer(this,
                    this::pulse, 100L, config.pulseTicks());
            onboarding.start();

            warnMissingSections();
            getLogger().info("CHRDK_Classes включён: классов " + ClassCatalog.classes().size()
                    + ", талантов " + (ClassCatalog.classes().size()
                    * ClassTrees.PER_CLASS)
                    + ", навыки: " + (skillsBridge.available() ? "да" : "нет")
                    + ", достижения: " + (achievementsBridge.available() ? "да" : "нет")
                    + ", ранги: " + (ranksBridge.available() ? "да" : "нет"));
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
                coreApi.unregisterNameTag(ClassTag.ID);
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
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
        if (onboarding != null) {
            onboarding.stop();
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
        // Навыки, ранги и достижения могли включиться после старта — ищем сервисы заново.
        skillsBridge.reset();
        ranksBridge.reset();
        achievementsBridge.reset();
        for (Player player : Bukkit.getOnlinePlayers()) {
            effects.apply(player);
        }
        if (onboarding != null) {
            onboarding.start();
        }
        warnMissingSections();
    }

    /**
     * Подсказывает, что новых ключей нет в старом {@code config.yml}.
     *
     * <p>Bukkit не дописывает ключи в файл: сервер, обновившийся с 1.4.172,
     * работает на значениях по умолчанию, но админ должен знать, почему он не
     * видит секцию. Без этой строки «в конфиге нет умений» выглядит как баг.</p>
     */
    private void warnMissingSections() {
        List<String> missing = new ArrayList<>();
        if (!getConfig().isSet("abilities")) {
            missing.add("abilities");
        }
        if (!getConfig().isSet("general.level-notice")) {
            missing.add("general.level-notice");
        }
        if (!getConfig().isSet("tag")) {
            missing.add("tag");
        }
        if (!getConfig().isSet("points")) {
            missing.add("points");
        }
        if (!getConfig().isSet("ranks")) {
            missing.add("ranks");
        }
        if (!getConfig().isSet("onboarding")) {
            missing.add("onboarding");
        }
        if (!missing.isEmpty()) {
            getLogger().info("[CHRDK_Classes] В config.yml нет ключей " + String.join(", ", missing)
                    + " — работают значения по умолчанию (образец лежит в jar: config.yml).");
        }
    }

    /**
     * Один такт: лечение и насыщение вне боя плюс проверка уровня класса.
     *
     * <p>Уровень вычисляется из собственного XP. Повышение ловится
     * сравнением с прошлым пульсом: без
     * этого игрок узнавал о новых очках таланта случайно.</p>
     */
    private void pulse() {
        if (!config.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean applyEffects = config.effectsEnabled();
        boolean notice = config.levelNotice();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!ready(player)) continue;
            if (applyEffects) {
                effects.pulse(player, now);
            }
            if (notice) {
                checkLevel(player);
            }
        }
    }

    /** Сообщает о повышении уровня класса, если он вырос с прошлого пульса. */
    private void checkLevel(Player player) {
        PlayerClass playerClass = service.classOf(player.getUniqueId());
        if (playerClass == null) {
            progress.forget(player.getUniqueId());
            return;
        }
        int level = service.level(player.getUniqueId());
        int points = service.availablePoints(player.getUniqueId());
        ClassProgress.Change change = progress.observe(player.getUniqueId(), level, points);
        if (change == null) {
            return;
        }
        messages().send(player, "level-up",
                "level", String.valueOf(change.level()),
                "points", String.valueOf(change.points()));
        if (config.actionbar()) {
            player.sendActionBar(messages().get("level-up-actionbar",
                    "level", String.valueOf(change.level())));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.35f);
    }

    private void registerCommands() {
        ClassesCommandHandler handler = new ClassesCommandHandler(this);
        for (String name : new String[]{"class", "classadmin", "ability"}) {
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
        try {
            coreApi.registerNameTag(ClassTag.ID, tag::decorate);
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("[CHRDK_Classes] метка класса в нике недоступна "
                    + "(нужно ядро с API v6): " + error);
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

    /** Мост рангов: нужен команде, чтобы показать источник плюшек. */
    RanksBridge ranksBridge() {
        return ranksBridge;
    }

    /** Мост достижений: {@code null} или недоступен — бонусных очков нет. */
    AchievementsBridge achievements() {
        return achievementsBridge;
    }

    public ClassEffects effects() {
        return effects;
    }

    public AbilityService abilities() {
        return abilities;
    }

    public ClassProgress progress() {
        return progress;
    }

    public ClassTag tag() {
        return tag;
    }

    public ClassesGui gui() {
        return gui;
    }
}
