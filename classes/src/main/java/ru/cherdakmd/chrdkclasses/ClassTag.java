package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.UUID;

/**
 * Метка класса в нике (1.4.174).
 *
 * <p>Класс виден не только в меню: ядро спрашивает у аддонов оформление ника
 * (API v6), и модуль дописывает перед ником короткую плашку — {@code [Воин]},
 * {@code [Стрелок]}, {@code [Маг]} или {@code [Жрец]}. Тот же текст уходит в
 * чат, личные сообщения и TAB: ядро рисует их одной функцией, поэтому «в чате
 * метка есть, а в табе нет» невозможно.</p>
 *
 * <p>Плашка ставится перед ником целиком, а не вместо знака ранга: у ядра свой
 * порядок оформления, и ломать его ради «красивого столбика» нельзя — ранг
 * остаётся на месте, метка класса просто идёт первой.</p>
 *
 * <p>Оформление читается из {@code messages.yml} ({@code tag.<ид класса>}),
 * поэтому цвет и форма — вопрос файла, а не кода. Строка чистая и проверяется
 * тестами без сервера; поиск игрока по нику живёт отдельно.</p>
 */
final class ClassTag {

    /** Ид оформления в реестре ядра: повторная регистрация заменяет прежнюю. */
    static final String ID = "classes";

    private final ClassesPlugin plugin;

    ClassTag(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Собирает имя с меткой класса.
     *
     * @param playerName    ник без разметки — по нему ищется класс
     * @param decoratedName имя, уже оформленное ядром (ранг, товар, другие аддоны)
     * @return имя с меткой или исходное имя, если класса нет
     */
    String decorate(String playerName, String decoratedName) {
        ClassConfig config = plugin.config();
        Messages messages = plugin.messages();
        // Выключение плагина и /classadmin reload идут через те же объекты:
        // без этой проверки ник мог бы остаться без метки или уронить чат.
        if (config == null || messages == null || !config.tagEnabled()
                || playerName == null || playerName.isBlank()) {
            return decoratedName;
        }
        PlayerClass playerClass = classOf(playerName);
        if (playerClass == null) {
            return decoratedName;
        }
        return prepend(messages.raw("tag." + playerClass.id()), decoratedName);
    }

    /**
     * Ставит метку перед именем.
     *
     * <p>Пустая метка имя не трогает, а имя, которое уже начинается с этой
     * метки, не получает вторую: ядро может позвать оформление дважды (чат и
     * TAB), и «[Воин] [Воин] Ник» — не то, что нужно.</p>
     */
    static String prepend(String tag, String name) {
        String safeName = name == null ? "" : name;
        if (tag == null || tag.isBlank()) {
            return safeName;
        }
        String cleanTag = tag.trim();
        if (safeName.isEmpty()) {
            return cleanTag;
        }
        String prefix = cleanTag + " ";
        return safeName.startsWith(prefix) ? safeName : prefix + safeName;
    }

    /** Класс игрока по нику: онлайн ищется в списке, офлайн — в сохранённых никах. */
    private PlayerClass classOf(String playerName) {
        TalentService service = plugin.service();
        if (service == null) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        UUID id = online != null ? online.getUniqueId() : plugin.store().byName(playerName);
        return id == null ? null : service.classOf(id);
    }
}
