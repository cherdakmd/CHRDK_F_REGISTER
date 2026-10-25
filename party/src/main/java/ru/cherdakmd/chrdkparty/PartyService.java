package ru.cherdakmd.chrdkparty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Правила групп: создание, приглашения, выход, исключение и бонус (1.4.158).
 *
 * <p>Все решения принимаются здесь, а не в командах и не в слушателях: клик в
 * меню, команда и событие должны вести себя одинаково. Класс чистый — только
 * UUID, имена и время, переданное параметром {@code now}, поэтому сценарии
 * «приглашение истекло» и «лидер ушёл» проверяются без сервера.</p>
 *
 * <p>Приглашения живут в памяти и умирают сами: срок измеряется в секундах,
 * поэтому после перезапуска сервера восстанавливать их не из чего и незачем.</p>
 */
public final class PartyService {

    /** Итог операции: команда сама решает, каким текстом его показать. */
    public enum Result {
        /** Всё получилось. */
        OK,
        /** Игрок ни в какой группе. */
        NOT_IN_PARTY,
        /** Действие доступно только лидеру. */
        NOT_LEADER,
        /** Игрок уже в группе (своей или чужой). */
        ALREADY_IN_PARTY,
        /** В группе нет места. */
        PARTY_FULL,
        /** Приглашение самому себе. */
        SELF,
        /** Приглашаемый уже состоит в группе. */
        TARGET_IN_PARTY,
        /** Такое приглашение уже висит и ещё действует. */
        ALREADY_INVITED,
        /** У группы слишком много висящих приглашений. */
        INVITE_LIMIT,
        /** Приглашения нет. */
        NO_INVITE,
        /** Приглашение было, но истекло. */
        EXPIRED,
        /** Цель не в этой группе. */
        TARGET_NOT_IN_PARTY
    }

    /**
     * Приглашение в группу.
     *
     * @param partyId  куда зовут
     * @param from     кто позвал
     * @param fromName имя пригласившего на момент приглашения
     * @param expiresAt когда приглашение перестаёт действовать (мс)
     */
    public record Invite(UUID partyId, UUID from, String fromName, long expiresAt) {

        public boolean expired(long now) {
            return now >= expiresAt;
        }

        public long secondsLeft(long now) {
            long left = expiresAt - now;
            return left <= 0L ? 0L : (left + 999L) / 1000L;
        }
    }

    private volatile PartyConfig config;
    private final PartyStore store;
    private final Map<UUID, Invite> invites = new LinkedHashMap<>();

    public PartyService(PartyConfig config, PartyStore store) {
        this.config = config == null ? PartyConfig.defaults() : config;
        this.store = store;
    }

    public PartyConfig config() {
        return config;
    }

    /**
     * Применяет новый конфиг, не теряя приглашений.
     *
     * <p>Приглашения живут в этом объекте, поэтому перезагрузка конфига
     * должна менять только числа: если бы сервис создавался заново, игроки
     * потеряли бы уже отправленные приглашения.</p>
     */
    public void applyConfig(PartyConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    public PartyStore store() {
        return store;
    }

    /* --------------------------------- группы --------------------------------- */

    /** Группа игрока; {@code null} — он один. */
    public Party of(UUID player) {
        return store == null ? null : store.of(player);
    }

    /** Создаёт группу; {@code null} — игрок уже в группе. */
    public Party create(UUID leader, String name) {
        return store == null ? null : store.create(leader, name);
    }

    /**
     * Выход из группы.
     *
     * <p>Если ушёл лидер, лидерство переходит следующему по порядку вступления:
     * группа не должна умирать из-за одного человека. Ушёл последний — группа
     * распускается.</p>
     */
    public Result leave(UUID member) {
        Party party = of(member);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        boolean wasLeader = party.isLeader(member);
        party.remove(member);
        if (party.size() == 0) {
            store.remove(party.id());
            store.markDirty();
            return Result.OK;
        }
        if (wasLeader) {
            party.setLeader(party.memberIds().get(0));
            party.leaderFirst();
        }
        store.markDirty();
        return Result.OK;
    }

    /** Исключение участника лидером. */
    public Result kick(UUID leader, UUID target) {
        Party party = of(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(leader)) {
            return Result.NOT_LEADER;
        }
        if (leader.equals(target)) {
            return Result.SELF;
        }
        if (!party.isMember(target)) {
            return Result.TARGET_NOT_IN_PARTY;
        }
        party.remove(target);
        store.markDirty();
        return Result.OK;
    }

    /** Роспуск группы лидером. */
    public Result disband(UUID leader) {
        Party party = of(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(leader)) {
            return Result.NOT_LEADER;
        }
        store.remove(party.id());
        store.markDirty();
        return Result.OK;
    }

    /** Принудительный роспуск админом: снимает группу по любому участнику. */
    public boolean forceDisband(UUID player) {
        Party party = of(player);
        if (party == null) {
            return false;
        }
        boolean removed = store.remove(party.id());
        store.markDirty();
        return removed;
    }

    /* ------------------------------- приглашения ------------------------------ */

    /**
     * Приглашает игрока в группу.
     *
     * @param from       кто зовёт (должен быть лидером)
     * @param target     кого зовут
     * @param targetName имя приглашаемого — нужно для будущих сообщений
     * @param now        текущее время в миллисекундах
     */
    public Result invite(UUID from, String fromName, UUID target, String targetName, long now) {
        if (from == null || target == null) {
            return Result.NO_INVITE;
        }
        if (from.equals(target)) {
            return Result.SELF;
        }
        Party party = of(from);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(from)) {
            return Result.NOT_LEADER;
        }
        if (of(target) != null) {
            return Result.TARGET_IN_PARTY;
        }
        if (party.size() >= config.maxSize()) {
            return Result.PARTY_FULL;
        }
        Invite existing = invites.get(target);
        if (existing != null && existing.partyId().equals(party.id()) && !existing.expired(now)) {
            return Result.ALREADY_INVITED;
        }
        if (pendingInvites(party.id(), now) >= config.inviteMaxPending()) {
            return Result.INVITE_LIMIT;
        }
        invites.put(target, new Invite(party.id(), from,
                fromName == null ? "" : fromName, now + config.inviteExpireMillis()));
        return Result.OK;
    }

