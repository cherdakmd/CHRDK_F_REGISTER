package ru.cherdakmd.chrdkskills;

import java.util.Arrays;

/** Первая волна: бережная добыча и контроль мобов, без новых процентов урона/дропа. */
public enum SkillAbility {
    MINING(SkillType.MINING, 25, 5, 45, "_PICKAXE"),
    WOODCUTTING(SkillType.WOODCUTTING, 25, 5, 45, "_AXE"),
    DIGGING(SkillType.DIGGING, 25, 5, 45, "_SHOVEL"),
    FARMING(SkillType.FARMING, 25, 5, 45, "_HOE"),
    FISHING(SkillType.FISHING, 25, 5, 45, "FISHING_ROD"),
    COMBAT(SkillType.COMBAT, 30, 5, 40, "_SWORD"),
    ARCHERY(SkillType.ARCHERY, 25, 5, 35, "BOW"),
    DEFENSE(SkillType.DEFENSE, 30, 5, 40, "SHIELD"),
    ALCHEMY(SkillType.ALCHEMY, 25, 5, 45, "BREWING_STAND"),
    ENCHANTING(SkillType.ENCHANTING, 25, 5, 45, "ENCHANTING_TABLE");

    public static final int UNLOCK_PRESTIGE = 1;
    public static final int MAX_ENERGY = 100;
    public static final int REGEN_PER_SECOND = 2;
    private final SkillType skill;
    private final int cost, seconds, cooldown;
    private final String tool;
    SkillAbility(SkillType skill, int cost, int seconds, int cooldown, String tool) {
        this.skill = skill; this.cost = cost; this.seconds = seconds; this.cooldown = cooldown; this.tool = tool;
    }
    public SkillType skill() { return skill; }
    public String id() { return skill.id(); }
    public int cost() { return cost; }
    public int seconds() { return seconds; }
    public int cooldown() { return cooldown; }
    public boolean gathering() { return skill.category() == SkillType.Category.GATHER; }
    public String nameKey() { return "ability." + id() + ".name"; }
    public String descKey() { return "ability." + id() + ".desc"; }
    public boolean acceptsTool(String material) {
        if (material == null) return false;
        return switch (this) {
            case ARCHERY -> material.equals("BOW") || material.equals("CROSSBOW");
            case COMBAT -> material.endsWith("_SWORD");
            case DEFENSE -> material.equals("SHIELD");
            default -> material.endsWith(tool);
        };
    }
    public static SkillAbility of(SkillType skill) {
        return Arrays.stream(values()).filter(a -> a.skill == skill).findFirst().orElse(null);
    }
}
