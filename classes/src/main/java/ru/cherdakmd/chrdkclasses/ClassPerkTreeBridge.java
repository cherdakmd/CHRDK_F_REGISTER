package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkskills.tree.PerkNode;
import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.List;
import java.util.Set;

/**
 * Мост между классовой прогрессией и обобщённым каркасом деревьев перков.
 *
 * <p>Rework классов уже реализован ({@code ClassTrees} на 180 узлов,
 * {@code ClassDevelopment} на 50 уровней, {@code ClassStore} схемы 4). Деревья
 * перков живут в {@code skills} и до сих пор не знали, откуда брать уровень
 * PvE-домена. Этот мост даёт им источник, не создавая ещё одной боевой
 * прогрессии.</p>
 *
 * <p>Мост <b>только</b> сообщает уровень домена. Он не выдаёт очки талантов,
 * пыль или токены, не пишет в {@code ClassStore}, не меняет раскладку,
 * сборки и откаты умений, не знает про Bukkit и не добавляет боевых эффектов.
 * {@code EXPLORATION} и {@code HUNT} остаются внешними доменами: их прогресс
 * должен прийти из hunt / achievements / quests, а не из уровня класса.</p>
 */
public final class ClassPerkTreeBridge {

    /** 50 уровней класса × 2 = шкала домена 1..100, как у навыков Skills. */
    public static final int MAX_DOMAIN_LEVEL = 100;
    private static final int LEVEL_SCALE = 2;

    private static final Set<TreeDomain> CLASS_SOURCED =
            Set.of(TreeDomain.COMBAT, TreeDomain.ARCHERY, TreeDomain.DEFENSE);

    private ClassPerkTreeBridge() {
    }

    /** Уровень класса → уровень домена дерева (жёстко ограничен сверху). */
    public static int domainLevel(int classLevel) {
        return Math.min(MAX_DOMAIN_LEVEL, Math.max(0, classLevel) * LEVEL_SCALE);
    }

    /** Домены роли: ведущий первый, второй — смежный. Внешних доменов здесь нет. */
    public static List<TreeDomain> classDomains(PlayerClass role) {
        if (role == null) {
            return List.of();
        }
        return switch (role) {
            case WARRIOR -> List.of(TreeDomain.COMBAT, TreeDomain.DEFENSE);
            case ARCHER -> List.of(TreeDomain.ARCHERY, TreeDomain.COMBAT);
            case MAGE -> List.of(TreeDomain.COMBAT, TreeDomain.ARCHERY);
            case CLERIC -> List.of(TreeDomain.DEFENSE, TreeDomain.COMBAT);
        };
    }

    public static TreeDomain primaryDomain(PlayerClass role) {
        List<TreeDomain> domains = classDomains(role);
        return domains.isEmpty() ? null : domains.get(0);
    }

    /** Домены, чей прогресс НЕ выводится из класса (Фаза 3). */
    public static List<TreeDomain> externalDomains() {
        return List.of(TreeDomain.EXPLORATION, TreeDomain.HUNT);
    }

    public static boolean isClassSourced(TreeDomain domain) {
        return CLASS_SOURCED.contains(domain);
    }

    /**
     * Начальное состояние дерева из уровня класса. Пыль и токены не выдаются:
     * их источник — отдельный контракт PvE-событий, а не классовый XP.
     */
    public static PerkTreeState initialState(TreeDomain domain, int classLevel) {
        if (domain == null || !isClassSourced(domain)) {
            throw new IllegalArgumentException("домен не получает прогресс из класса");
        }
        if (PerkTreeRegistry.of(domain).isEmpty()) {
            throw new IllegalStateException("дерево " + domain.id() + " не зарегистрировано");
        }
        return PerkTreeState.empty(domain).withLevel(domainLevel(classLevel));
    }

    /** Защита политики QoL-only при связке классов с PvE-деревьями. */
    public static boolean pveTreesStayQolOnly() {
        for (TreeDomain domain : PerkTreeRegistry.pveDomains()) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            for (PerkNode node : definition.nodes()) {
                if (node.effect().balanceSensitive()) {
                    return false;
                }
            }
        }
        return true;
    }
}
