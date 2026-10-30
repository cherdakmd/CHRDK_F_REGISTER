package ru.cherdakmd.chrdkguilds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Гильдия (1.4.163).
 *
 * <p>Состав, казна, уровень и слава. Ранг хранится рядом с участником, а лидер —
 * отдельное поле: иначе после исключения лидера гильдия осталась бы без главы,
 * и состав пришлось бы чинить вручную. Все изменения проходят через методы
 * класса, поэтому «два лидера» или «участник без ранга» не появляются.</p>
 *
 * <p>Уровень покупается из казны и поднимает только потолок состава и престиж:
 * силы в бою он не даёт, иначе гильдия превратилась бы в способ купить
 * преимущество.</p>
 */
public final class Guild {

    /** Потолок длины названия. */
    public static final int MAX_NAME = 16;
    /** Потолок длины тега. */
    public static final int MAX_TAG = 5;
    /** Потолок казны: дальше это уже не казна, а второй банк. */
    public static final long MAX_TREASURY = 1_000_000L;

    private final String id;
    private String name;
    private String tag;
    private UUID leader;
    private final long createdAt;
    private final Map<UUID, GuildRank> members = new LinkedHashMap<>();
    private long treasury;
    private long warReturns;
    private int level;
    private int glory;
    private int warsWon;
    private int warsLost;
    private long warCooldownUntil;

