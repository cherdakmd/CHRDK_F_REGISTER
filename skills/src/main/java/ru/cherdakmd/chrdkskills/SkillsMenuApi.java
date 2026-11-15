package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;

/**
 * Публичный контракт меню «Навыки» (после вырезания дерева перков и классов).
 * Регистрируется {@code CHRDK_Skills} через {@code ServicesManager}.
 */
public interface SkillsMenuApi {

    /** Вкладки единого инвентаря на 54 слота. */
    enum Tab {
        SKILLS,
        TOP,
        /** Покупка зелий опыта: те же предметы, что падают с мобов. */
        POTIONS,
        /** Серверный уровень престижа: звания, ступени по всем навыкам, бонусы. */
        PRESTIGE
    }

    /**
     * Открыть (или перерисовать уже открытое) меню.
     *
     * @param tab  вкладка; {@code null} трактуется как {@link Tab#SKILLS}
     * @param page страница; отрицательная прижимается к нулю
     */
    void open(Player player, Tab tab, int page);
}
