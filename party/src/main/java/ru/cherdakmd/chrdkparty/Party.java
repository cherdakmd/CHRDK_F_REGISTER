package ru.cherdakmd.chrdkparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Группа игроков (1.4.158).
 *
 * <p>Группа — это лидер и состав. Никаких очков, казны и уровней: всё
 * «богатство» группы — люди в ней. Состав хранится в порядке вступления, чтобы
 * список не прыгал при каждом пересчёте, а лидер всегда был первым.</p>
 *
 * <p>Имена лежат рядом с UUID: игрок может выйти из сети, но в списке и в
 * сообщениях он должен остаться узнаваемым. Класс чистый (только
 * {@code java.util}) и проверяется юнит-тестами без сервера.</p>
 */
public final class Party {

    /** Потолок состава — жёсткий, независимо от конфига. */
    public static final int HARD_LIMIT = PartyConfig.MAX_SIZE_LIMIT;

    private final UUID id;
    private final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
    private UUID leader;

    Party(UUID id, UUID leader, String name) {
        if (id == null || leader == null) {
            throw new IllegalArgumentException("у группы нет ида или лидера");
        }
        this.id = id;
        this.leader = leader;
        this.members.put(leader, clean(name));
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    /** Имя лидера; пустое, если он почему-то потерялся (битый файл). */
    public String leaderName() {
        return members.getOrDefault(leader, "");
    }

    public int size() {
        return members.size();
    }

    public boolean isMember(UUID player) {
        return player != null && members.containsKey(player);
    }

    public boolean isLeader(UUID player) {
        return player != null && player.equals(leader);
    }

    /** Состав в порядке вступления — копия, менять снаружи нельзя. */
    public List<UUID> memberIds() {
        return List.copyOf(members.keySet());
    }

    /** Состав с именами — для сохранения и для списка в меню. */
    public Map<UUID, String> members() {
        return Collections.unmodifiableMap(members);
    }

    /** Имя участника; пустое, если его нет в группе. */
    public String nameOf(UUID player) {
        return player == null ? "" : members.getOrDefault(player, "");
    }

    /* ------------------------------- изменения -------------------------------- */

    /**
     * Добавляет участника.
     *
     * @return {@code false} — уже в группе или состав переполнен
     */
    boolean add(UUID player, String name) {
        if (player == null || members.containsKey(player) || members.size() >= HARD_LIMIT) {
            return false;
        }
        members.put(player, clean(name));
        return true;
    }

    /** Убирает участника; {@code true} — он действительно был в группе. */
    boolean remove(UUID player) {
        if (player == null) {
            return false;
        }
        return members.remove(player) != null;
    }

    void setLeader(UUID player) {
        if (player != null && members.containsKey(player)) {
            this.leader = player;
        }
    }

    /** Переставляет лидера в начало списка — так его проще найти глазами. */
    void leaderFirst() {
        if (!members.containsKey(leader)) {
            return;
        }
        List<UUID> order = new ArrayList<>(members.keySet());
        order.remove(leader);
        order.add(0, leader);
        Map<UUID, String> copy = new LinkedHashMap<>(members);
        members.clear();
        for (UUID player : order) {
            members.put(player, copy.getOrDefault(player, ""));
        }
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim();
    }

    @Override
    public String toString() {
        return "Party[" + id + ", leader=" + leader + ", size=" + members.size() + "]";
    }
}
