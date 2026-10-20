package ru.cherdakmd.chrdkregister;

import java.util.List;

/**
 * Товар каталога {@code shop}: то, что можно продать за реальные деньги и выдать на срок.
 *
 * <p>Товар ничего не знает про Bukkit — это чистая запись из {@code config.yml}. Действие
 * товара складывается из трёх необязательных частей:</p>
 * <ul>
 *   <li>{@code group} — временная группа LuckPerms (её видят очередь, телепорты и другие аддоны);</li>
 *   <li>{@code tag} — MiniMessage-разметка ника в чате (цветной ник, плашка мецената);</li>
 *   <li>{@code commands} — консольные команды с {@code %player%} (анонсы, бонусы, ручные выдачи).</li>
 * </ul>
 *
 * <p>{@code permissions} — права, которые получает группа товара. Они нужны, когда товар
 * выдаёт возможность, а не отдельную группу: например, «+8 домов» — это право
 * {@code chrdktele.homes.plus} в группе {@code homes}.</p>
 */
public record ShopProduct(
        String id,
        String name,
        String description,
        int priceRubles,
        int days,
        String group,
        String tag,
        List<String> permissions,
        List<String> commands
) {

    public ShopProduct {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        commands = commands == null ? List.of() : List.copyOf(commands);
        group = group == null ? "" : group.trim();
        tag = tag == null ? "" : tag.trim();
        description = description == null ? "" : description.trim();
        name = name == null || name.isBlank() ? id : name.trim();
    }

    /** Товар выдаёт временную группу LuckPerms. */
    public boolean hasGroup() {
        return !group.isBlank();
    }

    /** Товар меняет оформление ника в чате. */
    public boolean hasTag() {
        return !tag.isBlank();
    }

    /** У товара есть консольные действия. */
    public boolean hasCommands() {
        return !commands.isEmpty();
    }

    /** Срок в днях, подставленный в текст («30», «90»). */
    public String daysText() {
        return Integer.toString(days);
    }
}
