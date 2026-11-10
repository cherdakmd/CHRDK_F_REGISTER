package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.UUID;

/**
 * CHRDK_Professions — восемь ремёсел, тихие прибавки и заработок алмазов (1.4.282).
 *
 * <p>Выбор профессии отменён: все ремесла доступны каждому игроку и качаются
 * параллельно. Опыт начисляется только за события, которые нельзя подделать
 * клиентом (крафт, выплавка, варка, добыча, распил, рыбалка, наковальня),
 * креатив не считается, а суточный потолок не даёт превратить работу в бесконечную
 * ферму. За тот же честно начисленный опыт игрок получает алмазы на личный счёт;
 * донатный ранг через {@code RankBonus.xpPercent} усиливает и опыт, и доход.</p>
 *
 * <p>Выплаты идут через накопительный WAL ядра ({@link ProfessionIncome}), чтобы
 * рестарт или недоступный банк не удваивали и не теряли алмазы.</p>
 */
public final class ProfessionsPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile ProfessionConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private ProfessionStore store;
    private ProfessionService service;
    private ProfessionsGui gui;
    private ProfessionWorkshop workshop;
    private ProfessionGuide guide;
    ProfessionGuide guide(){return guide;}
    /** 1.4.282: доставка заработанных алмазов в банк ядра. */
    private ProfessionIncome income;
    private BukkitTask incomeTask;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;

    @Override
    public void onEnable() {
        try {
            try {
                if (ChrdkRegisterApi.class.getField("API_VERSION").getInt(null)<40) throw new IllegalStateException("Нужен Core API 40+");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Нужен Core API 40+",failure); }
            saveDefaultConfig();
            ConfigUpgrader.apply(this);
            this.config = ProfessionConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new ProfessionStore(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(this), getLogger());
            store.load();
            this.service = new ProfessionService(config, store);
            this.gui = new ProfessionsGui(this);
            this.workshop = new ProfessionWorkshop(this);
            this.guide=new ProfessionGuide(this);
            getServer().getPluginManager().registerEvents(guide,this);
            // 1.4.282: принудительный онбординг и меню выбора больше не регистрируются.
            this.income = new ProfessionIncome(store, new ProfessionIncome.Bank() {
                public boolean available() { return coreApi != null && coreApi.personalBankAvailable(); }
                public boolean registered(UUID lineage) { return coreApi != null && coreApi.incomeSourceAvailable(ProfessionIncome.SOURCE, lineage); }
                public boolean register(UUID lineage) { return coreApi != null && coreApi.registerIncomeSource(ProfessionIncome.SOURCE, lineage); }
                public boolean known(UUID owner) { return coreApi != null && coreApi.canReceiveBankIncome(owner); }
                public long credited(UUID lineage, UUID owner) {
                    return coreApi == null ? -1L : coreApi.creditedEntitlement(ProfessionIncome.SOURCE, lineage, owner.toString(), owner);
                }
                public boolean credit(UUID lineage, UUID owner, long total) {
                    return coreApi != null && coreApi.creditEntitlement(ProfessionIncome.SOURCE, lineage, owner.toString(), owner, total);
                }
            });

            getServer().getPluginManager().registerEvents(new ProfessionListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            registerProfile();

            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 200L, 200L);
            this.incomeTask = getServer().getScheduler().runTaskTimer(this,
                    () -> { if (income != null) income.tick(16); }, 600L, 600L);

            getLogger().info("CHRDK_Professions включён: ремёсел " + Profession.values().length
                    + " (все активны), игроков в файле " + store.size() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Professions не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("professions");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше нужного API — снимать нечего.
            }
            this.coreApi = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        if (incomeTask != null) {
            incomeTask.cancel();
            incomeTask = null;
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Professions выключен.");
    }

    /** Перечитывает конфиг и тексты, не трогая опыт игроков. */
    public void reloadProfessions() {
        reloadConfig();
        this.config = ProfessionConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        service.applyConfig(config);
    }

    ProfessionWorkshop workshop() { return workshop; }

    boolean authenticated(Player player) {
        ChrdkRegisterApi api = coreApi;
        if (api == null || player == null) return false;
        try { return api.isAuthenticated(player); }
        catch (LinkageError ignored) { return false; }
    }

    private void registerCommands() {
        ProfessionsCommandHandler handler = new ProfessionsCommandHandler(this);
        for (String name : new String[]{"prof", "profadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
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
            new ProfessionsPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkprofessions_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    private void registerProfile() {
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (coreApi == null) {
            return;
        }
        // 1.4.167: бонусы ранга — опыт и дополнительные уровни навыка. Ядро может
        // быть старше API v10: тогда rankBonus() вернёт нули (default-метод),
        // и профессии работают как раньше.
        service.setRankBonus(uuid -> {
            var online = uuid == null ? null : Bukkit.getPlayer(uuid);
            return online == null ? ru.cherdakmd.chrdkregister.api.RankBonus.NONE
                    : coreApi.rankBonus(online.getName());
        });
        service.setRewardScaler((uuid, base, current) -> {
            var online = uuid == null ? null : Bukkit.getPlayer(uuid);
            return online == null || coreApi == null ? current
                    : coreApi.boostExperience(online.getName(), "professions", base, current);
        });
        try {
            coreApi.registerProfile("professions", new ProfessionsProfile(this));
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("[CHRDK_Professions] раздел профиля недоступен: " + error);
        }
    }

    /**
     * Подсказка после выбора ремесла (1.4.177).
     *
     * <p>Игрок только что нажал «Выбрать» — в этот момент он должен узнать, за
     * какую работу пойдёт опыт, а не через день и не в вики. Одна строка в чате
     * и есть ответ на «выбрал и ничего не происходит».</p>
     */
    public void hint(Player player, Profession profession) {
        if (player == null || profession == null) {
            return;
        }
        Messages texts = this.messages;
        if (texts == null) {
            return;
        }
        this.messages.send(player, "chosen-hint",
                "profession", ProfessionText.nameComponent(texts, profession),
                "hint", ProfessionText.hintComponent(texts, profession));
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
            getLogger().warning("Не удалось сохранить professions.json: " + error.getMessage());
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    public ProfessionConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public ProfessionStore store() {
        return store;
    }

    public ProfessionService service() {
        return service;
    }

    public ProfessionsGui gui() {
        return gui;
    }

    /** Доставщик заработанных алмазов (null до onEnable). */
    public ProfessionIncome income() {
        return income;
    }
}
