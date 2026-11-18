package ru.cherdakmd.chrdkanticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHRDK_Anticheat — мост античита (дизайн docs/design/ANTICHEAT.md).
 *
 * <p>Детекцию ведёт внешний Grim AC: его punishments.yml исполняет консольную
 * команду {@code chrdkac flag} на каждом срабатывании. Модуль пишет журнал
 * доказательств ({@code flags.jsonl} с ежедневной ротацией), алертит
 * онлайн-модераторам и даёт {@code /ac} для разбора. Наказания — штатные
 * {@code /pass} руками модератора: античит не банит и не кикает сам.</p>
 */
public final class AnticheatPlugin extends JavaPlugin {

    private Messages messages;
    private AnticheatConfig settings;
    private FlagJournal journal;
    private AlertThrottle throttle;
    private final Set<UUID> alertsDisabled = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.settings = AnticheatConfig.from(getConfig());
        try {
            this.journal = new FlagJournal(getDataFolder().toPath(), settings.keepDays(),
                    System::currentTimeMillis);
        } catch (IOException error) {
            getLogger().severe("Журнал античита недоступен, модуль остановлен: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.throttle = new AlertThrottle(settings.throttleSeconds() * 1000L);
        getCommand("chrdkac").setExecutor(new FlagCommand(this));
        getCommand("ac").setExecutor(new AcCommand(this));
    }

    /** Приём записи из команды детектора: журнал + алерты модераторам. */
    void onFlag(FlagRecord record) {
        if (!throttle.shouldAlert(record.player(), record.check(), record.time())) {
            return;
        }
        for (Player moderator : Bukkit.getOnlinePlayers()) {
            if (!moderator.hasPermission("chrdkanticheat.alerts")) {
                continue;
            }
            if (alertsDisabled.contains(moderator.getUniqueId())) {
                continue;
            }
            messages.send(moderator, "alert",
                    "player", record.player(),
                    "check", record.check(),
                    "vl", String.valueOf(record.vl()),
                    "ping", String.valueOf(record.ping()));
        }
    }

    /** Перечитывает настройки моста (горячие лимиты берутся заново). */
    void reloadSettings() {
        reloadConfig();
        this.settings = AnticheatConfig.from(getConfig());
        this.throttle = new AlertThrottle(settings.throttleSeconds() * 1000L);
    }

    Messages messages() {
        return messages;
    }

    AnticheatConfig settings() {
        return settings;
    }

    FlagJournal journal() {
        return journal;
    }

    Set<UUID> alertsDisabled() {
        return alertsDisabled;
    }
}
