package ru.cherdakmd.chrdktreasures;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Сетовый бонус региона: награда за то, что игрок собрал сразу несколько
 * находок одного биома.
 *
 * <p>У каждого региона четыре предмета — обычный, редкий, эпический и
 * легендарный. Два надетых предмета дают частичный бонус, все четыре — полный.
 * Полный бонус обязан быть не слабее частичного по каждому виду эффекта: это
 * проверяется в конструкторе, поэтому в каталоге не появится «награда», которая
 * ухудшает набор.</p>
 *
 * <p>Одновременно работает только один сет — сильнейший по числу надетых
 * предметов. Иначе игрок в двух сетах сразу получал бы сумму аур и обходил
 * задуманный потолок. Мифические предметы в сеты не входят: у них нет биома.</p>
 *
 * @param region  регион сета
 * @param partial бонус за {@value #PARTIAL_PIECES} предмета
 * @param full    бонус за {@value #FULL_PIECES} предмета
 */
public record TreasureSet(TreasureRegion region, List<TreasureEffect> partial,
                          List<TreasureEffect> full) {

    /** Сколько предметов нужно для частичного бонуса. */
    public static final int PARTIAL_PIECES = 2;

    /** Сколько предметов нужно для полного бонуса. */
    public static final int FULL_PIECES = 4;

    public TreasureSet {
        if (region == null) {
            throw new IllegalArgumentException("сет без региона");
        }
        partial = List.copyOf(partial == null ? List.of() : partial);
        full = List.copyOf(full == null ? List.of() : full);
        if (partial.isEmpty() || full.isEmpty()) {
            throw new IllegalArgumentException("у сета нет бонуса: " + region.id());
        }
        checkUnique(region, partial, "частичный");
        checkUnique(region, full, "полный");
        for (TreasureEffect bonus : partial) {
            TreasureEffect stronger = find(full, bonus.kind());
            if (stronger == null || stronger.value() < bonus.value()) {
                throw new IllegalArgumentException("полный бонус слабее частичного: "
                        + region.id() + "/" + bonus.kind());
            }
        }
        if (full.size() <= partial.size()) {
            throw new IllegalArgumentException("полный бонус не богаче частичного: "
                    + region.id());
        }
    }

    private static void checkUnique(TreasureRegion region, List<TreasureEffect> effects,
                                    String what) {
        Set<TreasureEffect.Kind> kinds = new HashSet<>();
        for (TreasureEffect effect : effects) {
            if (!kinds.add(effect.kind())) {
                throw new IllegalArgumentException("повтор вида в " + what + " бонусе "
                        + region.id() + ": " + effect.kind());
            }
        }
    }

    private static TreasureEffect find(List<TreasureEffect> effects, TreasureEffect.Kind kind) {
        for (TreasureEffect effect : effects) {
            if (effect.kind() == kind) {
                return effect;
            }
        }
        return null;
    }

    /** Бонус, который действует при таком числе надетых предметов. */
    public List<TreasureEffect> bonusFor(int pieces) {
        if (pieces >= FULL_PIECES) {
            return full;
        }
        return pieces >= PARTIAL_PIECES ? partial : List.<TreasureEffect>of();
    }

    /** Сколько предметов сета должно быть надето, чтобы бонус заработал. */
    public static int threshold(boolean full) {
        return full ? FULL_PIECES : PARTIAL_PIECES;
    }
}
