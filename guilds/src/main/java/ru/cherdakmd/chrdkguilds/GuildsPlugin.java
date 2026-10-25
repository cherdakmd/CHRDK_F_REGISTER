package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;

/**
 * CHRDK_Guilds — гильдии, казна и войны (1.4.163).
 *
 * <p>Гильдия — это не чат с тегом, а общая казна и общая война. Взносы идут
 * алмазами из инвентаря, уровень покупается из казны и поднимает только потолок
 * состава, а войны — это ставки и время: обе стороны кладут равную ставку, и
 * победитель по очкам забирает обе. Силы в бою гильдия не даёт — иначе она
 * стала бы способом купить преимущество.</p>
 *
 * <p>Приваты затрагиваются только одним правилом: бой на занятой территории
 * стоит вдвое больше очков. Никакой чужой код при этом не меняется — модуль
 * читает индекс приватов через {@link SpawnClaimIndex}.</p>
 */
public final class GuildsPlugin extends JavaPlugin {

    private GuildConfig config;
    private Messages messages;
    private GuildStore store;
    private GuildService service;
    private GuildsGui gui;
    private GuildPurse purse;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = GuildConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new GuildStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new GuildService(config, store);
            this.purse = new DiamondPurse();
            this.gui = new GuildsGui(this);
            getServer().getPluginManager().registerEvents(new GuildsListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("guilds", new GuildsProfile(this));
                    if (config.chatPrefix()) {
                        coreApi.registerNameTag("guilds", new GuildNameTag(this));
                    }
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Guilds] профиль и тег недоступны "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            getLogger().info("CHRDK_Guilds включён: гильдий " + store.count()
                    + ", войн " + store.wars().size()
                    + ", войны " + (config.warsEnabled() ? "разрешены" : "выключены") + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Guilds не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("guilds");
                coreApi.unregisterNameTag("guilds");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
            this.coreApi = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Guilds выключен.");
    }

    /** Один такт: срок приглашений, конец войн и объявления о них. */
    private void tick() {
        if (service == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (GuildResult notice : service.tick(now)) {
            if (notice.key().isEmpty()) {
                continue;
            }
            Bukkit.getServer().broadcast(messages.get(notice.key(), notice.array()));
            requestSave();
        }
    }

    /** Перечитывает конфиг; состав и войны остаются. */
    public void reloadGuilds() {
        reloadConfig();
        this.config = GuildConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        gui.refresh();
    }

    /**
     * Внутри ли привата точка.
     *
     * <p>Индекс приватов регистрирует CHRDK_Claims. Если аддона нет, правило
     * «бой на приватах вдвое дороже» просто не работает — ошибок не будет.</p>
     */
    public boolean claimed(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        SpawnClaimIndex index = Bukkit.getServicesManager().load(SpawnClaimIndex.class);
        if (index == null) {
            return false;
        }
        try {
            return index.isClaimed(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockZ());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void registerCommands() {
        GuildsCommandHandler handler = new GuildsCommandHandler(this);
        for (String name : new String[]{"guild", "gchat", "guildadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — пропущена.");
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
            new GuildsPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkguilds_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
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
            getLogger().warning("Не удалось сохранить guilds.json: " + error.getMessage());
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    public GuildConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public GuildStore store() {
        return store;
    }

    public GuildService service() {
        return service;
    }

    public GuildsGui gui() {
        return gui;
    }

    public GuildPurse purse() {
        return purse;
    }
}
