package ru.cherdakmd.chrdkartifacts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.cherdakmd.chrdkartifacts.ArtifactDef.Mode.ACTIVE;
import static ru.cherdakmd.chrdkartifacts.ArtifactDef.Mode.PASSIVE;
import static ru.cherdakmd.chrdkartifacts.ArtifactDef.Side.CURSED;
import static ru.cherdakmd.chrdkartifacts.ArtifactDef.Side.GOOD;

import org.bukkit.Material;

/**
 * Статический каталог всех 30 артефактов: 15 светлых и 15 проклятых.
 * Имена и описания живут в messages.yml ({@code art.<id>.name|lore}),
 * эффекты — в {@link ArtifactEffects}.
 */
public final class ArtifactRegistry {

    public static final int EXPECTED_SIZE = 30;
    public static final int EXPECTED_GOOD = 15;
    public static final int EXPECTED_CURSED = 15;

    private static final Map<String, ArtifactDef> REGISTRY = new LinkedHashMap<>();

    static {
        /* ------------------------------ светлые: пассивные ------------------------------ */
        register("solnechny-obereg", GOOD, PASSIVE, Material.SUNFLOWER);
        register("zhabii-kamen", GOOD, PASSIVE, Material.TURTLE_SCUTE);
        register("dyhanie-gor", GOOD, PASSIVE, Material.FEATHER);
        register("nochnoj-svetlyak", GOOD, PASSIVE, Material.GLOW_INK_SAC);
        register("kamen-stojkosti", GOOD, PASSIVE, Material.OBSIDIAN);
        register("vetryanaya-spitsa", GOOD, PASSIVE, Material.STRING);
        register("koren-zhizni", GOOD, PASSIVE, Material.MANGROVE_ROOTS);
        register("ruka-rudokopa", GOOD, PASSIVE, Material.COPPER_INGOT);

        /* ------------------------------ светлые: активные ------------------------------- */
        register("grozovoj-kolokol", GOOD, ACTIVE, Material.BELL, 90, 2);
        register("rog-tajgi", GOOD, ACTIVE, Material.ECHO_SHARD, 45, 1);
        register("plamya-ochaga", GOOD, ACTIVE, Material.TORCHFLOWER, 120, 3);
        register("kaplya-zhizni", GOOD, ACTIVE, Material.NETHER_WART, 300, 10);
        register("klyuch-strannika", GOOD, ACTIVE, Material.COMPASS, 180, 5);
        register("linza-providesa", GOOD, ACTIVE, Material.AMETHYST_SHARD, 240, 4);
        register("hartiya-char", GOOD, ACTIVE, Material.PAPER, 3600, 15);

        /* ----------------------------- проклятые: пассивные ----------------------------- */
        register("sleza-vdovy", CURSED, PASSIVE, Material.GHAST_TEAR);
        register("golodnyj-koren", CURSED, PASSIVE, Material.DEAD_BUSH);
        register("smolyanoj-mrak", CURSED, PASSIVE, Material.INK_SAC);
        register("krivoe-zerkalo", CURSED, PASSIVE, Material.GLASS_BOTTLE);
        register("rzhavyj-gvozd", CURSED, PASSIVE, Material.FLINT);
        register("glinyanaya-dusha", CURSED, PASSIVE, Material.CLAY_BALL);
        register("pepel-pogroma", CURSED, PASSIVE, Material.GUNPOWDER);
        register("sushonyj-glaz", CURSED, PASSIVE, Material.FERMENTED_SPIDER_EYE);

        /* ----------------------------- проклятые: активные ------------------------------ */
        register("chernaya-moneta", CURSED, ACTIVE, Material.GOLD_NUGGET, 300, 0);
        register("svistok-kostej", CURSED, ACTIVE, Material.BONE, 180, 2);
        register("membrana-prizraka", CURSED, ACTIVE, Material.PHANTOM_MEMBRANE, 60, 1);
        register("rakovina-toski", CURSED, ACTIVE, Material.NAUTILUS_SHELL, 600, 5);
        register("zhalo-moroki", CURSED, ACTIVE, Material.BLAZE_ROD, 120, 2);
        register("chasy-duroshljopa", CURSED, ACTIVE, Material.CLOCK, 900, 3);
        register("porvannyj-kupon", CURSED, ACTIVE, Material.LEATHER, 300, 0);
    }

    private ArtifactRegistry() {
    }

    private static void register(String id, ArtifactDef.Side side, ArtifactDef.Mode mode,
                                 Material material, int cooldownSeconds, int xpCostLevels) {
        REGISTRY.put(id, new ArtifactDef(id, side, mode, material, cooldownSeconds, xpCostLevels));
    }

    private static void register(String id, ArtifactDef.Side side, ArtifactDef.Mode mode,
                                 Material material) {
        register(id, side, mode, material, 0, 0);
    }

    public static ArtifactDef byId(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(java.util.Locale.ROOT).trim());
    }

    public static List<ArtifactDef> all() {
        return List.copyOf(new ArrayList<>(REGISTRY.values()));
    }

    public static List<ArtifactDef> bySide(ArtifactDef.Side side) {
        List<ArtifactDef> out = new ArrayList<>();
        for (ArtifactDef def : REGISTRY.values()) {
            if (def.side() == side) {
                out.add(def);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static int size() {
        return REGISTRY.size();
    }
}
