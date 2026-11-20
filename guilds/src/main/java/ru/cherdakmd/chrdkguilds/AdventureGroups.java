package ru.cherdakmd.chrdkguilds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Временная модель добровольных групп; не хранится на диске.
 * Вызывать на серверном потоке. Перед использованием состава для игровых
 * эффектов обязательно повторно проверять все пары через {@link #verify(UUID)}.
 */
final class AdventureGroups {
    static final int MIN_MEMBERS = 2;
    static final int MAX_MEMBERS = 16;
    static final long INVITATION_MILLIS = 60_000L;

    enum Result {
        OK, INVALID, OFFLINE, ALREADY_JOINED, NOT_LEADER, GROUP_FULL,
        INVITE_MISSING, INVITE_EXPIRED, AT_WAR, UNAVAILABLE
    }

    private static final class Team {
        final UUID leader;
        final List<UUID> members = new ArrayList<>();
        Team(UUID leader) { this.leader = leader; members.add(leader); }
    }
    private record Invitation(UUID leader, long expiresAt) { }

    private final int maxMembers;
    private final BiFunction<UUID, UUID, GuildRelation> relation;
    private final Predicate<UUID> online;
    private final Map<UUID, Team> membership = new HashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();

    AdventureGroups(int maxMembers, BiFunction<UUID, UUID, GuildRelation> relation,
                    Predicate<UUID> online) {
        if (maxMembers < MIN_MEMBERS || maxMembers > MAX_MEMBERS || relation == null || online == null) {
            throw new IllegalArgumentException("invalid group configuration");
        }
        this.maxMembers = maxMembers;
        this.relation = relation;
        this.online = online;
    }

    /** Одно ожидающее приглашение на получателя, новое заменяет предыдущее. */
    Result invite(UUID leader, UUID target, long now) {
        if (leader == null || target == null || leader.equals(target)
                || now < 0 || now > Long.MAX_VALUE - INVITATION_MILLIS) return Result.INVALID;
        if (!isOnline(leader) || !isOnline(target)) return Result.OFFLINE;
        invitations.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Team team = membership.get(leader);
        if (team != null && !team.leader.equals(leader)) return Result.NOT_LEADER;
        if (team != null) {
            Result current = verify(leader);
            if (current != Result.OK) return current;
        }
        if (membership.containsKey(target)) return Result.ALREADY_JOINED;
        List<UUID> members = team == null ? List.of(leader) : List.copyOf(team.members);
        if (team != null && team.members.size() >= maxMembers) return Result.GROUP_FULL;
        Result permitted = checkPairs(members, target);
        if (permitted != Result.OK) return permitted;
        invitations.put(target, new Invitation(leader, now + INVITATION_MILLIS));
        return Result.OK;
    }

    /** Повторно проверяет состав, онлайн и все пары перед созданием или вступлением. */
    Result accept(UUID target, long now) {
        if (target == null || now < 0) return Result.INVALID;
        Invitation invitation = invitations.remove(target);
        if (invitation == null) return Result.INVITE_MISSING;
        if (now >= invitation.expiresAt()) return Result.INVITE_EXPIRED;
        UUID leader = invitation.leader();
        if (!isOnline(leader) || !isOnline(target)) return Result.OFFLINE;
        if (membership.containsKey(target)) return Result.ALREADY_JOINED;
        Team team = membership.get(leader);
        if (team != null && !team.leader.equals(leader)) return Result.NOT_LEADER;
        if (team != null) {
            Result current = verify(leader);
            if (current != Result.OK) return current;
        }
        if (team != null && team.members.size() >= maxMembers) return Result.GROUP_FULL;
        List<UUID> members = team == null ? List.of(leader) : List.copyOf(team.members);
        Result permitted = checkPairs(members, target);
        if (permitted != Result.OK) return permitted;
        if (team == null) {
            team = new Team(leader);
            membership.put(leader, team);
        }
        team.members.add(target);
        membership.put(target, team);
        // Принятие нового членства отменяет приглашения, которые игрок отправлял как лидер.
        invitations.entrySet().removeIf(entry -> entry.getValue().leader().equals(target));
        return Result.OK;
    }

    boolean decline(UUID player) {
        return player != null && invitations.remove(player) != null;
    }

    /** Выход лидера или сокращение до одного участника распускает группу. */
    void leave(UUID player) {
        if (player == null) return;
        invitations.remove(player);
        invitations.entrySet().removeIf(entry -> entry.getValue().leader().equals(player));
        Team team = membership.get(player);
        if (team == null) return;
        if (team.leader.equals(player) || team.members.size() <= MIN_MEMBERS) {
            disband(team);
            return;
        }
        team.members.remove(player);
        membership.remove(player);
    }

    /** Проверить все пары; при войне распустить, при неизвестности не выдавать доступ. */
    Result verify(UUID player) {
        if (player == null) return Result.INVALID;
        Team team = membership.get(player);
        if (team == null) return Result.INVALID;
        Result result = checkPairs(List.copyOf(team.members), null);
        if (result == Result.AT_WAR) disband(team);
        return result;
    }

    /** Только подтверждённые участники; при неизвестных отношениях список недоступен. */
    List<UUID> members(UUID player) {
        if (verify(player) != Result.OK) return List.of();
        return List.copyOf(membership.get(player).members);
    }

    /** Снимок для обработки выхода и уведомлений, не для принятия решений о доступе. */
    List<UUID> participants() { return List.copyOf(membership.keySet()); }

    /** Снимок для уведомлений, не применять для принятия решений о доступе. */
    List<UUID> snapshot(UUID player) {
        Team team = membership.get(player);
        return team == null ? List.of() : List.copyOf(team.members);
    }

    /** Перепроверяет все группы; возвращает составы, распущенные из-за войны. */
    List<List<UUID>> reconcile() {
        List<Team> teams = membership.values().stream().distinct().toList();
        List<List<UUID>> dissolved = new ArrayList<>();
        for (Team team : teams) {
            List<UUID> before = List.copyOf(team.members);
            if (verify(team.leader) == Result.AT_WAR) dissolved.add(before);
        }
        return List.copyOf(dissolved);
    }

    /** На отключении плагина: временные группы и приглашения не восстанавливаются. */
    void clear() {
        membership.clear();
        invitations.clear();
    }

    private void disband(Team team) {
        for (UUID member : team.members) membership.remove(member);
        invitations.entrySet().removeIf(entry -> entry.getValue().leader().equals(team.leader));
    }

    private boolean isOnline(UUID player) {
        try { return online.test(player); } catch (RuntimeException error) { return false; }
    }

    private Result checkPairs(List<UUID> members, UUID candidate) {
        List<UUID> all = new ArrayList<>(members);
        if (candidate != null) all.add(candidate);
        boolean unknown = false;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                GuildRelation value;
                try { value = relation.apply(all.get(i), all.get(j)); }
                catch (RuntimeException error) { value = GuildRelation.UNKNOWN; }
                if (value == GuildRelation.ACTIVE_WAR) return Result.AT_WAR;
                if (value == null || value == GuildRelation.UNKNOWN) unknown = true;
            }
        }
        return unknown ? Result.UNAVAILABLE : Result.OK;
    }
}
