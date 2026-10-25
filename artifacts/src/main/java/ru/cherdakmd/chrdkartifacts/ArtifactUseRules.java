package ru.cherdakmd.chrdkartifacts;

import org.bukkit.event.block.Action;

/**
 * Чистые правила активации активных артефактов: какой правый клик считается
 * «кликом артефакта», а какой должен остаться обычному блоку. Вынесено из
 * {@link ActiveEffectsListener}, чтобы поведение проверялось юнит-тестами без
 * запуска сервера.
 *
 * <p>Правило простое: артефакт срабатывает по правому клику в воздух и по
 * «пустым» блокам (камень, земля, руда). Сундук, дверь, верстак, наковальня,
 * кафедра и прочие интерактивные блоки обслуживает игра; приседание перед
 * блоком — тоже способ взаимодействия (поставить блок, открыть сундук). В этих
 * случаях артефакт молчит и ничего не отменяет.</p>
 */
public final class ArtifactUseRules {

    private ArtifactUseRules() {
    }

    /** Правый клик — в воздух или по блоку. */
    public static boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    /**
     * Клик надо отдать блоку, а не артефакту.
     *
     * @param action            действие события
     * @param interactableBlock у кликнутого блока есть ванильное взаимодействие
     *                          (сундук, дверь, верстак, наковальня, кровать…)
     * @param sneaking          игрок присел (ставит блок / открывает контейнер)
     */
    public static boolean passesToBlock(Action action, boolean interactableBlock, boolean sneaking) {
        return action == Action.RIGHT_CLICK_BLOCK && (interactableBlock || sneaking);
    }

    /**
     * Должен ли артефакт в руке сработать на этот клик.
     *
     * <p>Клик по интерактивному блоку или клик в приседе блоку и остаётся;
     * всё остальное (воздух, обычный блок без взаимодействия) — артефакту.</p>
     */
    public static boolean shouldActivate(Action action, boolean interactableBlock, boolean sneaking) {
        return isRightClick(action) && !passesToBlock(action, interactableBlock, sneaking);
    }
}
