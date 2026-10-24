package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Locale;

/**
 * Босс (1.4.162).
 *
 * <p>Определение целиком приходит из конфига: кем он выглядит, сколько у него
 * здоровья, какие фазы, где арена и когда его можно звать снова. Один и тот же
 * объект обслуживает и таймер, и админскую команду, и меню — поэтому «жив»
 * и «на кулдауне» не могут разъехаться между экранами.</p>
 *
 * @param id                   ид босса
 * @param name                 имя для объявления и полосы
 * @param description          короткое описание
 * @param entity               кем он является
 * @param health               максимум здоровья (единицы здоровья: 1 сердце = 2)
 * @param icon                 иконка в меню
 * @param glow                 подсветить контуром
 * @param arena                арена боя
 * @param phases               фазы по убыванию здоровья
 * @param reward               награда за убийство
 * @param spawnIntervalSeconds пауза между появлениями
 * @param durationSeconds      сколько живёт без убийства
 * @param minPlayers           сколько игроков нужно для авто-спавна
 * @param auto                 может ли появляться сам
 */
public record BossDefinition(String id, String name, String description, EntityType entity,
                             double health, Material icon, boolean glow, Arena arena,
                             List<BossPhase> phases, BossReward reward,
                             int spawnIntervalSeconds, int durationSeconds, int minPlayers,
                             boolean auto) {

    /** Потолок здоровья: 2048 — это уже стена, а не босс. */
    public static final double MAX_HEALTH = 2_048.0;
    /** Минимум здоровья. */
    public static final double MIN_HEALTH = 20.0;
    /** Потолок паузы между появлениями — сутки. */
    public static final int MAX_INTERVAL_SECONDS = 86_400;
    /** Минимальная пауза: босс не должен спавниться каждые пять минут. */
    public static final int MIN_INTERVAL_SECONDS = 300;
    /** Потолок жизни босса — два часа. */
    public static final int MAX_DURATION_SECONDS = 7_200;
    /** Минимальная жизнь: меньше — не бой. */
    public static final int MIN_DURATION_SECONDS = 120;

    public BossDefinition {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        entity = entity == null ? EntityType.ZOMBIE : entity;
        health = Math.clamp(health, MIN_HEALTH, MAX_HEALTH);
        icon = icon == null || icon == Material.AIR ? Material.NETHERITE_SWORD : icon;
        arena = arena == null ? new Arena("", 0, Arena.AUTO_HEIGHT, 0, 8) : arena;
        phases = BossPhase.normalize(phases);
        reward = reward == null ? BossReward.none() : reward;
        spawnIntervalSeconds = Math.clamp(spawnIntervalSeconds, MIN_INTERVAL_SECONDS,
                MAX_INTERVAL_SECONDS);
        durationSeconds = Math.clamp(durationSeconds, MIN_DURATION_SECONDS,
                MAX_DURATION_SECONDS);
        minPlayers = Math.clamp(minPlayers, 1, 100);
    }

    /** Годится ли босс для мира: есть арена, фазы и награда. */
    public boolean usable() {
        return !id.isEmpty() && arena.usable() && !reward.empty() && phases.size() >= 2;
    }

    /** Фаза по текущему здоровью. */
    public BossPhase phase(double currentHealth) {
        return phases.get(BossMath.phaseIndex(phases, currentHealth, health));
    }

    /** Номер фазы с нуля — для счётчиков и текстов. */
    public int phaseIndex(double currentHealth) {
        return BossMath.phaseIndex(phases, currentHealth, health);
    }

    /** Нужно ли подсвечивать контуром. */
    public boolean glowing() {
        return glow;
    }

    /** Короткая подпись для меню и лога. */
    public String label() {
        return name + " · " + entity.name().toLowerCase(Locale.ROOT)
                + " · " + (int) health + " hp";
    }
}
