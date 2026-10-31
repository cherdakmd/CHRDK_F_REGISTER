package ru.cherdakmd.chrdkvk;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * Аддон {@code CHRDK_VK} — интеграция сервера с сообществом ВКонтакте:
 * привязка аккаунта по одноразовому коду, Bots Long Poll API, уведомления о входах
 * и переборе пароля, экстренный сброс сессий и заморозка входа (без смены пароля),
 * бонусы подписки и VK Donut, а также анонсы записей стены сообщества.
 */
public final class VkPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile VkConfig config;
    private volatile Messages messages;
    private VkStore store;
    private VkService service;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.config = VkConfig.parse(getConfig());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (this.coreApi == null) {
            getLogger().warning("[CHRDK_VK] Сервис ChrdkRegisterApi не найден — проверьте порядок запуска CHRDK_Core.");
        }

        this.store = new VkStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.service = new VkService(this, store);
        this.service.start();
        getServer().getPluginManager().registerEvents(this.service, this);

        VkCommandHandler handler = new VkCommandHandler(this, service);
        for (String cmd : List.of("vk", "vkadmin")) {
            PluginCommand pluginCommand = getCommand(cmd);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(handler);
                pluginCommand.setTabCompleter(handler);
            }
        }

        // Периодическая запись грязного состояния раз в 30 секунд.
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, () -> store.saveIfDirty(), 20L * 30, 20L * 30);

        getLogger().info("[CHRDK_VK] Включён (привязок: " + store.linkCount()
                + ", API настроен: " + config.apiConfigured() + ").");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
        if (store != null) {
            store.saveNow();
        }
        this.coreApi = null;
    }

    /** Атомарная перезагрузка {@code config.yml} и {@code messages.yml} без полуобновлённого состояния. */
    public boolean reloadBundle() {
        Path folder = getDataFolder().toPath();
        try {
            ConfigSnapshot<VkConfig> snapshot = ConfigSnapshot.load(
                    folder.resolve("config.yml"),
                    getResource("config.yml"),
                    (yaml, log) -> VkConfig.parse(yaml),
                    (yaml, log) -> Messages.fromChecked(
                            getResource("messages.yml"), folder.resolve("messages.yml"), log)
            );
            this.config = snapshot.value();
            this.messages = snapshot.messages();
            if (this.service != null) {
                this.service.applyRuntimeConfig();
            }
            return true;
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "[CHRDK_VK] Ошибка перезагрузки конфигурации", exception);
            return false;
        }
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public VkConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }
}
