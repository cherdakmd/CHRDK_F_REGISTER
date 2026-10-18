package ru.cherdakmd.chrdktierforge;

import java.util.List;

/**
 * Сводка пачки опознаний (1.4.94).
 *
 * <p>Фоновый скан инвентаря мог опознать сразу десяток предметов и на каждый
 * отправлял отдельное сообщение и звук — игрок получал спам. Хелпер считает
 * общее число находок, число проклятых и лучший тир пачки, чтобы показать одно
 * сообщение и один звук.
 *
 * <p>Чистая логика — покрыто {@link IdentifyBatchTest}.
 */
final class IdentifyBatch {

    private final int total;
    private final int cursed;
    private final TierDef.Snapshot best;

    private IdentifyBatch(List<TierDef.Snapshot> rolls) {
        int count = 0;
        int cursedCount = 0;
        TierDef.Snapshot top = null;
        for (TierDef.Snapshot roll : rolls) {
            if (roll == null) {
                continue;
            }
            count++;
            if ("cursed".equals(roll.id())) {
                cursedCount++;
            }
            if (top == null || TierDef.indexOf(roll.id()) > TierDef.indexOf(top.id())) {
                top = roll;
            }
        }
        this.total = count;
        this.cursed = cursedCount;
        this.best = top;
    }

    static IdentifyBatch of(List<TierDef.Snapshot> rolls) {
        return new IdentifyBatch(rolls == null ? List.of() : rolls);
    }

    /** Сколько предметов реально получило тир (null-роллы не считаются). */
    int total() {
        return total;
    }

    boolean isEmpty() {
        return total == 0;
    }

    int cursed() {
        return cursed;
    }

    /** Все находки пачки проклятые — сообщение должно быть соответствующим. */
    boolean allCursed() {
        return total > 0 && cursed == total;
    }

    /** Лучший тир пачки (null — находок нет). */
    TierDef.Snapshot best() {
        return best;
    }
}
