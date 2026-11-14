package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;

/**
 * Публичный контракт единого меню «Навыки + дерево перков».
 *
 * <p>Регистрируется {@code CHRDK_Skills} через {@code ServicesManager}, чтобы
 * {@code /perktree} из {@code CHRDK_Classes} открывал то же самое окно сразу
 * на вкладке дерева, а не второе меню со своим Holder'ом.</p>
 */
public interface SkillsMenuApi {

    /** Вкладки единого инвентаря на 54 слота. */
    enum Tab {
        SKILLS,
        TREE,
        TOP,
        /** Покупка зелий опыта: те же предметы, что падают с мобов. */
        POTIONS,
        /** Серверный уровень престижа: звания, ступени по всем навыкам, бонусы. */
        PRESTIGE
    }

    /**
     * Открыть (или перерисовать уже открытое) меню.
     *
     * @param tab    вкладка; {@code null} трактуется как {@link Tab#SKILLS}
     * @param domain id домена дерева для {@link Tab#TREE}; {@code null} — ведущий домен роли
     * @param page   страница узлов с нуля; отрицательная прижимается к нулю
     */
    void open(Player player, Tab tab, String domain, int page);
}
