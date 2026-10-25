package ru.cherdakmd.chrdkquests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Правила квестов и фракций (1.4.161).
 *
 * <p>Здесь нет ни Bukkit, ни игрока: только UUID, данные хранилища и конфиг.
 * Поэтому «задание закрыто», «цепочка не пускает дальше» и «суточный лимит
 * выбран» проверяются тестами без сервера, а команда, меню и слушатель
 * событий не могут разойтись в правилах.</p>
 */
public final class QuestService {

    /** Почему задание можно или нельзя взять. */
    public enum Availability {
        /** Доступно: предыдущее закрыто, фракция и ранг подходят. */
        AVAILABLE,
        /** Начато, прогресс есть, но цель не достигнута. */
        IN_PROGRESS,
        /** Цель достигнута — можно сдавать. */
        READY,
        /** Уже сдано. */
        DONE,
        /** Предыдущее задание цепочки не закрыто. */
        LOCKED,
        /** Игрок не в той фракции, которой служит персонаж. */
        WRONG_FACTION,
        /** Не хватает ранга фракции. */
        RANK
    }

    /** Итог действия — команда сама выбирает текст. */
    public enum Result {
        /** Получилось. */
        OK,
        /** Такого задания нет в конфиге. */
        UNKNOWN,
        /** Уже сдано. */
        DONE,
        /** Цепочка ещё не дошла до этого задания. */
        LOCKED,
        /** Игрок не в той фракции. */
        WRONG_FACTION,
        /** Не хватает ранга. */
        RANK,
        /** Цель не достигнута. */
        NOT_READY,
        /** Не хватает предметов для сдачи. */
        NO_ITEMS,
        /** Суточный лимит сдач выбран. */
        DAILY_LIMIT,
        /** Игрок уже в этой фракции. */
        SAME_FACTION,
        /** Такой фракции нет. */
        UNKNOWN_FACTION,
        /** Игрок ни в одной фракции. */
        NO_FACTION
    }

    private volatile QuestConfig config;
    private final QuestStore store;

    public QuestService(QuestConfig config, QuestStore store) {
        this.config = config == null ? QuestConfig.defaults() : config;
        this.store = store;
    }

    public QuestConfig config() {
        return config;
    }

    public QuestStore store() {
        return store;
    }

