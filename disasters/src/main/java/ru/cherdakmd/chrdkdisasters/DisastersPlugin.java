package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import ru.cherdakmd.chrdkregister.Messages;

/**
 * CHRDK_Disasters — собственные катастрофы CHRDK, написанные с нуля под
 * Purpur 26.3 и наш набор плагинов. Код, тексты и конфиги оригинальные (MIT),
 * сторонний GPL-плагин DeadlyDisasters не копируется и не поставляется.
 *
 * <p>Принципы второй итерации (шесть катастроф):</p>
 * <ul>
 *   <li>всё выключено по умолчанию: модуль, миры и площадные разрушительные
 *       катастрофы включаются владельцем явно;</li>
 *   <li>каждый запуск проходит фазы предупреждения, активных тиков и
 *       затухания; реестр активных событий доступен через
 *       {@code /disastersadmin list} и {@code stop <id>};</li>
 *   <li>приваты CHRDK_Claims, спавн и хранилища игроков проверяются ДО
 *       изменения блока, а не через Bukkit-события;</li>
 *   <li>изменённые блоки попадают в снимок и возвращаются по таймеру, при
 *       reload и при выключении плагина;</li>
 *   <li>звуки и мягкие эффекты состояния не наносят урона и снимаются на
 *       затухании; оповещения делят общий кулдаун, как в CHRDK_VK;</li>
 *   <li>никаких NMS, взрывов и {@code BlockBreakEvent}: CHRDK_Skills не видит
 *       катастрофу как добычу и не начисляет опыт/алмазы.</li>
 * </ul>
 */
public final class DisastersPlugin extends JavaPlugin {

    private volatile DisastersConfig settings;
    private volatile Messages messages;
    private volatile boolean runtimeEnabled = true;
    private DisasterProtection protection;
    private DisasterRestore restore;
    private DisasterScheduler scheduler;
    private DisasterEffects effects;
    private DisasterBroadcaster broadcaster;
    private DisasterEngine engine;
    private DisasterProgressStore progress;
    private DisasterRewards rewards;
    private DisasterShop shop;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        this.progress = new DisasterProgressStore(getDataFolder().toPath(), getLogger());
        this.progress.load();
        this.rewards = new DisasterRewards(this);
        this.shop = new DisasterShop(this);
        Bukkit.getPluginManager().registerEvents(shop, this);
        reloadAll();
        DisasterCommandHandler handler = new DisasterCommandHandler(this);
        PluginCommand player = getCommand("disasters");
        PluginCommand admin = getCommand("disastersadmin");
        if (player != null) {
            player.setExecutor(handler);
        }
        if (admin != null) {
            admin.setExecutor(handler);
        }
        getLogger().info("[CHRDK_Disasters] модуль загружен, активен: "
                + (settings.enabled() ? "да" : "нет") + "; катастроф разрешено: "
                + settings.enabledKinds().size() + "; приваты CHRDK_Claims: "
                + (settings.respectClaims() ? "учитываются" : "игнорируются")
                + "; фаз " + settings.phases().totalTicks() + " по "
                + settings.phases().tickSeconds() + " с");
    }

    /** Перечитывает YAML и тексты, не теряя снимок изменённых блоков. */
    public void reloadAll() {
        if (engine != null) {
            engine.stop();
        }
        if (restore != null) {
            restore.restoreAll();
        }
        reloadConfig();
        FileConfiguration yaml = getConfig();
        this.settings = DisastersConfig.parse(yaml);
        this.messages = Messages.load(this, settings.locale(), getLogger());
        this.protection = new DisasterProtection(this);
        this.restore = new DisasterRestore(settings.maxBlocks(), getLogger());
        this.scheduler = new DisasterScheduler(settings.minMillis(), settings.maxMillis(),
                settings.maxLevel(), settings.enabledKinds(), System.currentTimeMillis());
        this.effects = new DisasterEffects(this);
        this.broadcaster = new DisasterBroadcaster(this);
        this.engine = new DisasterEngine(this);
        this.engine.start();
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stop();
        }
        if (restore != null) {
            restore.restoreAll();
        }
        if (progress != null) {
            progress.saveNow();
        }
    }

    public DisastersConfig settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public DisasterProtection protection() {
        return protection;
    }

    public DisasterRestore restore() {
        return restore;
    }

    public DisasterScheduler scheduler() {
        return scheduler;
    }

    public DisasterEffects effects() {
        return effects;
    }

    public DisasterBroadcaster broadcaster() {
        return broadcaster;
    }

    public DisasterEngine engine() {
        return engine;
    }

    public DisasterProgressStore progress() {
        return progress;
    }

    public DisasterRewards rewards() {
        return rewards;
    }

    public DisasterShop shop() {
        return shop;
    }

    /** Runtime-переключатель /disastersadmin on|off: в YAML ничего не пишется. */
    public boolean runtimeEnabled() {
        return runtimeEnabled;
    }

    public void setRuntimeEnabled(boolean value) {
        this.runtimeEnabled = value;
    }
}
