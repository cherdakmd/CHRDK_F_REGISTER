package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Тексты результатов операций (1.4.164).
 *
 * <p>{@link TalentService} возвращает чистые иды: класс {@code warrior}, талант
 * {@code warrior-stoyka-1}, навык {@code defense}. Так сервис остаётся
 * проверяемым без сервера и без {@code messages.yml}. Команда и меню показывают
 * один и тот же результат, поэтому перевод идов в имена живёт здесь, а не
 * дублируется в двух местах.</p>
 *
 * <p>Неизвестный ид (опечатка в {@code /class choose паладин}) остаётся как
 * есть: игрок должен увидеть ровно то, что ввёл, а не пустую строку.</p>
 */
final class ClassText {

    /** Аргументы сообщения с именами вместо идов; исходный результат не меняется. */
    static List<String> localise(Messages messages, ClassResult result) {
        List<String> args = result == null ? new ArrayList<>() : new ArrayList<>(result.args());
        if (messages == null) {
            return args;
        }
        for (int index = 0; index + 1 < args.size(); index += 2) {
            String kind = args.get(index);
            if (!kind.equals("class") && !kind.equals("talent") && !kind.equals("skill")) {
                continue;
            }
            String id = args.get(index + 1);
            if (id == null || id.isBlank()) {
                continue;
            }
            String key = kind + "." + id.toLowerCase(Locale.ROOT) + ".name";
            if (messages.has(key)) {
                args.set(index + 1, messages.plain(key));
            }
        }
        return args;
    }

    private ClassText() {
    }
}