    public void applyConfig(QuestConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    /* ------------------------------- фракции ---------------------------------- */

    /** Текущая фракция игрока или {@code null}. */
    public QuestFaction faction(UUID playerId) {
        return config.faction(store.faction(playerId));
    }

    public String factionId(UUID playerId) {
        return store.faction(playerId);
    }

    /** Репутация в текущей фракции (0 — если её нет). */
    public int reputation(UUID playerId) {
        String faction = store.faction(playerId);
        return faction.isEmpty() ? 0 : store.reputation(playerId, faction);
    }

    /** Ид ранга в текущей фракции; пусто — если игрок вне фракций. */
    public String rankId(UUID playerId) {
        QuestFaction faction = faction(playerId);
        return faction == null ? "" : faction.rankId(reputation(playerId));
    }

    /** Номер ранга (1 — первый); 0 — если игрок вне фракций. */
    public int rankNumber(UUID playerId) {
        QuestFaction faction = faction(playerId);
        return faction == null ? 0 : faction.rankNumber(reputation(playerId));
    }

    /** Номер ранга в конкретной фракции: нужен для чужих квестов и профиля. */
    public int rankNumber(UUID playerId, String factionId) {
        QuestFaction faction = config.faction(factionId);
        if (faction == null) {
            return 0;
        }
        return faction.rankNumber(store.reputation(playerId, faction.id()));
    }

    /** Вступает во фракцию. */
    public Result join(UUID playerId, String factionId) {
        QuestFaction faction = config.faction(factionId);
        if (faction == null) {
            return Result.UNKNOWN_FACTION;
        }
        if (faction.id().equals(store.faction(playerId))) {
            return Result.SAME_FACTION;
        }
        store.setFaction(playerId, faction.id());
        return Result.OK;
    }

    /** Выходит из фракции; репутация сохраняется. */
    public Result leaveFaction(UUID playerId) {
        if (store.faction(playerId).isEmpty()) {
            return Result.NO_FACTION;
        }
        store.setFaction(playerId, "");
        return Result.OK;
    }

    /* -------------------------------- задания --------------------------------- */

    public Quest quest(String questId) {
        return config.quest(questId);
    }

    public List<QuestNpc> npcs() {
        return config.npcList();
    }

    public int totalQuests() {
        return config.totalQuests();
    }

    public int doneCount(UUID playerId) {
        return store.doneCount(playerId);
    }

    /** Прогресс задания: для сдачи предметов — 0, там считает инвентарь. */
    public int progress(UUID playerId, Quest quest) {
        if (quest == null || quest.type() == QuestType.GATHER) {
            return 0;
        }
        return Math.min(store.progress(playerId, quest.id()), quest.amount());
    }

    /** Готово ли задание к сдаче (без учёта инвентаря). */
    public boolean ready(UUID playerId, Quest quest) {
        return quest != null && quest.type() != QuestType.GATHER
                && progress(playerId, quest) >= quest.amount();
    }

    /** Первое незакрытое задание цепочки или {@code null}. */
    public Quest current(UUID playerId, QuestNpc npc) {
        if (npc == null) {
            return null;
        }
        for (Quest quest : npc.quests()) {
            if (!store.done(playerId, quest.id())) {
                return quest;
            }
        }
        return null;
    }

    /** Текущие задания всех персонажей. */
    public List<Quest> currentQuests(UUID playerId) {
        List<Quest> out = new ArrayList<>();
        for (QuestNpc npc : config.npcList()) {
            Quest quest = current(playerId, npc);
            if (quest != null) {
                out.add(quest);
            }
        }
        return out;
    }

    /** Сколько текущих заданий уже готово к сдаче. */
    public int readyCount(UUID playerId) {
        int count = 0;
        for (Quest quest : currentQuests(playerId)) {
            if (ready(playerId, quest)) {
                count++;
            }
        }
        return count;
    }

    /** Доступно ли задание по цепочке (предыдущее закрыто). */
    public boolean unlocked(UUID playerId, Quest quest) {
        QuestNpc npc = config.npc(quest.npcId());
        if (npc == null) {
            return false;
        }
        int index = npc.indexOf(quest.id());
        if (index <= 0) {
            return index == 0;
        }
        return store.done(playerId, npc.quests().get(index - 1).id());
    }

    /** Полная проверка: можно ли сейчас сдавать задание (без инвентаря). */
    public Availability availability(UUID playerId, Quest quest) {
        if (quest == null) {
            return Availability.LOCKED;
        }
        if (store.done(playerId, quest.id())) {
            return Availability.DONE;
        }
        QuestNpc npc = config.npc(quest.npcId());
        if (npc == null || !unlocked(playerId, quest)) {
            return Availability.LOCKED;
        }
        String required = npc.factionId();
        if (!required.isEmpty() && !required.equals(store.faction(playerId))) {
            return Availability.WRONG_FACTION;
        }
        String rankFaction = required.isEmpty() ? store.faction(playerId) : required;
        if (quest.minRank() > 0 && rankNumber(playerId, rankFaction) < quest.minRank()) {
            return Availability.RANK;
        }
        if (quest.type() != QuestType.GATHER && progress(playerId, quest) >= quest.amount()) {
            return Availability.READY;
        }
        return progress(playerId, quest) > 0 ? Availability.IN_PROGRESS : Availability.AVAILABLE;
    }

    /**
     * Та же проверка, но с учётом инвентаря: для {@link QuestType#GATHER}
     * прогресс живёт не в хранилище, а в карманах игрока. Меню и команды
     * зовут этот вариант, поэтому «принеси 16 пшеницы» честно показывает
     * «готово», когда пшеница уже собрана.
     *
     * @param availableItems сколько предметов цели лежит в инвентаре
     */
    public Availability availability(UUID playerId, Quest quest, int availableItems) {
        Availability base = availability(playerId, quest);
        if (quest == null || quest.type() != QuestType.GATHER) {
            return base;
        }
        if (base != Availability.AVAILABLE && base != Availability.IN_PROGRESS) {
            return base;
        }
        if (availableItems >= quest.amount()) {
            return Availability.READY;
        }
        return availableItems > 0 ? Availability.IN_PROGRESS : Availability.AVAILABLE;
    }

    /**
     * Сдаёт задание.
     *
     * @param availableItems сколько предметов цели есть в инвентаре (для GATHER)
     * @param day            текущий день в UTC ({@code 2026-10-24})
     */
    public Result claim(UUID playerId, String questId, int availableItems, String day) {
        Quest quest = config.quest(questId);
        if (quest == null) {
            return Result.UNKNOWN;
        }
        Availability availability = availability(playerId, quest);
        if (availability == Availability.DONE) {
            return Result.DONE;
        }
        if (availability == Availability.LOCKED) {
            return Result.LOCKED;
        }
        if (availability == Availability.WRONG_FACTION) {
            return Result.WRONG_FACTION;
        }
        if (availability == Availability.RANK) {
            return Result.RANK;
        }
        if (quest.type() == QuestType.GATHER) {
            if (availableItems < quest.amount()) {
                return Result.NO_ITEMS;
            }
        } else if (progress(playerId, quest) < quest.amount()) {
            return Result.NOT_READY;
        }
        if (claimsLeft(playerId, day) <= 0) {
            return Result.DAILY_LIMIT;
        }
        if (!store.countClaim(playerId, day, config.claimsPerDay())) {
            return Result.DAILY_LIMIT;
        }
        store.markDone(playerId, quest.id());
        String faction = rewardFaction(playerId, quest);
        if (!faction.isEmpty()) {
            store.addReputation(playerId, faction, quest.reward().reputation());
        }
        return Result.OK;
    }

    /**
     * Кому идут очки репутации: фракции персонажа, а у нейтрального — текущей
     * фракции игрока (если он в ней состоит).
     */
    public String rewardFaction(UUID playerId, Quest quest) {
        QuestNpc npc = quest == null ? null : config.npc(quest.npcId());
        if (npc != null && !npc.neutral()) {
            return npc.factionId();
        }
        return store.faction(playerId);
    }

    /** Сколько сдач осталось на сегодня. */
    public int claimsLeft(UUID playerId, String day) {
        int used = store.claims(playerId, day);
        return Math.max(0, config.claimsPerDay() - used);
    }

    /** Ид текущего дня для суточного счёта — UTC, как в остальных аддонах. */
    public static String day(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .toString();
    }

    /** Задание по иду или {@code null}. */
    public Quest find(String questId) {
        return config.quest(questId);
    }

    /** Все иды заданий — для автодополнения. */
    public List<String> questIds() {
        List<String> ids = new ArrayList<>();
        for (Quest quest : config.allQuests()) {
            ids.add(quest.id());
        }
        return ids;
    }

    /** Ид фракции в нижнем регистре — для сравнения без лишних аллокаций. */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
