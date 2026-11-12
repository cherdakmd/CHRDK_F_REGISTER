package ru.cherdakmd.chrdkskills.tree;

import ru.cherdakmd.chrdkskills.SkillType;

import java.util.Optional;

/**
 * Домен дерева перков: к какому аспекту игры оно относится.
 *
 * <p>Каркас намеренно доменно-независимый: дерево знает только идентификатор и
 * источник прогресса. У десяти доменов это существующий {@link SkillType},
 * у трёх зарезервированных источник внешний (профессии, охота/достижения,
 * заказы) и будет подключён в Фазе 3.</p>
 *
 * <p>Все домены объявлены заранее, чтобы добавление второй дефиниции не
 * требовало правок ядра. {@code pilot} — единственный домен, у которого сейчас
 * есть {@link PerkTreeDefinition}.</p>
 */
public enum TreeDomain {

    MINING("mining", SkillType.MINING, true),
    WOODCUTTING("woodcutting", SkillType.WOODCUTTING, false),
    DIGGING("digging", SkillType.DIGGING, false),
    FARMING("farming", SkillType.FARMING, false),
    FISHING("fishing", SkillType.FISHING, false),
    COMBAT("combat", SkillType.COMBAT, false),
    ARCHERY("archery", SkillType.ARCHERY, false),
    DEFENSE("defense", SkillType.DEFENSE, false),
    ALCHEMY("alchemy", SkillType.ALCHEMY, false),
    ENCHANTING("enchanting", SkillType.ENCHANTING, false),
    CRAFTING("crafting", null, false),
    EXPLORATION("exploration", null, false),
    COMMERCE("commerce", null, false),
    /** PvE без навыкового источника: прогресс модуля hunt (добыча, бестиарий, трофеи). */
    HUNT("hunt", null, false);

    private final String id;
    private final SkillType source;
    private final boolean pilot;

    TreeDomain(String id, SkillType source, boolean pilot) {
        this.id = id;
        this.source = source;
        this.pilot = pilot;
    }

    public String id() {
        return id;
    }

    /** Источник уровней домена; пусто для внешних (не-навыковых) доменов. */
    public Optional<SkillType> source() {
        return Optional.ofNullable(source);
    }

    public boolean hasSkillSource() {
        return source != null;
    }

    public boolean pilot() {
        return pilot;
    }
}
