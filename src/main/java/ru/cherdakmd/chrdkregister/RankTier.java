package ru.cherdakmd.chrdkregister;

/**
 * Одна ступень лестницы донатных рангов (1.4.167).
 *
 * <p>Ступень ничего не решает сама: числа (дома, приваты, скидки) живут в конфигах
 * модулей, а здесь — только то, что рисует ядро: имя, знак перед ником, цвет и
 * строка входа. {@code format} — MiniMessage-шаблон с {@code %name%}; законченная
 * пара тегов нужна, чтобы не полагаться на «мягкое» закрытие разметки.</p>
 *
 * @param level       ступень 1..5
 * @param id          машинный ид (wanderer, pathfinder, …)
 * @param name        имя без разметки («Странник») — для логов и простых плейсхолдеров
 * @param format      оформление имени: {@code <gray>%name%</gray>}
 * @param badge       знак перед ником: ✦, ❖, ✧, ★, ⚜
 * @param joinLine    строка входа в чате; пусто — не анонсировать
 */
public record RankTier(int level, String id, String name, String format, String badge, String joinLine) {

    public RankTier {
        level = Math.max(1, Math.min(RankLadder.MAX_LEVEL, level));
        id = id == null || id.isBlank() ? "rank-" + level : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        format = format == null || format.isBlank() ? "%name%" : format.trim();
        badge = badge == null ? "" : badge.trim();
        joinLine = joinLine == null ? "" : joinLine.trim();
    }

    /** Имя игрока в оформлении этого ранга. */
    public String decorate(String playerName) {
        return format.replace("%name%", playerName == null ? "" : playerName);
    }

    /** {@code true}, если ранг анонсирует вход. */
    public boolean hasJoinLine() {
        return !joinLine.isBlank();
    }
}
