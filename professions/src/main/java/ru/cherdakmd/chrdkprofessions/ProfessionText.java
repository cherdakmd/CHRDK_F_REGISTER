package ru.cherdakmd.chrdkprofessions;

import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Тексты профессий (1.4.165): имена, ранги и прибавки в одном месте.
 *
 * <p>{@link ProfessionService} возвращает чистые иды и числа, чтобы его можно
 * было проверять без сервера. Команда, меню, профиль и плейсхолдеры показывают
 * одно и то же, поэтому перевод идов в имена и форматирование прибавок живут
 * здесь, а не дублируются в четырёх местах.</p>
 */
final class ProfessionText {

    /** Имя профессии без разметки. */
    static String namePlain(Messages messages, Profession profession) {
        if (messages == null || profession == null) {
            return "";
        }
        String key = "profession." + profession.id() + ".name";
        return messages.has(key) ? messages.plain(key) : profession.id();
    }

    /** Имя профессии с разметкой — для подстановки в чужой шаблон. */
    static Messages.Trusted nameComponent(Messages messages, Profession profession) {
        if (messages == null || profession == null) {
            return Messages.trusted("");
        }
        return Messages.trusted(messages.raw("profession." + profession.id() + ".name"));
    }

    /**
     * Как получать опыт этого ремесла — с разметкой (1.4.177).
     *
     * <p>Подсказка живёт в messages.yml рядом с названием и описанием: игрок
     * видит её до выбора (в меню) и после него, а не узнаёт из вики.</p>
     */
    static Messages.Trusted hintComponent(Messages messages, Profession profession) {
        if (messages == null || profession == null) {
            return Messages.trusted("");
        }
        String key = "profession." + profession.id() + ".hint";
        return Messages.trusted(messages.has(key) ? messages.raw(key) : "");
    }

    /** Ранг по уровню без разметки. */
    static String rankPlain(Messages messages, int level) {
        if (messages == null) {
            return "";
        }
        String key = ProfessionMath.rankKey(level);
        return messages.has(key) ? messages.plain(key) : key;
    }

    /** Ранг с разметкой — для подстановки в чужой шаблон. */
    static Messages.Trusted rankComponent(Messages messages, int level) {
        return messages == null ? Messages.trusted("")
                : Messages.trusted(messages.raw(ProfessionMath.rankKey(level)));
    }

    /** Строка прибавки с разметкой: «Скидка 40 % на работу в наковальне» или «пока нет». */
    static Messages.Trusted perk(Messages messages, Profession profession, int level) {
        return perk(messages, profession, level, ProfessionMath.MAX_LEVEL);
    }

    /**
     * То же, но с потолком уровня игрока: у ранга прибавка выше (1.4.167).
     *
     * <p>Потолок важен для самой прибавки: без него игрок с рангом видел бы
     * «Скидка 40 %» даже на 33-м уровне, хотя фактически получает больше.</p>
     */
    static Messages.Trusted perk(Messages messages, Profession profession, int level, int maxLevel) {
        return Messages.trusted(perkRaw(messages, profession, level, maxLevel));
    }

    /** Прибавка сырым MiniMessage-текстом при обычном потолке. */
    static String perkRaw(Messages messages, Profession profession, int level) {
        return perkRaw(messages, profession, level, ProfessionMath.MAX_LEVEL);
    }

    /** Прибавка сырым MiniMessage-текстом — для подстановок в чужие шаблоны. */
    static String perkRaw(Messages messages, Profession profession, int level, int maxLevel) {
        if (messages == null || profession == null) {
            return "";
        }
        int value = ProfessionMath.perkValue(profession, level, maxLevel);
        return value <= 0 ? messages.raw("perk.none")
                : messages.raw("perk." + profession.id(), "value", String.valueOf(value));
    }

    /**
     * Подставляет имена вместо идов в аргументах результата.
     *
     * <p>Неизвестный ид (опечатка в {@code /prof choose}) остаётся как есть:
     * игрок должен увидеть ровно то, что ввёл.</p>
     */
    static List<String> localise(Messages messages, ProfessionResult result) {
        List<String> args = result == null ? new ArrayList<>() : new ArrayList<>(result.args());
        if (messages == null) {
            return args;
        }
        for (int index = 1; index < args.size(); index += 2) {
            String id = args.get(index);
            if (id == null || id.isBlank()) {
                continue;
            }
            String key = "profession." + id.toLowerCase(Locale.ROOT) + ".name";
            if (messages.has(key)) {
                args.set(index, messages.plain(key));
            }
        }
        return args;
    }

    private ProfessionText() {
    }
}
