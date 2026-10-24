package ru.cherdakmd.chrdkguilds;

import java.util.ArrayList;
import java.util.List;

/**
 * Ответ гильдейского действия (1.4.163).
 *
 * <p>Сервис не знает про сообщения и игроков: он возвращает ключ текста и пары
 * подстановок. Команда, меню и консоль показывают один и тот же ключ, поэтому
 * «в меню написано одно, а в чате другое» невозможно.</p>
 *
 * @param ok   действие выполнено
 * @param key  ключ из messages.yml
 * @param args пары «имя, значение» для подстановок
 */
public record GuildResult(boolean ok, String key, List<String> args) {

    public GuildResult {
        key = key == null || key.isBlank() ? "unknown" : key;
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** Успешный исход. */
    public static GuildResult ok(String key, String... pairs) {
        return new GuildResult(true, key, pairs == null ? List.of() : List.of(pairs));
    }

    /** Отказ с объяснением причины. */
    public static GuildResult fail(String key, String... pairs) {
        return new GuildResult(false, key, pairs == null ? List.of() : List.of(pairs));
    }

    /** Те же подстановки в виде массива — для {@code Messages.send}. */
    public Object[] array() {
        List<String> copy = new ArrayList<>(args);
        return copy.toArray();
    }
}
