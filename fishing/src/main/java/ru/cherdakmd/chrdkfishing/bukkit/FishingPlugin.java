package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicitly inert bootstrap until all integration and item safeguards are complete. */
public final class FishingPlugin extends JavaPlugin {
    @Override public void onEnable() {
        registerStagedListener(this, getServer().getPluginManager());
        getLogger().warning("CHRDK_Fishing: обработчик зарегистрирован, но кастомная рыба отключена: интеграция Core/Skills и транзакции не завершены. Этот JAR не является релизом.");
    }

    static FishCatchRouter registerStagedListener(Plugin plugin, PluginManager manager) {
        // The actual delegate cannot be switched on by config or a command.
        // Replace this only after integration, recovery and live-order tests pass.
        var router = new FishCatchRouter(() -> null);
        manager.registerEvents(router, plugin);
        return router;
    }
}
