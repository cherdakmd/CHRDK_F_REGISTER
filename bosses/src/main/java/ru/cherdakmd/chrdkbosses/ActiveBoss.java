package ru.cherdakmd.chrdkbosses;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * Идущий бой с боссом (1.4.162).
 *
 * <p>Живёт только в памяти: сессия начинается со спавна и заканчивается
 * смертью, таймаутом или выгрузкой сервера. Здесь же копится вклад участников
 * и паузы способностей — по ним считаются фазы и награда.</p>
 */
final class ActiveBoss {

    private final BossDefinition definition;
    private final LivingEntity entity;
    private final long startedAt;
    private final long endsAt;
    private final Map<UUID, Double> damage = new LinkedHashMap<>();
    private final Map<BossAbility, Long> nextCast = new LinkedHashMap<>();
    private final List<UUID> minions = new ArrayList<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final BossBar bar;
    private final double baseSpeed;
    private int phaseIndex;

    ActiveBoss(BossDefinition definition, LivingEntity entity, long startedAt, long endsAt,
               BossBar bar, double baseSpeed) {
        this.definition = definition;
        this.entity = entity;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.bar = bar;
        this.baseSpeed = baseSpeed <= 0 ? 0.23 : baseSpeed;
        this.phaseIndex = definition.phaseIndex(entity.getHealth());
    }

    /** Базовая скорость босса до множителей фаз. */
    double baseSpeed() {
        return baseSpeed;
    }

    BossDefinition definition() {
        return definition;
    }

    String id() {
        return definition.id();
    }

    LivingEntity entity() {
        return entity;
    }

    UUID entityId() {
        return entity.getUniqueId();
    }

    long startedAt() {
        return startedAt;
    }

    long endsAt() {
        return endsAt;
    }

    BossBar bar() {
        return bar;
    }

    int phaseIndex() {
        return phaseIndex;
    }

    BossPhase phase() {
        return definition.phases().get(Math.clamp(phaseIndex, 0,
                definition.phases().size() - 1));
    }

    /**
     * Записывает урон игрока и возвращает новую фазу, если она сменилась.
     *
     * @param healthAfter сколько здоровья останется после удара (событие урона
     *                    приходит до применения, поэтому считает вызывающий)
     */
    BossPhase recordDamage(UUID playerId, double amount, double healthAfter) {
        if (playerId == null || amount <= 0) {
            return null;
        }
        damage.merge(playerId, amount, Double::sum);
        int next = definition.phaseIndex(Math.max(0.0, healthAfter));
        if (next > phaseIndex) {
            phaseIndex = next;
            return phase();
        }
        return null;
    }

    /** Сколько урона нанёс игрок. */
    double damageOf(UUID playerId) {
        return damage.getOrDefault(playerId, 0.0);
    }

    /** Все, кто бил. */
    Map<UUID, Double> damage() {
        return Map.copyOf(damage);
    }

    /** Доли урона 0..1. */
    Map<UUID, Double> shares() {
        return BossMath.shares(damage);
    }

    /** Лучший боец или {@code null}, если никто не бил. */
    UUID topDamager() {
        UUID best = null;
        double value = 0.0;
        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            if (entry.getValue() > value) {
                value = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** Пора ли кастовать способность: обновляет паузу. */
    boolean due(BossAbility ability, long now) {
        long next = nextCast.getOrDefault(ability, 0L);
        if (now < next) {
            return false;
        }
        nextCast.put(ability, now + ability.cooldownSeconds() * 1_000L);
        return true;
    }

    /** Забывает паузу способности — при смене фазы бой начинается заново. */
    void resetCast() {
        nextCast.clear();
    }

    /** Запоминает призванную свиту, чтобы убрать её вместе с боссом. */
    void addMinion(UUID minion) {
        if (minion != null) {
            minions.add(minion);
        }
    }

    List<UUID> minions() {
        return List.copyOf(minions);
    }

    /** Уже показываем полосу этому игроку? */
    boolean viewing(UUID playerId) {
        return viewers.contains(playerId);
    }

    void addViewer(UUID playerId) {
        if (playerId != null) {
            viewers.add(playerId);
        }
    }

    void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    Set<UUID> viewers() {
        return Set.copyOf(viewers);
    }

    /** Процент здоровья для полосы 0..1. */
    float progress() {
        double max = definition.health();
        if (max <= 0) {
            return 0.0f;
        }
        return (float) Math.clamp(entity.getHealth() / max, 0.0, 1.0);
    }
}
