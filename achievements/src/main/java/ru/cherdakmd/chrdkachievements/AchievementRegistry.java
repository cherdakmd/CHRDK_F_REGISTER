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
import static ru.cherdakmd.chrdkachievements.AchievementMetric.BLOCKS_MINED;
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
 * Каталог достижений: 24 записи, по 6 в каждом разделе.
 *
 * <p>Пороги подобраны так, чтобы путь от «первого шага» до «легенды» занимал
 * недели, а не часы: 100 часов игры, 50 км, 5 000 мобов, 50 000 блоков.
 * Награды — дни проходки и алмазы; мета-достижение «Легенда» открывается,
 * когда открыты все остальные 23, и единственное выдаёт бейдж-трофей
 * (второй бейдж — у «Ветерана»).</p>
 */
public final class AchievementRegistry {

    public static final int EXPECTED_SIZE = 24;
    public static final int PER_CATEGORY = 6;
    /** Сколько достижений нужно для мета-достижения «Легенда». */
    public static final int LEGEND_GOAL = EXPECTED_SIZE - 1;

    private static final Map<String, AchievementDef> REGISTRY = new LinkedHashMap<>();

    static {
        /* ---------------------------------- путь ---------------------------------- */
        register("pervyj-shag", PATH, JOINS, 1, 0, 2);
        register("chas-v-puti", PATH, PLAY_SECONDS, 3_600, 1, 4);
        register("sutki-v-puti", PATH, PLAY_SECONDS, 86_400, 2, 12);
        register("sem-mil", PATH, DISTANCE_BLOCKS, 50_000, 2, 16);
        register("sto-vhodov", PATH, JOINS, 100, 2, 16);
        register("veteran", PATH, PLAY_SECONDS, 360_000, 5, 40, "veteran");

        /* ---------------------------------- бой ----------------------------------- */
        register("ohotnik", COMBAT, MOBS_KILLED, 100, 1, 4);
        register("opytnyj-ohotnik", COMBAT, MOBS_KILLED, 1_000, 2, 16);
        register("groza-mobov", COMBAT, MOBS_KILLED, 5_000, 3, 24);
        register("ubijca-drakona", COMBAT, BOSSES_KILLED, 1, 2, 16);
        register("vlastelin-bezdny", COMBAT, BOSSES_KILLED, 5, 3, 24);
        register("ohotnik-na-bossov", COMBAT, BOSSES_KILLED, 25, 5, 40);

        /* -------------------------------- ремесло --------------------------------- */
        register("kamenschik", CRAFT, BLOCKS_MINED, 1_000, 1, 4);
        register("rudokop", CRAFT, BLOCKS_MINED, 10_000, 2, 12);
        register("almaznyj-klondajk", CRAFT, ORES_MINED, 100, 2, 16);
        register("rybak", CRAFT, FISH_CAUGHT, 100, 1, 8);
        register("master-krafta", CRAFT, ITEMS_CRAFTED, 500, 2, 12);
        register("zemlekop", CRAFT, BLOCKS_MINED, 50_000, 4, 28);

        /* ------------------------------- сообщество ------------------------------- */
        register("svoi-chelovek", SOCIAL, PASS_ACTIVE, 1, 1, 8);
        register("nadezhnyj-akkaunt", SOCIAL, REGISTERED, 1, 0, 4);
        register("dvuhfaktornyj", SOCIAL, TWO_FACTOR, 1, 1, 8);
        register("golos", SOCIAL, CHAT_MESSAGES, 100, 1, 4);
        register("dushevnaya-beseda", SOCIAL, CHAT_MESSAGES, 1_000, 2, 12);
        register("legenda", SOCIAL, ACHIEVEMENTS, LEGEND_GOAL, 7, 64, "legenda");
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
