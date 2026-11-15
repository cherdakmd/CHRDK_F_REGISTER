package ru.cherdakmd.chrdkskills;

import java.util.Arrays;

/**
 * Активные приёмы веток билда. Это новые приёмы — приёмы классов не
 * переносятся. У каждой ветки два приёма: первый открывается на уровне ветки
 * {@link #UNLOCK_FIRST}, второй — на {@link #UNLOCK_SECOND}. Сила масштабируется
 * ступенью престижа ({@link BranchMath#power}).
 */
public enum BranchAbility {
    CLEAVE(BuildBranch.WARRIOR, "cleave", "Рассечение", 3, 18, 30),
    STANCE(BuildBranch.WARRIOR, "stance", "Стойка", 6, 22, 40),
    VOLLEY(BuildBranch.ARCHER, "volley", "Залп", 3, 18, 30),
    LEAP(BuildBranch.ARCHER, "leap", "Отскок", 6, 16, 28),
    BOLT(BuildBranch.MAGE, "bolt", "Разряд", 3, 20, 32),
    ORB(BuildBranch.MAGE, "orb", "Сфера", 6, 22, 40),
    FAITH_SHIELD(BuildBranch.CLERIC, "faith_shield", "Щит веры", 3, 20, 36),
    PURGE(BuildBranch.CLERIC, "purge", "Очищение", 6, 16, 30),
    HEALING_SHIELD(BuildBranch.PRIEST, "healing_shield", "Исцеляющий щит", 3, 20, 36),
    GRACE(BuildBranch.PRIEST, "grace", "Благодать", 6, 22, 40),
    BACKSTAB(BuildBranch.ROGUE, "backstab", "Удар в спину", 3, 18, 30),
    VANISH(BuildBranch.ROGUE, "vanish", "Исчезновение", 6, 20, 36);

    static final int UNLOCK_FIRST = 3;
    static final int UNLOCK_SECOND = 6;

    private final BuildBranch branch;
    private final String id;
    private final String name;
    private final int unlockLevel;
    private final int cost;
    private final int cooldown;

    BranchAbility(BuildBranch branch, String id, String name, int unlockLevel, int cost, int cooldown) {
        this.branch = branch;
        this.id = id;
        this.name = name;
        this.unlockLevel = unlockLevel;
        this.cost = cost;
        this.cooldown = cooldown;
    }

    public BuildBranch branch() {
        return branch;
    }

    public String id() {
        return id;
    }

    /** Русское имя для сообщений (метод {@code name()} занят {@link Enum}). */
    public String displayName() {
        return name;
    }

    public int unlockLevel() {
        return unlockLevel;
    }

    public int cost() {
        return cost;
    }

    public int cooldown(int prestige) {
        return BranchMath.cooldown(cooldown, prestige);
    }

    public String nameKey() {
        return "branch." + id + ".name";
    }

    public String descKey() {
        return "branch." + id + ".desc";
    }

    public static BranchAbility byId(String id) {
        if (id == null) return null;
        String needle = id.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(ability -> ability.id.equals(needle))
                .findFirst()
                .orElse(null);
    }
}
