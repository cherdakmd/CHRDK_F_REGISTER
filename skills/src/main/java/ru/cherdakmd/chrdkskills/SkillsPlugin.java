package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.util.Locale;

/**
 * CHRDK_Skills — десять навыков с уровнями 1..100 и 12 ступенями перков
 * на каждом (всего 120).
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
    private SkillAbilityStore abilityStore;
    private SkillAbilities abilities;
    private BukkitTask abilityTask;
    private BukkitTask saveTask;
    private BukkitTask effectTask;

    private ru.cherdakmd.chrdkregister.api.MilestoneAwards awards;
    public ru.cherdakmd.chrdkregister.api.MilestoneAwards awards() { return awards; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        this.store = new SkillStore(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(this), getLogger());
        // Конфиг читается до создания листенера: антинакрутка берёт из него TTL.
        reloadSkillsConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 38) throw new IllegalStateException("Нужен Core API 38+");
            this.awards = new ru.cherdakmd.chrdkregister.api.MilestoneAwards(this,this::coreApi,this::messages,"skills-awards",ru.cherdakmd.chrdkregister.api.MilestoneMigration.json(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(this).resolve("skills.json"),ru.cherdakmd.chrdkregister.api.MilestoneMigration.Kind.SKILLS));
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Модуль остановлен без сброса прогресса/наград",error);
            this.store = null;
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        this.store.load();
        this.service = new SkillService(this, store);
        this.abilityStore = new SkillAbilityStore(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(this), getLogger());
        abilityStore.load();
        this.abilities = new SkillAbilities(this, abilityStore);
        this.effects = new SkillEffects(this);
        this.listener = new SkillListener(this, service);
        this.gui = new SkillsGui(this, service);

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(abilities, this);
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

        this.abilityTask = Bukkit.getScheduler().runTaskTimer(this, abilities::pulse, 20L, 20L);

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
        if (gui != null) {
            Bukkit.getServicesManager().unregister(SkillsMenuApi.class, gui);
        }
        if (awards != null) awards.close();
        ru.cherdakmd.chrdkregister.api.CropReplanting.cancel(this);
        if (abilityTask != null) abilityTask.cancel();
        if (abilities != null) abilities.clear();
        if (abilityStore != null) abilityStore.flush();
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
        ru.cherdakmd.chrdkregister.api.CropReplanting.cancel(this);
        if (abilities != null) abilities.clear();
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
            if (abilityStore != null) abilityStore.saveNow();
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
        // Единое меню: /perktree из CHRDK_Classes открывает тот же инвентарь.
        Bukkit.getServicesManager().register(SkillsMenuApi.class, gui,
                this, ServicePriority.Normal);
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

    SkillAbilities abilities() { return abilities; }

    public SkillEffects effects() {
        return effects;
    }

    /** Персональный дневной лимит дропа алмазов для онлайн-игрока. */
    public int diamondDailyCap(Player player, RankBonus knownBonus) {
        if (player == null) {
            return config().diamonds().dailyCap();
        }
        return diamondDailyCap(player.getName(),
                skills().totalLevel(player), skills().totalPrestige(player), knownBonus);
    }

    /**
     * Тот же лимит для {@code /profile}: ранг, Pass, буст и entitlement читаются
     * по имени, уровни и престиж — из {@code skills.json} по UUID, поэтому
     * раздел работает и для оффлайн-игрока.
     */
    public int diamondDailyCap(String playerName, int totalLevel, int totalPrestige) {
        return diamondDailyCap(playerName, totalLevel, totalPrestige, null);
    }

    private int diamondDailyCap(String playerName, int totalLevel, int totalPrestige,
                                RankBonus knownBonus) {
        DiamondCapMath.Settings settings = config().diamondCap();
        return DiamondCapMath.compute(config().diamonds().dailyCap(), settings,
                capContext(playerName, totalLevel, totalPrestige, settings, knownBonus));
    }

    private DiamondCapMath.Context capContext(String name, int totalLevel,
                                             int totalPrestige,
                                             DiamondCapMath.Settings settings,
                                             RankBonus knownBonus) {
        ChrdkRegisterApi api = coreApi();
        RankBonus bonus = knownBonus == null ? RankBonus.NONE : knownBonus;
        boolean pass = false;
        boolean boost = false;
        boolean entitlement = false;
        if (api != null && name != null) {
            try {
                if (knownBonus == null) {
                    bonus = api.rankBonus(name);
                }
                pass = api.hasActivePass(name);
                boost = api.rewardBoostRemainingMillis(name) > 0L;
                String productId = settings.entitlementId();
                entitlement = !productId.isEmpty() && api.hasEntitlement(name, productId);
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро недоступно или старше контракта: остаёмся на базовом лимите.
            }
        }
        if (bonus == null) {
            bonus = RankBonus.NONE;
        }
        return new DiamondCapMath.Context(
                bonus.level(), bonus.xpPercent(), bonus.dropPercent(), bonus.extraSkill(),
                totalPrestige, totalLevel, pass, boost, entitlement);
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
        public int totalPrestige(Player player) {
            return service.totalPrestige(player);
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
