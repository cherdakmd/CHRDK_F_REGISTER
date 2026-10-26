package ru.cherdakmd.chrdkranks;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CHRDK_Ranks — звания за наигранное время: лестница с церемонией вручения
 * и сортировка TAB-списка по стажу (чем дольше играешь — тем выше).
 */
public final class RanksPlugin extends JavaPlugin implements Listener {

    private volatile Messages messages;
    private volatile RanksConfig config;
    private PlaytimeStore store;
    private ChrdkRegisterApi coreApi;
    private RanksService service;
    private BukkitTask tickTask;

    /** Снимок конфига (неизменяемый для тикеров). */
    record RanksConfig(boolean sortTab, int tickSeconds, boolean announce,
                       List<RankLadder.Rank> ladder) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new PlaytimeStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.service = new RanksService(this);
        // 1.4.183: API ядра нужен уже при первой раскладке — иначе стартовый
        // порядок в табе соберётся без донатного ранга и «поедет» только
        // через минуту, на следующем тике.
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        reloadRanksConfig();
        if (coreApi != null) {
            try {
                coreApi.registerProfile("ranks", new RanksProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_Ranks] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand ranks = getCommand("ranks");
        if (ranks != null) {
            RanksCommandHandler handler = new RanksCommandHandler(this);
            ranks.setExecutor(handler);
            ranks.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("ranksadmin");
        if (admin != null) {
            RanksAdminCommandHandler handler = new RanksAdminCommandHandler(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }

        restartTicker();
        getLogger().info("CHRDK_Ranks включён: званий " + config.ladder().size()
                + ", TAB-сортировка " + (config.sortTab() ? "вкл" : "выкл") + ".");
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("ranks");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
            this.coreApi = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (service != null) {
            service.clearManagedTeams();
        }
        if (store != null) {
            store.save();
        }
        getLogger().info("CHRDK_Ranks выключен.");
    }

    /** Перечитать config.yml и переподнять сервисы. */
    @SuppressWarnings("unchecked")
    public void reloadRanksConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        ConfigurationSection section = getConfig().getConfigurationSection("ranks");
        List<RankLadder.Rank> ladder = List.of();
        if (section != null && section.getList("ladder") != null) {
            List<Map<?, ?>> raw = new ArrayList<>();
            for (Object entry : section.getList("ladder", List.of())) {
                if (entry instanceof Map<?, ?> map) {
                    raw.add(map);
                }
            }
            try {
                ladder = RankLadder.parse(raw);
            } catch (IllegalStateException exception) {
                getLogger().severe("[CHRDK_Ranks] Ошибка лестницы званий: " + exception.getMessage());
            }
        }
        this.config = new RanksConfig(
                getConfig().getBoolean("ranks.sort-tab", true),
                Math.max(10, getConfig().getInt("ranks.tick-seconds", 60)),
                getConfig().getBoolean("ranks.announce", true),
                ladder);
        restartTicker();
        if (service != null) {
            service.clearManagedTeams();
            service.refreshAllOnline();
        }
    }

    private void restartTicker() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (config == null) {
            return;
        }
        long period = config.tickSeconds() * 20L;
        tickTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> service.tick(config.tickSeconds()), period, period);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // тик спустя — стаж уже прочитан, скорборд готов
        Bukkit.getScheduler().runTask(this,
                () -> service.evaluate(player, store.secondsOf(player.getUniqueId())));
    }

    public Messages messages() {
        return messages;
    }

    /**
     * Ступень донатного ранга игрока (1.4.183).
     *
     * <p>Берётся у ядра: ранги — его данные, дублировать их в файле стажа
     * нельзя. Совсем старое ядро метода не знает — тогда ступень 0, и внутри
     * одного ранга сортировка идёт по стажу, как раньше.</p>
     *
     * @return 0 — ранга нет, 1..5 — ступень лестницы
     */
    public int rankLevel(Player player) {
        ChrdkRegisterApi api = coreApi;
        if (api == null || player == null) {
            return 0;
        }
        try {
            return Math.clamp(api.rankLevel(player.getName()), 0, RankLadder.MAX_RANK_LEVEL);
        } catch (RuntimeException | LinkageError ignored) {
            // Старое ядро или сбой реестра — сортировка по стажу остаётся.
            return 0;
        }
    }

    public RanksConfig config() {
        return config;
    }

    public PlaytimeStore store() {
        return store;
    }

    public RanksService service() {
        return service;
    }
}
