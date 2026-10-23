package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.Locale;

/**
 * CHRDK_Skills — десять навыков с уровнями 1..100 и 60 перками.
 *
 * <p>Опыт идёт из честных событий: разрушение блоков, бой, рыбалка, варка и
 * зачарование. Прогресс живёт в {@code skills.json} по UUID, уровень всегда
 * выводится из опыта — поэтому смена кривой в конфиге не требует миграции.</p>
 *
 * <p>Атрибутные перки (скорость копания, броня) висят транзитными
 * модификаторами и обновляются пульсом раз в {@code general.refresh-ticks}:
 * модификаторы не попадают в playerdata и не переживают перезаход, поэтому
 * потеря после выхода из игры лечится сама.</p>
 */
public final class SkillsPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile SkillsConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private SkillStore store;
    private SkillService service;
    private SkillEffects effects;
    private SkillListener listener;
    private SkillsGui gui;
    private BukkitTask saveTask;
    private BukkitTask effectTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new SkillStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        // Конфиг читается до создания листенера: антинакрутка берёт из него TTL.
        reloadSkillsConfig();
        this.service = new SkillService(this, store);
        this.effects = new SkillEffects(this);
        this.listener = new SkillListener(this, service);
        this.gui = new SkillsGui(this, service);

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        SkillCommandHandler commands = new SkillCommandHandler(this, service, gui);
        PluginCommand skills = getCommand("skills");
        PluginCommand admin = getCommand("skillsadmin");
        if (skills != null) {
            skills.setExecutor(commands);
            skills.setTabCompleter(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }

        if (coreApi != null) {
            try {
                coreApi.registerProfile("skills", new SkillsProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_Skills] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }

        registerApi();
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::saveState, 100L, 100L);
        this.effectTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshEffects,
                Math.max(1, config.refreshTicks()), Math.max(1, config.refreshTicks()));

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.rememberName(player);
            effects.apply(player);
        }

        getLogger().info("CHRDK_Skills включён: навыков " + SkillRegistry.size()
                + ", перков " + (SkillRegistry.size() * SkillRegistry.PERKS_PER_SKILL)
                + ", кривая 1.." + config.curve().maxLevel() + ".");
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("skills");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (effectTask != null) {
            effectTask.cancel();
            effectTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (effects != null) {
                effects.clear(player);
            }
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Skills выключен.");
    }

    /** Перечитывает config.yml/messages.yml, сохраняя прогресс игроков. */
    public void reloadSkillsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"),
                getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = SkillsConfig.parse(getConfig(), getLogger());
        if (listener != null) {
            listener.reloadAntiCheat();
        }
        if (service != null && effects != null && !config.enabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                effects.clear(player);
            }
        }
    }

    /** Пульс атрибутных перков: переставляет модификаторы по текущим уровням. */
    private void refreshEffects() {
        if (!config.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                effects.apply(player);
            } catch (RuntimeException exception) {
                getLogger().warning("Не удалось обновить перки " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void saveState() {
        try {
            if (store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить навыки: " + exception.getMessage());
        }
    }

    /** Регистрация публичного API: другие аддоны видят только интерфейс. */
    public void registerApi() {
        Bukkit.getServicesManager().register(ChrdkSkillsApi.class,
                new SkillsApiImpl(service), this, ServicePriority.Normal);
    }

    public Messages messages() {
        return messages;
    }

    public SkillsConfig config() {
        return config;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public SkillStore store() {
        return store;
    }

    public SkillService skills() {
        return service;
    }

    public SkillEffects effects() {
        return effects;
    }

    /** Тонкая обёртка: аддоны не видят внутренние классы и не могут сломать состояние. */
    private record SkillsApiImpl(SkillService service) implements ChrdkSkillsApi {

        @Override
        public long xp(Player player, String skillId) {
            SkillType skill = SkillType.byId(skillId);
            return skill == null ? 0L : service.xp(player, skill);
        }

        @Override
        public int level(Player player, String skillId) {
            SkillType skill = SkillType.byId(skillId);
            return skill == null ? 0 : service.level(player, skill);
        }

        @Override
        public int totalLevel(Player player) {
            return service.totalLevel(player);
        }

        @Override
        public int award(Player player, String skillId, long units) {
            SkillType skill = SkillType.byId(skillId);
            if (skill == null) {
                return 0;
            }
            SkillService.Award award = service.award(player, skill, units);
            return Math.max(0, award.newLevel() - award.oldLevel());
        }

        @Override
        public boolean addXp(Player player, String skillId, long xp) {
            SkillType skill = SkillType.byId(skillId);
            if (skill == null || xp <= 0L) {
                return false;
            }
            service.grantXp(player, skill, xp);
            return true;
        }

        @Override
        public double perk(Player player, String skillId, String kind) {
            SkillType skill = SkillType.byId(skillId);
            if (skill == null || kind == null || kind.isBlank()) {
                return 0.0;
            }
            try {
                SkillPerk.Kind parsed = SkillPerk.Kind.valueOf(
                        kind.trim().toUpperCase(Locale.ROOT));
                return service.perksFor(player, skill).value(parsed);
            } catch (IllegalArgumentException exception) {
                return 0.0;
            }
        }
    }
}
