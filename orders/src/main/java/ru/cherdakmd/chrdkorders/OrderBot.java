package ru.cherdakmd.chrdkorders;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Заказчик-бот: имя, что он просит и как благодарит.
 *
 * <p>Боты — не сущности мира: это записи из {@code config.yml}. Поэтому доска
 * не спавнит мобов, не теряет заказчиков при перезапуске и не требует
 * отдельного мира. Игрок видит имя и реплику — этого достаточно, чтобы заказ
 * звучал как просьба, а не как строчка из таблицы.</p>
 *
 * @param id         ид из конфига
 * @param name       отображаемое имя
 * @param categories категории, из которых бот просит
 * @param thanks     благодарности после сдачи
 */
public record OrderBot(String id, String name, List<String> categories, List<String> thanks) {

    public OrderBot {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        categories = categories == null ? List.of() : List.copyOf(categories);
        thanks = thanks == null ? List.of() : List.copyOf(thanks);
    }

    /** Благодарность бота; пустая строка — реплики нет. */
    public String thanks(Random random) {
        if (thanks.isEmpty()) {
            return "";
        }
        return random == null ? thanks.get(0) : thanks.get(random.nextInt(thanks.size()));
    }
}
