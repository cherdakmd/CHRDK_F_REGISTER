package ru.cherdakmd.chrdkachievements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ru.cherdakmd.chrdkachievements.AchievementDef.Category.COMBAT;
import static ru.cherdakmd.chrdkachievements.AchievementDef.Category.CRAFT;
import static ru.cherdakmd.chrdkachievements.AchievementDef.Category.PATH;
import static ru.cherdakmd.chrdkachievements.AchievementDef.Category.SOCIAL;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ACHIEVEMENTS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ANIMALS_TAMED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ARROWS_SHOT;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BANK_BALANCE;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BANK_RECEIVED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BANK_SENT;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BEDS_SLEPT;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BLOCKS_MINED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BLOCKS_PLACED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.DAMAGE_DEALT;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.DAMAGE_TAKEN;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.DEATHS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.END_VISITS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.FOOD_EATEN;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ITEMS_ENCHANTED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.NETHER_VISITS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.POTIONS_BREWED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.SHIELD_BLOCKS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.TOOLS_BROKEN;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.VILLAGER_TRADES;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BOSSES_KILLED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.CHAT_MESSAGES;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.DISTANCE_BLOCKS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.FISH_CAUGHT;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ITEMS_CRAFTED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.JOINS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.MOBS_KILLED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.ORES_MINED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.PASS_ACTIVE;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.PLAY_SECONDS;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.REGISTERED;
import static ru.cherdakmd.chrdkachievements.AchievementMetric.TWO_FACTOR;

/**
 * Каталог достижений: 48 записей, по 12 в каждом разделе.
 *
 * <p>Пороги подобраны так, чтобы путь от «первого шага» до «легенды» занимал
 * недели, а не часы: 100 часов игры, 50 км, 5 000 мобов, 50 000 блоков.
 * Награды — дни проходки и алмазы; мета-достижение «Легенда» открывается,
 * когда открыты все остальные 23, и единственное выдаёт бейдж-трофей
 * (второй бейдж — у «Ветерана»).</p>
 */
public final class AchievementRegistry {

    public static final int EXPECTED_SIZE = 48;
    public static final int PER_CATEGORY = 12;
    /** Сколько достижений нужно для мета-достижения «Легенда». */
    public static final int LEGEND_GOAL = EXPECTED_SIZE - 1;

    private static final Map<String, AchievementDef> REGISTRY = new LinkedHashMap<>();

