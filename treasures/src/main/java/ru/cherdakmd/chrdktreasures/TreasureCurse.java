package ru.cherdakmd.chrdktreasures;

import java.util.Locale;

/**
 * Проклятие-расплата за силу находки.
 *
 * <p>Легендарные и мифические предметы не бывают «чистым плюсом»: у каждого
 * есть цена. Проклятие срабатывает по одному из трёх поводов — удар, полученный
 * урон или добытый блок — с шансом, записанным в самом предмете. Эффект всегда
 * накладывается на владельца и всегда временный: проклятие не ломает предмет,
 * не отбирает находку и не может убить. Это дискомфорт, а не наказание.</p>
 *
 * @param kind   вид проклятия
 * @param chance шанс срабатывания за повод, 0..1
 */
public record TreasureCurse(Kind kind, double chance) {

    /** Виды проклятий и их поводы. */
    public enum Kind {

        /** Удар: голод I на 8 с. */
        HUNGER(Trigger.HIT, 160),
        /** Полученный урон: слабость I на 5 с. */
        WEAKNESS(Trigger.DAMAGED, 100),
        /** Добыча блока: замедление I на 4 с. */
        SLOWNESS(Trigger.MINE, 80),
        /** Удар: слепота на 3 с. */
        BLINDNESS(Trigger.HIT, 60),
        /** Добыча блока: свечение на 8 с — владельца видно издалека. */
        GLOWING(Trigger.MINE, 160),
        /** Полученный урон: левитация на 1.5 с — сбивает с шага, но не роняет. */
        LEVITATION(Trigger.DAMAGED, 30);

        private final Trigger trigger;
        private final int durationTicks;

        Kind(Trigger trigger, int durationTicks) {
            this.trigger = trigger;
            this.durationTicks = durationTicks;
        }

        /** Повод срабатывания. */
        public Trigger trigger() {
            return trigger;
        }

        /** Длительность эффекта в тиках. */
        public int durationTicks() {
            return durationTicks;
        }

        /** Ид для messages.yml: {@code curse.<ид>}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** Ключ названия в messages.yml. */
        public String textKey() {
            return "curse." + id();
        }
    }

    /** Когда проверяется проклятие. */
    public enum Trigger {
        /** При ударе по мобу. */
        HIT,
        /** При получении урона. */
        DAMAGED,
        /** При добыче блока. */
        MINE
    }

    public TreasureCurse {
        if (kind == null) {
            throw new IllegalArgumentException("проклятие без вида");
        }
        if (chance <= 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("шанс проклятия вне 0..1: " + chance);
        }
    }
}
