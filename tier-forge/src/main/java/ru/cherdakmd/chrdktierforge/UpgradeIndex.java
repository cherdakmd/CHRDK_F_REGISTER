package ru.cherdakmd.chrdktierforge;

/**
 * Битовая карта уровней улучшений на предмете (1.4.82).
 *
 * <p>До 1.4.82 {@code TierItems.upgradesOf} перебирал все 85 улучшений и на каждое
 * делал {@code item.getItemMeta()} — то есть 85 копий меты на предмет. Живой пульс
 * кузни вызывал это шесть раз на игрока каждые две секунды. Теперь на предмете
 * лежит {@code up_index} (два {@code long}, 128 бит), и читаются только реально
 * стоящие свойства.
 *
 * <p>Чистая логика без Bukkit — покрыта {@link UpgradeIndexTest}.
 */
final class UpgradeIndex {

    /** Сколько long-слов нужно на все 85 улучшений (2). */
    static final int WORDS = (ForgeUpgrade.ALL.size() + 63) / 64;

    private UpgradeIndex() {
    }

    /** Пустая карта (все биты нулевые). */
    static long[] empty() {
        return new long[WORDS];
    }

    /** Ставит бит улучшения с индексом {@code index}; вне диапазона — игнор. */
    static boolean set(long[] words, int index) {
        if (words == null || index < 0 || index >= ForgeUpgrade.ALL.size()
                || index / 64 >= words.length) {
            return false;
        }
        words[index / 64] |= 1L << (index % 64);
        return true;
    }

    /** Стоит ли бит улучшения (безопасно к коротким/чужим массивам). */
    static boolean has(long[] words, int index) {
        if (words == null || index < 0 || index >= ForgeUpgrade.ALL.size()
                || index / 64 >= words.length) {
            return false;
        }
        return (words[index / 64] & (1L << (index % 64))) != 0;
    }

    /** Есть ли хоть один бит (пустая карта не сохраняется в PDC). */
    static boolean any(long[] words) {
        if (words == null) {
            return false;
        }
        for (long word : words) {
            if (word != 0L) {
                return true;
            }
        }
        return false;
    }
}
