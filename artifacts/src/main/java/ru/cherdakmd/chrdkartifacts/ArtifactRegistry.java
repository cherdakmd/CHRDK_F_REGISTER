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
 * Статический каталог всех 65 артефактов: 33 светлых и 32 проклятых (30 базовых
 * + 35 по мотивам CHRDK_REBORN, 1.4.58). Имена и описания живут в messages.yml
 * ({@code art.<id>.name|lore}), эффекты — в {@link ArtifactEffects}.
 */
public final class ArtifactRegistry {

    public static final int EXPECTED_SIZE = 65;
    public static final int EXPECTED_GOOD = 33;
    public static final int EXPECTED_CURSED = 32;

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

        /* ---- добрая волна (1.4.58, по мотивам CHRDK_REBORN): пассивные ---- */
        register("pero-feniksa", GOOD, PASSIVE, Material.BLAZE_POWDER);
        register("magnit-opyta", GOOD, PASSIVE, Material.PRISMARINE_SHARD);
        register("zhnej-dush", GOOD, PASSIVE, Material.SOUL_LANTERN);
        register("ledyanaya-aura", GOOD, PASSIVE, Material.BLUE_ICE);
        register("ternistyj-plasch", GOOD, PASSIVE, Material.CACTUS);
        register("travjanoj-eliksir", GOOD, PASSIVE, Material.OXEYE_DAISY);
        register("sapogi-vetra", GOOD, PASSIVE, Material.RABBIT_FOOT);
        register("klever-udachi", GOOD, PASSIVE, Material.FERN);
        register("travyanoj-meshochek", GOOD, PASSIVE, Material.WHEAT_SEEDS);
        register("serdtse-drakona", GOOD, PASSIVE, Material.DRAGON_BREATH);

        /* ---- добрая волна (1.4.58): активные ---- */
        register("svitok-ochischeniya", GOOD, ACTIVE, Material.SHULKER_SHELL, 240, 5);
        register("totem-krovi", GOOD, ACTIVE, Material.ROTTEN_FLESH, 180, 4);
        register("sfera-pobega", GOOD, ACTIVE, Material.ENDER_PEARL, 120, 3);
        register("ledyanaya-stena", GOOD, ACTIVE, Material.SNOW_BLOCK, 90, 2);
        register("glaz-nachala", GOOD, ACTIVE, Material.ENDER_EYE, 300, 4);
        register("korona-bezdny", GOOD, ACTIVE, Material.CRYING_OBSIDIAN, 300, 8);
        register("ender-sdvig", GOOD, ACTIVE, Material.CHORUS_FRUIT, 60, 1);
        register("oskolok-vechnosti", GOOD, ACTIVE, Material.NETHER_STAR, 600, 12);

        /* ---- тёмная волна (1.4.58): проклятые пассивные ---- */
        register("okov-tyazhesti", CURSED, PASSIVE, Material.CHAIN);
        register("pustoy-zhivot", CURSED, PASSIVE, Material.WEEPING_VINES);
        register("slabye-kisti", CURSED, PASSIVE, Material.SPONGE);
        register("krovavyj-svyaz", CURSED, PASSIVE, Material.NETHERITE_SCRAP);
        register("smertnyj-potok", CURSED, PASSIVE, Material.KELP);
        register("chernye-slezy", CURSED, PASSIVE, Material.BLACK_DYE);
        register("ruga-zhadnosti", CURSED, PASSIVE, Material.LAPIS_LAZULI);
        register("prah-marka", CURSED, PASSIVE, Material.BROWN_MUSHROOM);
        register("koltso-usmerti", CURSED, PASSIVE, Material.WITHER_ROSE);

        /* ---- тёмная волна (1.4.58): проклятые активные ---- */
        register("kniga-proklyatiy", CURSED, ACTIVE, Material.BOOK, 300, 4);
        register("zhertvennyj-kubok", CURSED, ACTIVE, Material.CRIMSON_FUNGUS, 120, 2);
        register("raskolotaya-luna", CURSED, ACTIVE, Material.PACKED_ICE, 600, 5);
        register("shlyapa-bezdelnika", CURSED, ACTIVE, Material.CARROT_ON_A_STICK, 30, 0);
        register("silok-dushi", CURSED, ACTIVE, Material.TWISTING_VINES, 90, 2);
        register("oblaka-durmana", CURSED, ACTIVE, Material.HANGING_ROOTS, 120, 2);
        register("klyuch-propasti", CURSED, ACTIVE, Material.COAL_ORE, 300, 5);
        register("svist-gadov", CURSED, ACTIVE, Material.COBWEB, 180, 3);
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