    public Guild(String id, String name, String tag, UUID leader, long createdAt) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
        this.name = cleanName(name);
        this.tag = cleanTag(tag);
        this.leader = leader;
        this.createdAt = createdAt;
        if (leader != null) {
            members.put(leader, GuildRank.LEADER);
        }
        this.level = 1;
    }

    /* ------------------------------------ ид ---------------------------------- */

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String tag() {
        return tag;
    }

    public long createdAt() {
        return createdAt;
    }

    public UUID leader() {
        return leader;
    }

    public long treasury() {
        return treasury;
    }

    /** Коллективное обязательство: часть военной выплаты сверх потолка казны. */
    public long warReturns() { return warReturns; }

    /** Не обрезает выигрыш/возврат при полной казне. */
    void returnWarFunds(long amount) {
        if (amount <= 0) return;
        long accepted = Math.min(amount, MAX_TREASURY - treasury);
        long pending = Math.addExact(warReturns, amount - accepted);
        treasury += accepted;
        warReturns = pending;
    }

    void restoreWarReturns(long amount) {
        if (amount < 0 || amount > 0 && treasury != MAX_TREASURY) throw new IllegalArgumentException("war returns");
        warReturns = amount;
    }

    public int level() {
        return level;
    }

    public int glory() {
        return glory;
    }

    public int warsWon() {
        return warsWon;
    }

    public int warsLost() {
        return warsLost;
    }

    /** Момент, раньше которого гильдия не может объявить новую войну. */
    public long warCooldownUntil() {
        return warCooldownUntil;
    }

    /* ---------------------------------- состав -------------------------------- */

    /** Состав в порядке вступления. */
    public Map<UUID, GuildRank> members() {
        return Map.copyOf(members);
    }

    /** Иды участников по порядку вступления — лидер первым. */
    public List<UUID> memberIds() {
        return new ArrayList<>(members.keySet());
    }

    public int size() {
        return members.size();
    }

    public boolean isMember(UUID player) {
        return player != null && members.containsKey(player);
    }

    public GuildRank rankOf(UUID player) {
        return player == null ? null : members.get(player);
    }

    /** Добавляет участника; уже состоящий в гильдии не дублируется. */
    public boolean addMember(UUID player, GuildRank rank) {
        if (player == null || members.containsKey(player)) {
            return false;
        }
        members.put(player, rank == null || rank == GuildRank.LEADER ? GuildRank.MEMBER : rank);
        return true;
    }

    /** Убирает участника; лидера убрать нельзя — сначала передача или роспуск. */
    public boolean removeMember(UUID player) {
        if (player == null || player.equals(leader) || !members.containsKey(player)) {
            return false;
        }
        members.remove(player);
        return true;
    }

    /** Меняет ранг; лидера понизить нельзя. */
    public boolean setRank(UUID player, GuildRank rank) {
        if (player == null || rank == null || rank == GuildRank.LEADER || player.equals(leader)) {
            return false;
        }
        GuildRank current = members.get(player);
        if (current == null || current == rank) {
            return false;
        }
        members.put(player, rank);
        return true;
    }

    /** Передаёт лидерство: прежний лидер становится офицером. */
    public boolean transferLeader(UUID player) {
        if (player == null || !members.containsKey(player) || player.equals(leader)) {
            return false;
        }
        members.put(player, GuildRank.LEADER);
        if (leader != null) {
            members.put(leader, GuildRank.OFFICER);
        }
        this.leader = player;
        return true;
    }

    /** Есть ли в составе кто-то, кроме одного игрока. */
    public boolean abandoned() {
        return members.isEmpty();
    }

    /* ---------------------------------- казна --------------------------------- */

    /** Кладёт в казну; возвращает зачисленную сумму с учётом потолка. */
    public long deposit(long amount) {
        if (amount <= 0) {
            return 0L;
        }
        long accepted = Math.min(amount, MAX_TREASURY - treasury);
        if (accepted <= 0) {
            return 0L;
        }
        treasury += accepted;
        return accepted;
    }

    void setWarCooldownUntil(long moment) {
        this.warCooldownUntil = Math.max(0L, moment);
    }

    /** Снимает из казны; возвращает снятую сумму (не больше остатка). */
    public long withdraw(long amount) {
        if (amount <= 0) {
            return 0L;
        }
        long taken = Math.min(amount, treasury);
        treasury -= taken;
        long refill = Math.min(warReturns, MAX_TREASURY - treasury);
        treasury += refill;
        warReturns -= refill;
        return taken;
    }

    /* --------------------------------- уровень -------------------------------- */

    /** Поднимает уровень; вызывающий уже проверил цену и списал её. */
    public void raiseLevel(int maxLevel) {
        if (level < maxLevel) {
            level++;
        }
    }

    /* ---------------------------------- слава --------------------------------- */

    public void addGlory(int amount) {
        if (amount > 0) {
            glory += amount;
        }
    }

    public void recordWarWin() {
        warsWon++;
    }

    public void recordWarLoss() {
        warsLost++;
    }

    /* --------------------------------- данные --------------------------------- */

    /** Переименование — только через сервис, который проверит уникальность. */
    void rename(String name) {
        this.name = cleanName(name);
    }

    void retag(String tag) {
        this.tag = cleanTag(tag);
    }

    /**
     * Восстанавливает состояние из файла.
     *
     * <p>Уровень и слава зажимаются, потому что файл могли править руками; казна
     * тоже. Лидер, которого нет в составе, добавляется первым — иначе гильдия
     * осталась бы без главы и её нельзя было бы ни распустить, ни передать.</p>
     */
    void restore(long treasury, int level, int glory, int warsWon, int warsLost) {
        this.treasury = (long) Math.clamp(treasury, 0L, MAX_TREASURY);
        this.level = Math.clamp(level, 1, GuildMath.MAX_LEVEL);
        this.glory = Math.max(0, glory);
        this.warsWon = Math.max(0, warsWon);
        this.warsLost = Math.max(0, warsLost);
        if (leader != null && !members.containsKey(leader)) {
            members.put(leader, GuildRank.LEADER);
        }
        if (!members.containsValue(GuildRank.LEADER) && !members.isEmpty()) {
            UUID first = members.keySet().iterator().next();
            members.put(first, GuildRank.LEADER);
            leader = first;
        }
    }

    /** Подпись для меню и сообщений. */
    public String label() {
        return tag.isEmpty() ? name : name + " [" + tag + "]";
    }

    static String cleanName(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        return value.length() > MAX_NAME ? value.substring(0, MAX_NAME) : value;
    }

    static String cleanTag(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        value = value.replaceAll("[^A-ZА-Я0-9]", "");
        return value.length() > MAX_TAG ? value.substring(0, MAX_TAG) : value;
    }
}
