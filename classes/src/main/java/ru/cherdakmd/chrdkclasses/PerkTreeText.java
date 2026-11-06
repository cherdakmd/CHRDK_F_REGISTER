package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.List;

/**
 * Русские названия и описания дерева перков из {@code messages.yml}.
 *
 * <p>Раньше иконка узла называлась голым идентификатором ({@code defense-telegraph}),
 * а весь лор состоял из чисел и технических пометок. Теперь и меню, и команды
 * берут один и тот же перевод по ключу {@code perktree.node.<id>.name} и описание
 * по ключу {@code perktree.node.<id>.desc}. Если администратор ещё не обновил
 * свой {@code messages.yml}, показывается понятный откат, а не пустая строка.</p>
 */
final class PerkTreeText {

    private PerkTreeText() {
    }

    /** Имя домена без разметки: подставляется как %domain% внутрь готового шаблона. */
    static String domain(Messages messages, TreeDomain domain) {
        return messages.has("perktree.domain." + domain.id())
                ? messages.raw("perktree.domain." + domain.id())
                : domain.id();
    }

    /** Название узла как компонент для displayName иконки. */
    static Component nodeName(Messages messages, String nodeId) {
        return messages.has("perktree.node." + nodeId + ".name")
                ? messages.get("perktree.node." + nodeId + ".name")
                : Component.text(nodeId);
    }

    /** Название узла чистым текстом — для чата и списка зависимостей. */
    static String nodeNamePlain(Messages messages, String nodeId) {
        return messages.has("perktree.node." + nodeId + ".name")
                ? Messages.stripTags(messages.raw("perktree.node." + nodeId + ".name"))
                : nodeId;
    }

    /** Описание узла: «что даёт» и ограничение QoL-only. */
    static List<Component> description(Messages messages, String nodeId) {
        return messages.has("perktree.node." + nodeId + ".desc")
                ? messages.getList("perktree.node." + nodeId + ".desc")
                : List.of(messages.get("perktree-node-no-desc"));
    }
}
