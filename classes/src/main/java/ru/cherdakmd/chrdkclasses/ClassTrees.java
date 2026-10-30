package ru.cherdakmd.chrdkclasses;

import java.util.*;

/** Каталог версии 1: 4 класса × 3 специализации × 3 направления × 5 узлов. */
final class ClassTrees {
    static final int VERSION = 1;
    static final int PER_CLASS = 45;
    private static final Map<String, Talent> NODES = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static final Map<String, String> BRANCH_NAMES = new LinkedHashMap<>();
    static final String[][] SPECS = {
        {"Страж", "Берсерк", "Авангард"}, {"Меткий стрелок", "Следопыт", "Ловчий"},
        {"Пиромант", "Криомант", "Чародей"}, {"Жизнь", "Оберег", "Свет"}
    };
    private static final EffectKind H = EffectKind.MAX_HEALTH, A = EffectKind.ARMOR,
        M = EffectKind.MELEE_DAMAGE, R = EffectKind.DAMAGE_REDUCTION, S = EffectKind.MOVEMENT_SPEED,
        K = EffectKind.KNOCKBACK_RESISTANCE, F = EffectKind.FALL_REDUCTION,
        G = EffectKind.REGEN, T = EffectKind.SATURATION, B = EffectKind.ARROW_DAMAGE;
    static {
        add(PlayerClass.WARRIOR, new String[]{"Закалка", "Бастион", "Несгибаемость", "Натиск", "Ярость", "Живучесть", "Марш", "Опора", "Погоня"},
            new EffectKind[][]{{H,A,R,H,A},{A,R,K,A,R},{K,H,A,R,K},{M,S,M,H,M},{M,H,M,R,M},{H,R,H,A,H},{S,F,S,K,S},{K,A,F,H,K},{M,S,F,M,S}},
            new String[]{"defense","defense","defense","combat","combat","defense","combat","mining","combat"});
        add(PlayerClass.ARCHER, new String[]{"Прицел", "Тетива", "Пробивание", "Тропа", "Запас сил", "Ускользание", "Засада", "Выдержка", "Дистанция"},
            new EffectKind[][]{{B,S,B,B,B},{B,H,B,K,B},{B,R,B,A,B},{S,F,S,K,S},{H,G,T,H,G},{F,R,K,F,R},{B,K,B,S,B},{H,A,R,G,H},{S,F,B,S,S}},
            new String[]{"archery","archery","archery","archery","defense","defense","archery","defense","archery"});
        add(PlayerClass.MAGE, new String[]{"Жар", "Пепел", "Жаростойкость", "Кристалл", "Ледяной щит", "Скольжение", "Поток", "Барьер", "Восстановление"},
            new EffectKind[][]{{S,H,A,S,H},{A,R,K,A,R},{H,R,A,H,R},{A,H,K,A,H},{R,A,H,R,A},{S,F,K,S,F},{S,G,T,S,G},{H,A,R,K,A},{G,T,H,G,T}},
            new String[]{"alchemy","alchemy","defense","enchanting","defense","enchanting","enchanting","defense","alchemy"});
        add(PlayerClass.CLERIC, new String[]{"Милость", "Жизненная сила", "Провизия", "Покров", "Непоколебимость", "Убежище", "Сияние", "Паломник", "Надежда"},
            new EffectKind[][]{{G,H,G,T,G},{H,R,H,A,H},{T,G,T,H,T},{A,R,H,A,R},{K,A,K,H,K},{R,H,F,R,H},{H,A,G,H,A},{S,F,S,T,S},{G,T,H,K,G}},
            new String[]{"alchemy","defense","farming","defense","defense","defense","enchanting","defense","alchemy"});
    }
    private ClassTrees() { }
    private static void add(PlayerClass role, String[] paths, EffectKind[][] kinds, String[] skills) {
        int[] required = {0,10,25,45,65}, levels = {1,5,12,22,35};
        for (int path = 0; path < 9; path++) {
            String branch = role.id() + "-path-" + path;
            BRANCH_NAMES.put(branch, paths[path]);
            for (int tier = 1; tier <= 5; tier++) {
                EffectKind kind = kinds[path][tier - 1];
                if (ceiling(role.id(), kind) == 0) kind = EffectKind.DAMAGE_REDUCTION;
                String id = branch + "-" + tier;
                double value = switch(kind) {
                    case MAX_HEALTH -> tier == 5 ? 4 : 0.5;
                    case ARMOR -> tier == 5 ? 4 : 0.5;
                    case MOVEMENT_SPEED -> tier == 5 ? .04 : .01;
                    case KNOCKBACK_RESISTANCE -> tier == 5 ? .15 : .03;
                    case MELEE_DAMAGE, ARROW_DAMAGE -> tier == 5 ? .12 : .025;
                    case DAMAGE_REDUCTION -> tier == 5 ? .10 : .02;
                    case FALL_REDUCTION -> tier == 5 ? .20 : .04;
                    case REGEN -> tier == 5 ? 2 : .5;
                    case SATURATION -> tier == 5 ? 3 : .5;
                };
                NODES.put(id, new Talent(id, role.id(), branch, tier, tier == 5 ? 5 : 1,
                        tier == 1 ? "" : branch + "-" + (tier - 1), skills[path], required[tier-1],
                        kind, value, tier == 5 ? 1 : 3, levels[tier-1], tier == 5 ? role.id()+"-mastery" : ""));
                NAMES.put(id, paths[path] + " · " + new String[]{"Основа", "Выучка", "Искусство", "Мастерство", "Вершина"}[tier-1]);
            }
        }
    }
    static Talent talent(String id) { return NODES.get(id); }
    static String name(String id) { return NAMES.getOrDefault(id, id); }
    static List<Talent> talents(String role) { return NODES.values().stream().filter(t -> t.classId().equals(role)).toList(); }
    static List<String> branches(String role) { return BRANCH_NAMES.keySet().stream().filter(b -> b.startsWith(role+"-path-")).toList(); }
    static List<Talent> page(String role, int page) { return talents(role).subList(page * 15, page * 15 + 15); }
    /** Не расширяем даже достижимый старый PvP-потолок конкретного класса. */
    static double ceiling(String role, EffectKind kind) {
        double raw = ClassCatalog.passive(role).getOrDefault(kind, 0.0);
        for (Talent talent : ClassCatalog.talents(role)) if (talent.kind() == kind) raw += talent.value();
        return ClassMath.total(kind, raw);
    }
}
