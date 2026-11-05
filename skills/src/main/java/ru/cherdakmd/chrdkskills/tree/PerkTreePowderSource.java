package ru.cherdakmd.chrdkskills.tree;

import ru.cherdakmd.chrdkregister.pve.PveAwardContract;
import ru.cherdakmd.chrdkregister.pve.PveVerdict;

/**
 * Начисление пыли дерева перков из подтверждённого PvE-события.
 *
 * <p>Это <b>единственный</b> источник валюты деревьев на Фазе 1: пыль приходит
 * из уже подтверждённой смерти моба, а не из нового листенера на каждый удар.
 * Начисление строго QoL: оно не меняет уровень домена, токены, опыт навыков,
 * дроп, алмазы и донатные множители. Двойной потолок — дневной бюджет игрока и
 * потолок запаса пыли конкретного дерева.</p>
 *
 * <p>Класс без состояния: идемпотентность по {@code deathId} и хранение
 * квитанций обеспечивает вызывающая сторона (роутер / будущий
 * {@code PerkTreeStore}).</p>
 */
public final class PerkTreePowderSource {

    /** Базовая пыль за одного подтверждённого моба. */
    public static final long BASE_POWDER = 5L;

    public record Result(PerkTreeState state, long credited, boolean applied, String reason) {
    }

    private PerkTreePowderSource() {
    }

    public static Result credit(PerkTreeState state, TreeDomain domain, PveVerdict verdict,
                                long basePowder, long dailyPowderCap, long creditedToday) {
        if (verdict == null || !verdict.allowed()) {
            return refused(state, "PvE-событие не подтверждено");
        }
        if (state == null || domain == null || state.domain() != domain) {
            return refused(state, "состояние и домен не совпадают");
        }
        PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElse(null);
        if (definition == null) {
            return refused(state, "дерево не зарегистрировано");
        }
        long remaining = PveAwardContract.remainingDailyPowder(dailyPowderCap, creditedToday);
        long grant = Math.min(Math.max(0L, basePowder), remaining);
        if (grant <= 0L) {
            return refused(state, "дневной бюджет пыли исчерпан");
        }
        PerkTreeState next = state.creditPowder(grant, definition.powderCap());
        long credited = next.powder() - state.powder();
        if (credited <= 0L) {
            return refused(state, "запас пыли дерева полон");
        }
        if (next.level() != state.level() || next.tokensSpent() != state.tokensSpent()
                || !next.levels().equals(state.levels())) {
            return refused(state, "начисление не должно менять прогресс дерева");
        }
        return new Result(next, credited, true, "ok");
    }

    private static Result refused(PerkTreeState state, String reason) {
        return new Result(state, 0L, false, reason);
    }
}
