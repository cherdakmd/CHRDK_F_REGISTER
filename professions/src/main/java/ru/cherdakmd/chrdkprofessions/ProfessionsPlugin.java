package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Professions — четыре ремесла и тихие прибавки к работе (1.4.165).
 *
 * <p>Профессия не даёт силы: она возвращает время и материалы. Кузнец платит
 * меньше уровней в наковальне, алхимик получает лишнюю склянку, повар сытнее
 * ест, камнерез получает второй блок при плавке. Всё это зажато потолками в
 * {@link ProfessionMath}, поэтому профессия не становится скрытой веткой
 * прокачки и не продаёт преимущество за деньги.</p>
 *
 * <p>Опыт начисляется только за события, которые нельзя подделать клиентом:
 * крафт, выплавку и завершённую варку. Креатив не считается, шифт-крафт даёт
 * опыт за один результат рецепта, а суточный потолок не даёт превратить
 * профессию в бесконечную ферму.</p>
 */
public final class ProfessionsPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile ProfessionConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private ProfessionStore store;
    private ProfessionService service;
    private ProfessionsGui gui;
    /** 1.4.182: обязательный выбор профессии — меню и напоминания. */
    private ProfessionOnboarding onboarding;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = ProfessionConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new ProfessionStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new ProfessionService(config, store);
            this.gui = new ProfessionsGui(this);
            this.onboarding = new ProfessionOnboarding(this);

            getServer().getPluginManager().registerEvents(new ProfessionListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(onboarding, this);
            registerCommands();
            registerPlaceholders();
            registerProfile();

            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 200L, 200L);
            onboarding.start();

            getLogger().info("CHRDK_Professions включён: профессий " + Profession.values().length
                    + ", игроков в файле " + store.size() + ".");
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
        if (onboarding != null) {
            onboarding.stop();
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
        // 1.4.182: период напоминания мог измениться — перезапускаем задачи.
        if (onboarding != null) {
            onboarding.start();
        }
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
}
