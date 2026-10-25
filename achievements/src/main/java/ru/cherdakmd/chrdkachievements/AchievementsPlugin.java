package ru.cherdakmd.chrdkachievements;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Achievements — 72 достижения в четырёх разделах: путь, бой, ремесло и
 * сообщество (по 18 в каждом). Прогресс идёт из ванильных событий (блоки, мобы, рыбалка, крафт,
 * чат, дистанция) и из API ядра (ранг, регистрация, 2FA); награды — дни
 * ранга и алмазы, бейджи оформляют ник через {@code NameTagProvider}.
 *
 * <p>Жёстко зависит только от ядра. Данные — {@code achievements.json}:
 * прогресс по UUID, открытия, выданные награды и выбранный бейдж. Запись
 * асинхронная, раз в 5 секунд и принудительно при выключении.</p>
 */
public final class AchievementsPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile ChrdkRegisterApi coreApi;
    private volatile AchievementsConfig config;
    private AchievementStore store;
    private AchievementService service;
    private AchievementListener listener;
    private AchievementGui gui;
    private BukkitTask saveTask;
    private BukkitTask tickTask;
    /** Секунды с включения — по ним считаются время игры и опрос API. */
    private int seconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new AchievementStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.service = new AchievementService(this, store);
        this.listener = new AchievementListener(this, service);
        this.gui = new AchievementGui(this, service);
        reloadAchievementsConfig();

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        AchievementCommandHandler commands = new AchievementCommandHandler(this, service, gui);
        PluginCommand achievements = getCommand("achievements");
        PluginCommand admin = getCommand("achadmin");
        if (achievements != null) {
            achievements.setExecutor(commands);
            achievements.setTabCompleter(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }

        // Провайдер ников: бейдж ставится перед уже оформленным именем.
        ChrdkRegisterApi api = coreApi;
        if (api != null) {
            api.registerNameTag("achievements", (playerName, decoratedName) -> {
                if (playerName == null || decoratedName == null) {
                    return decoratedName;
                }
                Player online = Bukkit.getPlayerExact(playerName);
                if (online == null) {
                    return decoratedName;
                }
                String badge = service.cachedBadge(online.getUniqueId());
                return badge == null ? decoratedName : badge + " " + decoratedName;
            });
        }

        if (coreApi != null) {
            try {
                coreApi.registerProfile("achievements", new AchievementsProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_Achievements] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }

        registerApi();
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::saveState, 100L, 100L);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        // Достижения, накопленные до установки аддона, пересчитываются при входе.
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleSocialCheck(player);
        }

        getLogger().info("CHRDK_Achievements включён: достижений " + AchievementRegistry.size()
                + " (путь " + AchievementRegistry.byCategory(AchievementDef.Category.PATH).size()
                + ", бой " + AchievementRegistry.byCategory(AchievementDef.Category.COMBAT).size()
                + ", ремесло " + AchievementRegistry.byCategory(AchievementDef.Category.CRAFT).size()
                + ", сообщество "
                + AchievementRegistry.byCategory(AchievementDef.Category.SOCIAL).size() + ").");
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("achievements");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        ChrdkRegisterApi api = coreApi;
        if (api != null) {
            api.unregisterNameTag("achievements");
        }
        if (listener != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                listener.flushDistance(player);
            }
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Achievements выключен.");
    }

    /** Перечитывает config.yml/messages.yml, сохраняя прогресс и бейджи. */
    public void reloadAchievementsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"),
                getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = AchievementsConfig.parse(getConfig(), getLogger());
        if (store != null) {
            // Бейджи могли измениться в messages.yml — обновляем кэш ников.
            for (Player player : Bukkit.getOnlinePlayers()) {
                service.refreshBadge(player);
            }
        }
    }

    /**
     * Секундный пульс: накопленная дистанция, время игры и раз в пять минут —
     * опрос социальных достижений через API ядра.
     */
    private void tick() {
        seconds++;
        int refresh = Math.max(1, config.timeTickSeconds());
        boolean social = seconds % 60 == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            // Дистанция сбрасывается каждую секунду: буфер не растёт.
            listener.flushDistance(player);
            if (social) {
                listener.checkSocial(player);
            }
        }
        if (seconds % refresh == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                service.addProgress(player, AchievementMetric.PLAY_SECONDS, refresh);
            }
        }
    }

    private void scheduleSocialCheck(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                listener.checkSocial(player);
                service.refreshBadge(player);
            }
        }, 60L);
    }

    private void saveState() {
        try {
            if (store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить достижения: " + exception.getMessage());
        }
    }

    public Messages messages() {
        return messages;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public AchievementsConfig config() {
        return config;
    }

    public AchievementStore store() {
        return store;
    }

    public AchievementService service() {
        return service;
    }

    /** Регистрация публичного API для других аддонов (в этом релизе — задел). */
    public void registerApi() {
        Bukkit.getServicesManager().register(ChrdkAchievementsApi.class,
                new AchievementsApiImpl(service), this, ServicePriority.Normal);
    }

    /** Тонкая обёртка, чтобы аддоны не зависели от внутренних классов. */
    private record AchievementsApiImpl(AchievementService service) implements ChrdkAchievementsApi {

        @Override
        public int addProgress(Player player, String metricId, long amount) {
            AchievementMetric metric = AchievementMetric.byId(metricId);
            return metric == null ? 0 : service.addProgress(player, metric, amount);
        }

        @Override
        public int unlockedCount(Player player) {
            return service.unlockedCount(player);
        }

        @Override
        public boolean isUnlocked(Player player, String achievementId) {
            return service.isUnlocked(player, achievementId);
        }

        @Override
        public boolean grant(Player player, String achievementId) {
            AchievementDef def = AchievementRegistry.byId(achievementId);
            return def != null && service.grant(player, def);
        }
    }
}
