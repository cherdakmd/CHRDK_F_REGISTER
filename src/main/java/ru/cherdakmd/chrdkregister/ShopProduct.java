package ru.cherdakmd.chrdkregister;

import java.util.List;

/**
 * Товар каталога {@code shop}: то, что можно продать за реальные деньги и выдать на срок.
 *
 * <p>Товар ничего не знает про Bukkit — это чистая запись из {@code config.yml}. Действие
 * товара складывается из трёх необязательных частей:</p>
 * <ul>
 *   <li>{@code group} — временная группа LuckPerms (её видят телепорты и другие аддоны);</li>
 *   <li>{@code tag} — MiniMessage-разметка ника в чате (цветной ник, плашка мецената);</li>
 *   <li>{@code commands} — консольные команды с {@code %player%} (анонсы, бонусы, ручные выдачи).</li>
 * </ul>
 *
 * <p>{@code permissions} — права, которые получает группа товара. Они нужны, когда товар
 * выдаёт возможность, а не отдельную группу: например, «+8 домов» — это право
 * {@code chrdktele.homes.plus} в группе {@code homes}.</p>
 *
 * <p><b>Ранг (1.4.167).</b> Товар с полем {@code rank} продаёт ступень донатной
 * лестницы: вместо записи «товар → срок» покупатель получает ранг в аккаунте.
 * Цена и срок карточки остаются обычными, поэтому витрина и RCON-хук магазина
 * работают без изменений.</p>
 *
 * <p><b>Набор.</b> Товар может включать другие товары ({@code includes}) и дни проходки
 * ({@code pass-days}). Набор — это рецепт покупки, а не отдельная сущность: покупатель
 * получает ровно те товары, что перечислены. Обычно у каждой части свой срок;
 * положительный {@code includedDays} задаёт единый период всех листьев этой покупки.
 * Ранг гибридного набора задаётся на корне, не вложенным товаром. {@code includes}
 * хранит то, что написал администратор (для витрины), {@code resolvedIncludes} — плоский
 * список после раскрытия вложенных наборов (для выдачи).</p>
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
        List<String> commands,
        List<String> includes,
        List<String> resolvedIncludes,
        int passDays,
        int rank,
        int includedDays
) {

    /** Товар без набора: прежний конструктор, чтобы не менять существующие вызовы. */
    public ShopProduct(String id, String name, String description, int priceRubles, int days,
                       String group, String tag, List<String> permissions, List<String> commands) {
        this(id, name, description, priceRubles, days, group, tag, permissions, commands,
                List.of(), List.of(), 0, 0);
    }

    /** Товар без ранга: конструктор эпохи 1.4.110–1.4.166. */
    public ShopProduct(String id, String name, String description, int priceRubles, int days,
                       String group, String tag, List<String> permissions, List<String> commands,
                       List<String> includes, List<String> resolvedIncludes, int passDays) {
        this(id, name, description, priceRubles, days, group, tag, permissions, commands,
                includes, resolvedIncludes, passDays, 0);
    }

    /** Старый конструктор: составляющие сохраняют собственные сроки. */
    public ShopProduct(String id, String name, String description, int priceRubles, int days,
                       String group, String tag, List<String> permissions, List<String> commands,
                       List<String> includes, List<String> resolvedIncludes, int passDays, int rank) {
        this(id, name, description, priceRubles, days, group, tag, permissions, commands,
                includes, resolvedIncludes, passDays, rank, 0);
    }

    /** Единый период составляющих задан явно; 0 — прежние собственные сроки. */
    public int includedDays(ShopProduct part) {
        return includedDays > 0 ? includedDays : part.days();
    }

    public ShopProduct {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        commands = commands == null ? List.of() : List.copyOf(commands);
        includes = includes == null ? List.of() : List.copyOf(includes);
        resolvedIncludes = resolvedIncludes == null ? List.of() : List.copyOf(resolvedIncludes);
        group = group == null ? "" : group.trim();
        tag = tag == null ? "" : tag.trim();
        description = description == null ? "" : description.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        includedDays = Math.max(0, includedDays);
        passDays = Math.max(0, passDays);
        rank = Math.max(0, Math.min(RankLadder.MAX_LEVEL, rank));
    }

    /** Клановый комплект сохраняет собственный срок как бонусную лицензию анимации.
     * Старые наборы по-прежнему являются только рецептами, без нового права. */
    public boolean retainsBundleEntitlement() {
        var parts = java.util.Set.of("clan-name-style", "clan-emblem", "clan-card-frame");
        return "clan-style-pack".equals(id) && !hasRank() && !hasGroup() && !hasTag() && !hasCommands()
                && permissions.isEmpty() && passDays == 0 && includedDays > 0 && days == includedDays
                && java.util.Set.copyOf(includes).equals(parts) && java.util.Set.copyOf(resolvedIncludes).equals(parts);
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

    /** Набор: включает другие товары или дни проходки. */
    public boolean isBundle() {
        return !includes.isEmpty() || passDays > 0;
    }

    /** В набор входят дни проходки. */
    public boolean hasPassDays() {
        return passDays > 0;
    }

    /** Товар продаёт ступень донатного ранга; 0 — обычный товар. */
    public boolean hasRank() {
        return rank > 0;
    }

    /** Срок в днях, подставленный в текст («30», «90»). */
    public String daysText() {
        return Integer.toString(days);
    }
}
