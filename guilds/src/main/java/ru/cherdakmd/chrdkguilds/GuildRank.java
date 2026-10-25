package ru.cherdakmd.chrdkguilds;

import java.util.Locale;

/**
 * Ранг в гильдии (1.4.163).
 *
 * <p>Три ступени, а не свободная иерархия: лидер, офицер и рядовой. Право — это
 * свойство ранга, а не отдельный список флагов, поэтому «офицер без права
 * приглашать» невозможен по построению, а в конфиге нечего рассинхронизировать.</p>
 *
 * <p>Вес нужен для сравнения: приглашать и исключать можно только тех, кто
 * строго ниже.</p>
 */
public enum GuildRank {

    /** Глава гильдии: казна, войны, состав и роспуск. */
    LEADER(3),
    /** Офицер: приглашения, исключения рядовых, взносы в казну. */
    OFFICER(2),
    /** Рядовой участник: взносы и выход. */
    MEMBER(1);

    private final int weight;

    GuildRank(int weight) {
        this.weight = weight;
    }

    /** Вес для сравнения рангов. */
    public int weight() {
        return weight;
    }

    /** Ид ранга для конфига и текстов ({@code rank.leader} в messages.yml). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Может ли ранг приглашать новых участников. */
    public boolean canInvite() {
        return this != MEMBER;
    }

    /** Может ли ранг исключать носителя {@code target}. */
    public boolean canKick(GuildRank target) {
        return target != null && target.weight < weight;
    }

    /** Может ли ранг повышать и понижать носителя {@code target}. */
    public boolean canManage(GuildRank target) {
        return target != null && target.weight < weight;
    }

    /** Может ли ранг распоряжаться казной и войнами. */
    public boolean canManageGuild() {
        return this == LEADER;
    }

    /** Разбирает ид ранга; неизвестное значение — рядовой. */
    public static GuildRank parse(String value) {
        if (value == null || value.isBlank()) {
            return MEMBER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return MEMBER;
        }
    }
}
