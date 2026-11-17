package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Generator — личный генератор ресурсов (п.31): физическая камера с
 * лавой и водой, тиры производства и модули улучшений.
 *
 * <p>Скелет модуля: каркас, конфигурация, математика (циклы, бункер, лимиты
 * выдачи, множитель перерождений) и хранилище {@code generator.json}.
 * Механика мира (ядро, ячейки, GUI) — следующими шагами по дизайну
 * {@code docs/design/GENERATOR.md}.</p>
 */
public final class GeneratorPlugin extends JavaPlugin {

    private Messages messages;
    private ChrdkRegisterApi coreApi;
    private GeneratorStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (coreApi == null) {
            getLogger().severe("CHRDK_Core не найден: модуль остановлен без данных");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.store = new GeneratorStore(getDataFolder(), getLogger());
        this.store.load();
        try {
            this.store.markRegistered();
        } catch (Exception exception) {
            getLogger().severe("CHRDK_Generator: generator.json не принят диском: " + exception.getMessage());
        }
        PluginCommand command = getCommand("generator");
        if (command != null) {
            GeneratorCommand handler = new GeneratorCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("generatoradmin");
        if (admin != null) {
            GeneratorAdminCommand handler = new GeneratorAdminCommand(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }
        getLogger().info("CHRDK_Generator включён: скелет п.31 (математика, конфигурация, generator.json).");
    }

    @Override
    public void onDisable() {
        if (this.store != null) {
            this.store.saveNow();
            this.store = null;
        }
        this.messages = null;
        this.coreApi = null;
    }

    public Messages messages() {
        return messages;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public GeneratorStore store() {
        return store;
    }
}
