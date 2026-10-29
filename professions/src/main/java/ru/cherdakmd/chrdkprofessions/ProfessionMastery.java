package ru.cherdakmd.chrdkprofessions;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Специализации ремесла. Не дают боевых атрибутов и не заменяют старые прибавки. */
public enum ProfessionMastery {
    FIELD_SMITH("field-smith", Profession.SMITH, "IRON_INGOT", 1, 120, 10),
    RESTORER("restorer", Profession.SMITH, "ANVIL", 1, 600, 25),
    GRENADIER("grenadier", Profession.ALCHEMIST, "SPLASH_POTION", 3, 120, 0),
    DISTILLER("distiller", Profession.ALCHEMIST, "LINGERING_POTION", 3, 180, 0),
    CAMP_COOK("camp-cook", Profession.COOK, "CAMPFIRE", 8, 120, 0),
    CHEF("chef", Profession.COOK, "SMOKER", 32, 480, 0),
    KILN_MASTER("kiln-master", Profession.MASON, "FURNACE", 32, 300, 0),
    FINISHER("finisher", Profession.MASON, "STONECUTTER", 32, 180, 0);

    public static final int UNLOCK_LEVEL = 5;
    public static final long CHANGE_MILLIS = 24 * 60 * 60 * 1000L;
    private final String id;
    private final Profession profession;
    private final String icon;
    private final int batch;
    private final int cooldownSeconds;
    private final int repairPercent;

    ProfessionMastery(String id, Profession profession, String icon, int batch, int cooldownSeconds, int repairPercent) {
        this.id = id;
        this.profession = profession;
        this.icon = icon;
        this.batch = batch;
        this.cooldownSeconds = cooldownSeconds;
        this.repairPercent = repairPercent;
    }

    public String id() { return id; }
    public Profession profession() { return profession; }
    public String icon() { return icon; }
    public int batch() { return batch; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public int repairPercent() { return repairPercent; }
    public String nameKey() { return "mastery." + id + ".name"; }
    public String descKey() { return "mastery." + id + ".desc"; }
    public String costKey() { return "mastery." + id + ".cost"; }

    public static ProfessionMastery parse(String id) {
        if (id == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(m -> m.id.equals(normalized)).findFirst().orElse(null);
    }

    public static List<ProfessionMastery> of(Profession profession) {
        return Arrays.stream(values()).filter(m -> m.profession == profession).toList();
    }
}
