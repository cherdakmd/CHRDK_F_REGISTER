package ru.cherdakmd.chrdkgoals;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CHRDK_CommonGoals — кооперативные общие цели сервера: волна целей (по умолчанию 4 шт /
 * неделя), прогресс вносит каждый авторизованный игрок, по завершении пул алмазов делится
 * между участниками пропорционально вкладу (оффлайн — в pending, выдача /goals claim).
 */
public final class CommonGoalsPlugin extends JavaPlugin implements Listener {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60; // минута

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile GoalsConfig config;
    private GoalStore store;
    private GoalsService service;
    private BukkitTask lifecycle;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new GoalStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.service = new GoalsService(this);
        reloadGoalsConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 16 || !store.healthy())
                throw new IllegalStateException("Нужен Core API 16+ и исправный источник целей");
            store.enableBank();
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Цели остановлены без сброса данных", error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }

        Bukkit.getPluginManager().registerEvents(new GoalListeners(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        GoalCommandHandler commands = new GoalCommandHandler(this);
        PluginCommand goals = getCommand("goals");
        PluginCommand admin = getCommand("goalsadmin");
        if (goals != null) {
            goals.setExecutor(commands);
            goals.setTabCompleter(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }

        lifecycle = Bukkit.getScheduler().runTaskTimer(this, () -> { ensureWave(); service.settleRewards(); }, 100L, 1200L);
        saveTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> store.saveAsync(this), SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
        getLogger().info("CHRDK_CommonGoals включён: волна целей до "
                + new java.util.Date(store.rotationEndsAt()) + ", активных: " + store.goals().size());
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) {
            lifecycle.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (store != null) {
            store.markDirty();
            store.save();
        }
        getLogger().info("CHRDK_CommonGoals выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadGoalsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = GoalsConfig.parse(getConfig(), getLogger());
    }

    /** Гарантирует активную волну целей: если срок вышел — ротация. */
    private void ensureWave() {
        if (store.needRotation(System.currentTimeMillis())) {
            rotateNow();
        }
    }

    /** Принудительная ротация (новая волна + рассылка). */
    public void rotateNow() {
        List<GoalState> wave = config.rollWave(new java.util.Random());
        long endsAt = System.currentTimeMillis()
                + Duration.ofDays(config.periodDays()).toMillis();
        store.replaceGoals(wave, endsAt);
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages().send(player, "rotated");
        }
        store.markDirty();
        store.saveAsync(this);
    }

    /** Отложенная ротация через N тиков (после полного завершения всех целей волны). */
    public void rotate(long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, this::rotateNow, delayTicks);
    }

    /** После полной авторизации напоминаем про незабранные награды. */
    @EventHandler
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        if (store.entitlementOf(player.getName()) > 0) service.claimRewards(player);
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public GoalsConfig config() {
        return config;
    }

    public GoalStore store() {
        return store;
    }

    public GoalsService service() {
        return service;
    }
}