    static {
        /* ---------------------------------- путь ---------------------------------- */
        register("pervyj-shag", PATH, JOINS, 1, 0, 2);
        register("sto-vhodov", PATH, JOINS, 100, 2, 16);
        register("chas-v-puti", PATH, PLAY_SECONDS, 3_600, 1, 4);
        register("polovina-sutok", PATH, PLAY_SECONDS, 43_200, 1, 10);
        register("sutki-v-puti", PATH, PLAY_SECONDS, 86_400, 2, 12);
        register("veteran", PATH, PLAY_SECONDS, 360_000, 5, 40, "veteran");
        register("putnik", PATH, DISTANCE_BLOCKS, 10_000, 1, 6);
        register("sem-mil", PATH, DISTANCE_BLOCKS, 50_000, 2, 16);
        register("pervaya-noch", PATH, BEDS_SLEPT, 1, 0, 4);
        register("sonnyj-master", PATH, BEDS_SLEPT, 50, 1, 10);
        register("za-porogom", PATH, NETHER_VISITS, 1, 1, 8);
        register("kraj-sveta", PATH, END_VISITS, 1, 2, 16);

        /* ---------------------------------- бой ----------------------------------- */
        register("ohotnik", COMBAT, MOBS_KILLED, 100, 1, 4);
        register("opytnyj-ohotnik", COMBAT, MOBS_KILLED, 1_000, 2, 16);
        register("groza-mobov", COMBAT, MOBS_KILLED, 5_000, 3, 24);
        register("ubijca-drakona", COMBAT, BOSSES_KILLED, 1, 2, 16);
        register("vlastelin-bezdny", COMBAT, BOSSES_KILLED, 5, 3, 24);
        register("ohotnik-na-bossov", COMBAT, BOSSES_KILLED, 25, 5, 40);
        register("bojec", COMBAT, DAMAGE_DEALT, 1_000, 1, 6);
        register("bogatyr", COMBAT, DAMAGE_DEALT, 100_000, 3, 24);
        register("stojkij", COMBAT, DAMAGE_TAKEN, 50_000, 2, 16);
        register("upornyj", COMBAT, DEATHS, 25, 1, 10);
        register("sniper", COMBAT, ARROWS_SHOT, 1_000, 2, 12);
        register("shchitonosec", COMBAT, SHIELD_BLOCKS, 100, 1, 8);

        /* -------------------------------- ремесло --------------------------------- */
        register("kamenschik", CRAFT, BLOCKS_MINED, 1_000, 1, 4);
        register("rudokop", CRAFT, BLOCKS_MINED, 10_000, 2, 12);
        register("zemlekop", CRAFT, BLOCKS_MINED, 50_000, 4, 28);
        register("almaznyj-klondajk", CRAFT, ORES_MINED, 100, 2, 16);
        register("rybak", CRAFT, FISH_CAUGHT, 100, 1, 8);
        register("master-krafta", CRAFT, ITEMS_CRAFTED, 500, 2, 12);
        register("fabrika", CRAFT, ITEMS_CRAFTED, 5_000, 3, 20);
        register("stroitel", CRAFT, BLOCKS_PLACED, 1_000, 1, 6);
        register("arhitektor", CRAFT, BLOCKS_PLACED, 50_000, 4, 28);
        register("charodej", CRAFT, ITEMS_ENCHANTED, 100, 2, 12);
        register("alhimik", CRAFT, POTIONS_BREWED, 100, 2, 12);
        register("instrument", CRAFT, TOOLS_BROKEN, 100, 1, 8);

        /* ------------------------------- сообщество ------------------------------- */
        register("svoi-chelovek", SOCIAL, PASS_ACTIVE, 1, 1, 8);
        register("nadezhnyj-akkaunt", SOCIAL, REGISTERED, 1, 0, 4);
        register("dvuhfaktornyj", SOCIAL, TWO_FACTOR, 1, 1, 8);
        register("golos", SOCIAL, CHAT_MESSAGES, 100, 1, 4);
        register("dushevnaya-beseda", SOCIAL, CHAT_MESSAGES, 1_000, 2, 12);
        register("legenda", SOCIAL, ACHIEVEMENTS, LEGEND_GOAL, 7, 64, "legenda");
        register("bankir", SOCIAL, BANK_BALANCE, 1_000, 2, 16);
        register("mecenat", SOCIAL, BANK_SENT, 1_000, 2, 16);
        register("podarok", SOCIAL, BANK_RECEIVED, 1_000, 1, 8);
        register("torgovec", SOCIAL, VILLAGER_TRADES, 100, 1, 8);
        register("gurman", SOCIAL, FOOD_EATEN, 500, 1, 8);
        register("dressirovshchik", SOCIAL, ANIMALS_TAMED, 10, 1, 8);
    }

    private AchievementRegistry() {
    }

    private static void register(String id, AchievementDef.Category category,
                                 AchievementMetric metric, long goal, int passDays, int diamonds) {
        register(id, category, metric, goal, passDays, diamonds, null);
    }

    private static void register(String id, AchievementDef.Category category,
                                 AchievementMetric metric, long goal, int passDays, int diamonds,
                                 String badge) {
        REGISTRY.put(id, new AchievementDef(id, category, metric, goal, passDays, diamonds, badge));
    }

    public static AchievementDef byId(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(Locale.ROOT).trim());
    }

    public static List<AchievementDef> all() {
        return List.copyOf(new ArrayList<>(REGISTRY.values()));
    }

    public static List<AchievementDef> byCategory(AchievementDef.Category category) {
        List<AchievementDef> out = new ArrayList<>();
        for (AchievementDef def : REGISTRY.values()) {
            if (def.category() == category) {
                out.add(def);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static int size() {
        return REGISTRY.size();
    }
}
