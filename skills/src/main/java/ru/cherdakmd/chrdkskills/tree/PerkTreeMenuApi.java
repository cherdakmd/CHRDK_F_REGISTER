package ru.cherdakmd.chrdkskills.tree;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Контракт вкладки «Дерево перков» для единого меню {@code /skills}.
 *
 * <p>Объявлен здесь, в {@code skills}: модуль классов зависит от навыков, а
 * наоборот — нет. Реализацию регистрирует {@code CHRDK_Classes} через
 * {@code ServicesManager}; если аддон не установлен, вкладка показывает
 * понятную заглушку, а не падает. Инвентарь всегда принадлежит
 * {@code SkillsGui}: второго Holder'а и второго окна не создаётся,
 * переключение вкладок только перерисовывает слоты.</p>
 *
 * <p>Ни один метод не открывает инвентарь и не вызывает {@code openInventory}
 * — это ответственность принимающей стороны. Так одна и та же логика покупки
 * узлов работает и из {@code /skills}, и из отдельного {@code /perktree}.</p>
 */
public interface PerkTreeMenuApi {

    /** Домены, у которых у этого игрока есть состояние дерева. */
    List<TreeDomain> domains(Player player);

    /** Ведущий домен роли игрока или {@code null}, если класс не выбран. */
    TreeDomain primary(Player player);

    /** Число страниц узлов домена. */
    int pages(TreeDomain domain);

    /** Русское название домена (из messages.yml аддона классов). */
    String domainName(TreeDomain domain);

    /** Заголовок вкладки (для подсказок и лора кнопок). */
    Component title(Player player, TreeDomain domain, int page);

    /** Готовые иконки: номер слота 0..53 → предмет. Пусто, если дерево недоступно. */
    Map<Integer, ItemStack> items(Player player, TreeDomain domain, int page);

    /** Обработать клик по слоту; сообщения игроку отправляет реализация. */
    Result click(Player player, TreeDomain domain, int page, int rawSlot);

    /** Многострочная инструкция «как качать и где брать пыль». */
    List<Component> guide(Player player);

    /** Слот кнопки закрытия в нижнем ряду. */
    int closeSlot();

    enum Action {
        /** Ничего не менять (клик по статусу/пустому слоту). */
        NONE,
        /** Перерисовать вкладку с указанными доменом и страницей. */
        REPAINT,
        /** Закрыть меню. */
        CLOSE
    }

    record Result(Action action, TreeDomain domain, int page) {

        public static Result none(TreeDomain domain, int page) {
            return new Result(Action.NONE, domain, page);
        }

        public static Result repaint(TreeDomain domain, int page) {
            return new Result(Action.REPAINT, domain, page);
        }

        public static Result close(TreeDomain domain, int page) {
            return new Result(Action.CLOSE, domain, page);
        }
    }
}
