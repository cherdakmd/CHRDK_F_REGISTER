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
 * через личный /bal с обычной предметной доплатой, уровень покупается из казны и поднимает только потолок
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
    private ru.cherdakmd.chrdkregister.api.PaidActions payments;
    private GuildFinance finance;
    private GuildPeaceController peace;
    private BukkitTask tickTask;
    private ChrdkRegisterApi coreApi;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = GuildConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new GuildStore(getDataFolder().toPath(), getLogger());
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 31) throw new IllegalStateException("Нужен Core API 31+");
            this.payments = new ru.cherdakmd.chrdkregister.api.PaidActions(this,this::coreApi,this::messages,"guilds");
            store.bind(payments.store());
            this.service = new GuildService(config, store);
            this.finance = new GuildFinance(this,payments);
            this.peace = new GuildPeaceController(this);
            this.store.combatGuard(peace::combatBlocked);
            Bukkit.getServicesManager().register(ru.cherdakmd.chrdkregister.api.PersonalDebtIndex.class,
                    peace::reserved,this,org.bukkit.plugin.ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(new GuildPeaceListener(this),this);
            this.gui = new GuildsGui(this);
            getServer().getPluginManager().registerEvents(new GuildsListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("guilds", new GuildsProfile(this));
                    coreApi.registerNameTag("guilds-tab", new GuildNameTag(this,true));
                    if (config.chatPrefix()) {
                        coreApi.registerNameTag("guilds", new GuildNameTag(this));
                    }
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Guilds] профиль и тег недоступны "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            Bukkit.getServicesManager().register(ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.class,
                    this::membership, this, org.bukkit.plugin.ServicePriority.Normal);
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

            getLogger().info("CHRDK_Guilds включён: гильдий " + store.count()
                    + ", войн " + store.wars().size()
                    + ", войны " + (config.warsEnabled() ? "разрешены" : "выключены") + ".");
        } catch (Exception | LinkageError exception) {
            getLogger().severe("CHRDK_Guilds не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("guilds");
                coreApi.unregisterNameTag("guilds");
                coreApi.unregisterNameTag("guilds-tab");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
            this.coreApi = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // Источник уже durable. Legacy guilds.json после импорта никогда не перезаписывается.
        getLogger().info("CHRDK_Guilds выключен.");
    }

    /** Один такт: срок приглашений, конец войн и объявления о них. */
    private void tick() {
        if (service == null || payments == null) return;
        payments.tick();
        if (payments.paused()) return;
        long now = System.currentTimeMillis();
        peace.tick(now);
        if(payments.paused())return;
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
        ru.cherdakmd.chrdkregister.api.EconomyTariffMigration.apply(this);
        this.config = GuildConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        this.service = new GuildService(config,store);
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

    private ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.Membership membership(String name) {
        if (name == null || coreApi == null || config == null || !config.enabled() || service == null
                || payments == null || payments.paused()) return null;
        java.util.UUID id = coreApi.knownBankPlayer(name);
        if (id == null) return null;
        Guild guild = service.guildOf(id);
        if (guild == null) return null;
        return new ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.Membership(
                guild.id(), guild.name(), guild.tag(), guild.rankOf(id).id());
    }

    boolean visible(org.bukkit.entity.Player player) {
        return player != null && GuildVisibility.allowed(config.enabled(), authenticated(player),
                player.hasPermission("chrdkguilds.use"));
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
        } catch (Exception | LinkageError exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    /* --------------------------------- запись --------------------------------- */

    /** Совместимость вызывающих UI: все изменения уже записаны до успешного GuildResult. */
    public void requestSave() { }

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

    GuildPeaceController peace() { return peace; }
    GuildFinance finance() { return finance; }
    public ru.cherdakmd.chrdkregister.api.PaidActions payments() { return payments; }
    public ChrdkRegisterApi coreApi() { return coreApi; }
    boolean authenticated(org.bukkit.entity.Player player) { return player != null && coreApi != null && coreApi.isAuthenticated(player); }
}
