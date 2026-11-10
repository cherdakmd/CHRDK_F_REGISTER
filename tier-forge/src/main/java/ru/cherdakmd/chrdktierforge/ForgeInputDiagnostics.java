package ru.cherdakmd.chrdktierforge;

/**
 * Точная причина отказа наковальни Кузни. Чистая логика без Bukkit: вызывающая
 * сторона сама читает PDC и настройки, а здесь только выбирает ключ сообщения.
 *
 * <p>Раньше все эти случаи падали в один ключ {@code forge-input} с текстом
 * «Нужна одна опознанная тир-вещь слева». Для искры первого тира он был попросту
 * неверным: {@code seedPlan()} требует вещь <b>БЕЗ</b> тира, а остальные семь
 * расходников — наоборот, <b>С</b> тиром. Игрок с вещью без тира и искрой видел
 * требование тир-вещи и не мог понять, что именно положено в наковальню.</p>
 *
 * <p>Порядок проверок — от самой конкретной к общей: сначала стопка и скрытая
 * находка, затем вид предмета и его доступность, и только потом пара
 * «расходник ↔ наличие тира». Логика применения не меняется: тот же отказ, но с
 * сообщением, которое называет причину и следующий шаг.</p>
 */
public final class ForgeInputDiagnostics {

    /** Нет вещи слева или настоящего расходника справа. */
    public static final String MISSING = "forge-input";
    /** Слева стопка: Кузня обрабатывает ровно один предмет. */
    public static final String STACK = "forge-input-stack";
    /** Находка ещё не опознана — надо принять её в инвентарь. */
    public static final String UNIDENTIFIED = "forge-input-unidentified";
    /** У предмета нет метаданных (не должно случаться, но молча падать нельзя). */
    public static final String NO_META = "forge-input-meta";
    /** Материал не входит ни в одну из четырёх групп Кузни. */
    public static final String WRONG_KIND = "forge-input-kind";
    /** Группа подходит, но отключена в {@code craft.*} конфига владельца. */
    public static final String KIND_DISABLED = "forge-input-kind-off";
    /** Расходнику нужна вещь С тиром; для первого тира нужна искра. */
    public static final String NEED_TIER = "forge-input-need-tier";
    /** Искре нужна вещь БЕЗ тира; для повышения нужно Возвышение. */
    public static final String SEED_TIERED = "forge-input-seed-tiered";
    /** Искра без канонического UUID — не настоящий прототип. */
    public static final String SEED_INVALID = "forge-input-seed-invalid";
    /** Из UUID искры не выводится корректный 12-hex UID модификатора. */
    public static final String SEED_UID = "forge-input-seed-uid";
    /** Кузнечному лому нечего чинить: повреждений нет. */
    public static final String REPAIR_UNDAMAGED = "forge-input-repair";
    /** Для этого вида вещи нет другой кузнечной клички. */
    public static final String NAME_POOL_EMPTY = "forge-input-name";

    private ForgeInputDiagnostics() {
    }

    /**
     * @param amount       количество предметов в левом слоте
     * @param unidentified находка ещё не опознана
     * @param hasMeta      у предмета есть {@code ItemMeta}
     * @param kind         вид предмета (null — материал не подходит)
     * @param kindAllowed  группа вида включена в {@code craft.*}
     * @param seed         расходник — искра первого тира
     * @param hasTier      на предмете уже есть PDC-метка тира
     * @return ключ сообщения об отказе или {@code null}, если входы подходят
     */
    public static String of(int amount, boolean unidentified, boolean hasMeta,
                            ItemKind kind, boolean kindAllowed,
                            boolean seed, boolean hasTier) {
        if (amount != 1) return STACK;
        if (unidentified) return UNIDENTIFIED;
        if (!hasMeta) return NO_META;
        if (kind == null) return WRONG_KIND;
        if (!kindAllowed) return KIND_DISABLED;
        if (seed) return hasTier ? SEED_TIERED : null;
        return hasTier ? null : NEED_TIER;
    }
}
