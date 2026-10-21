package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;

/**
 * Публичный API навыков для других аддонов (регистрируется в ServicesManager).
 *
 * <p>Нужен механикам, которых нет в ванильных событиях: событие арены, награда
 * за квест, руда из кастомного генератора. Аддоны адресуют навык строкой
 * ({@link SkillType#id()}) и не зависят от внутренних классов; неизвестный ид
 * не бросает исключение, а возвращает 0/{@code false}.</p>
 *
 * <p>Опыт в {@link #addXp} начисляется как есть (без множителя «за действие»),
 * но с учётом уровня и вех: аддон сам решает, сколько дать, а навык — когда
 * поздравить и какую награду выдать.</p>
 */
public interface ChrdkSkillsApi {

    /** Накопленный опыт навыка (0 для неизвестного ида). */
    long xp(Player player, String skillId);

    /** Текущий уровень навыка, 1..max-level. */
    int level(Player player, String skillId);

    /** Суммарный уровень всех навыков. */
    int totalLevel(Player player);

    /**
     * Начисляет опыт за действие с множителями конфига (как событие сервера).
     *
     * @param units число действий (блоков, рыб, пунктов урона)
     * @return сколько уровней прибавилось (0 — ни одного)
     */
    int award(Player player, String skillId, long units);

    /** Начисляет фиксированный опыт без множителей. */
    boolean addXp(Player player, String skillId, long xp);

    /** Суммарная величина активного эффекта навыка (0, если перк не открыт). */
    double perk(Player player, String skillId, String kind);
}
