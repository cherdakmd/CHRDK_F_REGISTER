package ru.cherdakmd.chrdkregister.pve;

/**
 * Получатели награды за одно подтверждённое PvE-событие.
 *
 * <p>Реестр нужен, чтобы один убитый моб не качал шесть независимых прогрессий
 * с шестью разными дневными лимитами. Каждый получатель ведёт свою квитанцию,
 * но проходит через общий {@link PveAwardContract} и общий дневной бюджет.</p>
 *
 * <p>Порядок перечисления не задаёт приоритет начисления.</p>
 */
public enum PveRecipient {

    /** Собственный XP класса ({@code classes}). */
    CLASS_XP,
    /** Опыт боевых навыков ({@code skills}). */
    SKILL_XP,
    /** Общие цели ({@code common-goals}). */
    COMMON_GOALS,
    /** Сезонные задания ({@code battlepass}). */
    BATTLEPASS,
    /** Пыль деревьев перков ({@code skills}, каркас tree). QoL-only. */
    PERK_POWDER
}
