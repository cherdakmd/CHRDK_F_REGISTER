package ru.cherdakmd.chrdkclasses;

import java.util.ArrayList;
import java.util.List;

/**
 * Ответ действия с классом (1.4.164).
 *
 * <p>Сервис не знает про сообщения и игроков: он возвращает ключ текста и пары
 * подстановок. Команда, меню и консоль показывают один и тот же ключ, поэтому
 * «в меню написано одно, а в чате другое» невозможно.</p>
 *
 * @param ok   действие выполнено
 * @param key  ключ из messages.yml
 * @param args пары «имя, значение» для подстановок
 */
public record ClassResult(boolean ok, String key, List<String> args) {

    public ClassResult {
        key = key == null || key.isBlank() ? "unknown" : key;
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** Успешный исход. */
    public static ClassResult ok(String key, String... pairs) {
        return new ClassResult(true, key, pairs == null ? List.of() : List.of(pairs));
    }

    /** Отказ с объяснением причины. */
    public static ClassResult fail(String key, String... pairs) {
        return new ClassResult(false, key, pairs == null ? List.of() : List.of(pairs));
    }

    /** Те же подстановки в виде массива — для {@code Messages.send}. */
    public Object[] array() {
        List<String> copy = new ArrayList<>(args);
        return copy.toArray();
    }
}
