package ru.cherdakmd.chrdkquests;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Quests — задания персонажей и фракции с репутацией (1.4.161).
 *
 * <p>Задания не выдают валюту: награда — опыт, предметы и очки фракции. Так
 * квесты не открывают второй источник алмазов и не обходят суточные потолки
 * охоты, но всё равно ведут игрока по миру: убей, добудь, принеси.</p>
 *
 * <p>Фракция — это выбор, а не класс: вступить можно в одну, репутация каждой
 * хранится отдельно и не сгорает при выходе. Ранг фракции даёт префикс в чате
 * (с настраиваемой ступени) и открывает последние задания цепочек.</p>
 */
public final class QuestsPlugin extends JavaPlugin {

    private QuestConfig config;
    private Messages messages;
    private QuestStore store;
    private QuestService service;
    private QuestsGui gui;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = QuestConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new QuestStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new QuestService(config, store);
            this.gui = new QuestsGui(this);
            getServer().getPluginManager().registerEvents(new QuestsListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            registerCommands();
            registerPlaceholders();
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("quests", new QuestsProfile(this));
                    if (config.factionPrefix()) {
                        coreApi.registerNameTag("quests", new QuestsNameTag(this));
                    }
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Quests] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            getLogger().info("CHRDK_Quests включён: фракций " + config.factions().size()
                    + ", персонажей " + config.npcList().size()
                    + ", заданий " + config.totalQuests()
                    + ", сдач в день " + config.claimsPerDay() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Quests не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("quests");
                coreApi.unregisterNameTag("quests");
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
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Quests выключен.");
    }

    /** Перечитывает конфиг и тексты, не трогая прогресс игроков. */
    public void reloadQuests() {
        reloadConfig();
        this.config = QuestConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        service.applyConfig(config);
    }

    private void registerCommands() {
        QuestsCommandHandler handler = new QuestsCommandHandler(this);
        for (String name : new String[]{"quests", "questsadmin"}) {
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
            new QuestsPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkquests_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    /* ---------------------------------- сдача --------------------------------- */

    /**
     * Единая точка сдачи задания: проверка, списание предметов, награда и
     * тексты. Команда и меню зовут её же, поэтому правила не расходятся.
     */
    public boolean claim(Player player, Quest quest) {
        if (player == null || quest == null) {
            return false;
        }
        String day = today();
        QuestService.Result result = service.claim(player.getUniqueId(), quest.id(),
                countItems(player, quest), day);
        switch (result) {
            case OK -> {
                if (quest.type() == QuestType.GATHER && !takeItems(player, quest)) {
                    // Предметы успели уйти между проверкой и списанием: прогресс
                    // уже закрыт, поэтому возвращаем вещи не требуется — но в лог
                    // пишем, чтобы расхождение было видно.
                    getLogger().warning("[CHRDK_Quests] " + player.getName()
                            + ": не удалось списать предметы для задания " + quest.id() + ".");
                }
                giveReward(player, quest);
                messages.send(player, "claim-ok", "name", quest.name());
                QuestFaction faction = config.faction(
                        service.rewardFaction(player.getUniqueId(), quest));
                if (faction != null && quest.reward().reputation() > 0) {
                    // Очки уже начислены сервисом; текст только сообщает сумму.
                    messages.send(player, "claim-rep",
                            "amount", String.valueOf(quest.reward().reputation()),
                            "faction", faction.name());
                }
                if (quest.reward().xp() > 0) {
                    messages.send(player, "claim-xp",
                            "xp", String.valueOf(quest.reward().xp()));
                }
                if (config.actionbar()) {
                    player.sendActionBar(messages.get("claim-actionbar", "name", quest.name()));
                }
            }
            case DAILY_LIMIT -> messages.send(player, "claim-daily",
                    "limit", String.valueOf(config.claimsPerDay()));
            case NOT_READY -> messages.send(player, "claim-not-ready",
                    "amount", String.valueOf(quest.amount()),
                    "target", Messages.trusted(QuestText.joinTags(quest.targetTags())));
            case NO_ITEMS -> messages.send(player, "claim-no-items",
                    "amount", String.valueOf(quest.amount()),
                    "have", String.valueOf(countItems(player, quest)),
                    "target", Messages.trusted(QuestText.joinTags(quest.targetTags())));
            case DONE -> messages.send(player, "claim-done");
            case LOCKED -> messages.send(player, "claim-locked");
            case WRONG_FACTION -> messages.send(player, "claim-wrong-faction",
                    "faction", factionName(quest),
                    "id", requiredFactionId(quest));
            case RANK -> messages.send(player, "claim-rank",
                    "rank", String.valueOf(quest.minRank()));
            default -> messages.send(player, "claim-unknown");
        }
        return result == QuestService.Result.OK;
    }

    /** Сколько предметов цели лежит в инвентаре игрока. */
    public int countItems(Player player, Quest quest) {
        if (player == null || quest == null || quest.materials().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack != null && quest.matchesMaterial(stack.getType())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Забирает предметы цели; {@code false}, если их не хватило. */
    public boolean takeItems(Player player, Quest quest) {
        if (player == null || quest == null) {
            return false;
        }
        if (countItems(player, quest) < quest.amount()) {
            return false;
        }
        int left = quest.amount();
        for (int slot = 0; slot < player.getInventory().getSize() && left > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || !quest.matchesMaterial(stack.getType())) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                player.getInventory().setItem(slot, null);
            }
            left -= take;
        }
        return left <= 0;
    }

    /** Выдаёт опыт и предметы; если инвентарь полон — бросает под ноги. */
    public void giveReward(Player player, Quest quest) {
        QuestReward reward = quest.reward();
        if (reward.xp() > 0) {
            player.giveExp(reward.xp());
        }
        for (QuestReward.Item item : reward.items()) {
            ItemStack stack = new ItemStack(item.material(), item.amount());
            for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                messages.send(player, "claim-dropped", "item", Messages.trusted(
                        QuestText.materialTag(rest.getType())));
            }
        }
    }

    /** Название фракции персонажа задания — для текста «нужна фракция». */
    private String factionName(Quest quest) {
        QuestFaction faction = config.faction(requiredFactionId(quest));
        return faction == null ? "—" : faction.name();
    }

    /** Ид фракции, без которой задание недоступно. */
    private String requiredFactionId(Quest quest) {
        QuestNpc npc = config.npc(quest.npcId());
        return npc == null ? "" : npc.factionId();
    }

    /** Сегодняшний день по UTC — как суточные счётчики охоты. */
    public String today() {
        return QuestService.day(System.currentTimeMillis());
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
            getLogger().warning("Не удалось сохранить quests.json: " + error.getMessage());
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    public QuestConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public QuestService service() {
        return service;
    }

    public QuestStore store() {
        return store;
    }

    public QuestsGui gui() {
        return gui;
    }
}