    /** Действующее приглашение игрока; истёкшее удаляется и не возвращается. */
    public Invite inviteOf(UUID target, long now) {
        if (target == null) {
            return null;
        }
        Invite invite = invites.get(target);
        if (invite == null) {
            return null;
        }
        if (invite.expired(now)) {
            invites.remove(target);
            return null;
        }
        return invite;
    }

    /** Сколько приглашений ещё висит у группы. */
    public int pendingInvites(UUID partyId, long now) {
        int count = 0;
        for (Invite invite : invites.values()) {
            if (!invite.expired(now) && invite.partyId().equals(partyId)) {
                count++;
            }
        }
        return count;
    }

    /** Сколько приглашений ждёт конкретного игрока. */
    public int invitesFor(UUID target, long now) {
        Invite invite = inviteOf(target, now);
        return invite == null ? 0 : 1;
    }

    /** Принимает приглашение; {@link Result#OK} — игрок в группе. */
    public Result accept(UUID target, String name, long now) {
        if (target == null) {
            return Result.NO_INVITE;
        }
        Invite invite = invites.get(target);
        if (invite == null) {
            return Result.NO_INVITE;
        }
        if (invite.expired(now)) {
            invites.remove(target);
            return Result.EXPIRED;
        }
        Party party = store.byId(invite.partyId());
        if (party == null) {
            invites.remove(target);
            return Result.NO_INVITE;
        }
        if (of(target) != null) {
            invites.remove(target);
            return Result.ALREADY_IN_PARTY;
        }
        if (party.size() >= config.maxSize()) {
            return Result.PARTY_FULL;
        }
        if (!party.add(target, name)) {
            return Result.PARTY_FULL;
        }
        invites.remove(target);
        store.markDirty();
        return Result.OK;
    }

    /** Отклоняет приглашение; {@code true} — оно было и снято. */
    public boolean deny(UUID target) {
        return target != null && invites.remove(target) != null;
    }

    /** Снимает приглашения игрока (например, при выходе из группы). */
    public void forget(UUID target) {
        if (target != null) {
            invites.remove(target);
        }
    }

    /** Все, кого зовут в группы прямо сейчас — для админской сводки. */
    public List<UUID> invitedPlayers(long now) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Invite> entry : invites.entrySet()) {
            if (!entry.getValue().expired(now)) {
                out.add(entry.getKey());
            }
        }
        return List.copyOf(out);
    }

    /* ---------------------------------- бонус --------------------------------- */

    /**
     * Бонус опыта за игру рядом.
     *
     * <p>Считаются только участники группы: случайный прохожий бонуса не даёт,
     * иначе выгода была бы у толпы на спавне. Потолок жёсткий, поэтому «мягкое»
     * преимущество не превращается в удвоение прокачки.</p>
     *
     * @param player       кому считается бонус
     * @param nearbySameParty сколько своих рядом (без самого игрока)
     * @return 0..{@code bonus.max-percent}
     */
    public int bonusPercent(UUID player, int nearbySameParty) {
        if (!config.bonusEnabled() || nearbySameParty <= 0) {
            return 0;
        }
        Party party = of(player);
        if (party == null) {
            return 0;
        }
        int others = Math.min(nearbySameParty, party.size() - 1);
        if (others <= 0) {
            return 0;
        }
        return Math.min(others * config.perMemberPercent(), config.maxBonusPercent());
    }

    /** Максимум, который группа может дать при полном составе рядом. */
    public int potentialBonus(Party party) {
        if (!config.bonusEnabled() || party == null) {
            return 0;
        }
        int others = Math.max(0, party.size() - 1);
        return Math.min(others * config.perMemberPercent(), config.maxBonusPercent());
    }

    /** В одной ли группе два игрока. */
    public boolean sameParty(UUID left, UUID right) {
        if (left == null || right == null || left.equals(right)) {
            return false;
        }
        Party party = of(left);
        return party != null && party.isMember(right);
    }
}
