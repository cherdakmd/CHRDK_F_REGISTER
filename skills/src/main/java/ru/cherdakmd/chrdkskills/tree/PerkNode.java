package ru.cherdakmd.chrdkskills.tree;

import java.util.Set;

/**
 * Узел дерева перков: одна покупаемая способность внутри домена.
 *
 * @param id         аски-идентификатор, уникальный в дефиниции
 * @param domain     домен дерева
 * @param tier       тир (1..10); открывается уровнем домена
 * @param tokenCost  токены за один уровень узла
 * @param powderCost базовая пыль за первый уровень; следующие уровни дороже
 * @param effect     вид эффекта (только QoL)
 * @param value      величина эффекта за один уровень
 * @param maxLevel   сколько раз узел можно купить
 * @param requires   иды узлов, которые должны быть открыты раньше
 * @param core       «ядро»: не стирается при сбросе, пыль не возвращается
 * @param ability    активный приём; одновременно выбран может быть только один
 */
public record PerkNode(String id, TreeDomain domain, int tier, int tokenCost,
                       long powderCost, PerkEffect effect, double value,
                       int maxLevel, Set<String> requires, boolean core, boolean ability) {

    public PerkNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("у узла должен быть ид");
        }
        if (domain == null || effect == null) {
            throw new IllegalArgumentException("домен и эффект обязательны");
        }
        if (effect.balanceSensitive()) {
            throw new IllegalArgumentException("балансовый эффект запрещён политикой QoL-only: " + effect);
        }
        if (tier < 1 || tier > 10 || tokenCost < 0 || powderCost < 0 || maxLevel < 1) {
            throw new IllegalArgumentException("некорректные числа узла " + id);
        }
        if (core && ability) {
            throw new IllegalArgumentException("ядро не может быть активным приёмом: " + id);
        }
        requires = requires == null ? Set.of() : Set.copyOf(requires);
    }

    /** Простой одноуровневый узел без зависимостей. */
    public PerkNode(String id, TreeDomain domain, int tier, int tokenCost,
                    long powderCost, PerkEffect effect, double value) {
        this(id, domain, tier, tokenCost, powderCost, effect, value, 1, Set.of(), false, false);
    }
}
