package ru.cherdakmd.chrdkevents;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Нашествие мобов события (1.4.176).
 *
 * <p>«Кровавая луна» раньше только щедрее делилась добычей: ночь называлась
 * кровавой, а мобов было столько же, сколько обычно. Теперь у события есть
 * вторая половина: естественный спавн усиливается множителем, а раз в
 * {@code wave-interval-seconds} рядом с игроками поднимается волна монстров.</p>
 *
 * <p>Множитель работает «эхом»: на каждый обычный спавн рядом появляется ещё
 * столько же мобов, сколько обещает множитель (2.5 — один гарантированно и
 * второй с шансом 50 %). Волны — отдельный, более редкий и заметный приём.</p>
 *
 * <p>Потолки важнее конфига: рядом с игроком не может быть больше
 * {@code max-near-player} монстров, за волну — не больше {@link #MAX_WAVE_SIZE},
 * а множитель не выше {@link #MAX_MULTIPLIER}. Иначе один вечер с событием
 * ронял бы сервер.</p>
 *
 * @param enabled            включено ли нашествие у события
 * @param multiplier         множитель естественного спавна (1.0..3.0)
 * @param waveIntervalSeconds пауза между волнами (10..600)
 * @param waveSize           сколько мобов в волне (1..12)
 * @param maxNearPlayer      потолок монстров в 32 блоках от игрока
 * @param minDistance        ближе этого волна не спавнит (6..24)
 * @param maxDistance        дальше этого волна не спавнит (до 48)
 * @param onlyAtNight        спавнить только ночью в обычном мире
 * @param mobs               кого именно поднимать
 */
public record HordeSpawn(boolean enabled, double multiplier, int waveIntervalSeconds, int waveSize,
                         int maxNearPlayer, int minDistance, int maxDistance, boolean onlyAtNight,
                         List<EntityType> mobs) {

    /** Меньше единицы множитель не имеет смысла, больше трёх — уже не спавн, а стена. */
    public static final double MIN_MULTIPLIER = 1.0;
    public static final double MAX_MULTIPLIER = 3.0;
    /** Больше двух «лишних» мобов на один естественный спавн не появляется. */
    public static final int MAX_EXTRA_PER_SPAWN = 2;
    public static final int MIN_WAVE_INTERVAL_SECONDS = 10;
    public static final int MAX_WAVE_INTERVAL_SECONDS = 600;
    public static final int MAX_WAVE_SIZE = 12;
    /** Потолок монстров рядом: считается в радиусе {@link #NEAR_RADIUS}. */
    public static final int MAX_NEAR_PLAYER_LIMIT = 64;
    /** Радиус подсчёта «сколько мобов уже рядом». */
    public static final double NEAR_RADIUS = 32.0;
    public static final int MIN_DISTANCE_FLOOR = 6;
    public static final int MAX_DISTANCE_CEILING = 48;
    /** Выключенное нашествие: у события без секции {@code horde}. */
    public static final HordeSpawn NONE = new HordeSpawn(false, 1.0, 45, 3, 24, 10, 24, true,
            List.of());

    public HordeSpawn {
        multiplier = clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        waveIntervalSeconds = clamp(waveIntervalSeconds, MIN_WAVE_INTERVAL_SECONDS,
                MAX_WAVE_INTERVAL_SECONDS);
        waveSize = clamp(waveSize, 1, MAX_WAVE_SIZE);
        maxNearPlayer = clamp(maxNearPlayer, 1, MAX_NEAR_PLAYER_LIMIT);
        minDistance = clamp(minDistance, MIN_DISTANCE_FLOOR, MAX_DISTANCE_CEILING);
        maxDistance = clamp(maxDistance, minDistance, MAX_DISTANCE_CEILING);
        mobs = copyTypes(mobs);
    }

    /** Нашествие вообще может работать: есть кого спавнить. */
    public boolean usable() {
        return enabled && !mobs.isEmpty();
    }

    /**
     * Сколько лишних мобов добавить к одному естественному спавну.
     *
     * <p>Дробная часть множителя — это шанс: 2.5 даёт одного моба всегда и
     * второго в половине случаев. Так среднее совпадает с множителем, но
     * игрок не видит «полтора зомби».</p>
     */
    public int extraSpawns(Random random) {
        double extra = multiplier - 1.0;
        if (extra <= 0.0) {
            return 0;
        }
        int whole = (int) Math.floor(extra);
        double fraction = extra - whole;
        int result = whole;
        if (fraction > 0.0 && random.nextDouble() < fraction) {
            result++;
        }
        return Math.clamp(result, 0, MAX_EXTRA_PER_SPAWN);
    }

    /**
     * Можно ли спавнить прямо сейчас.
     *
     * <p>В обычном мире ночь — обязательное условие (иначе зомби горят на
     * солнце и событие выглядит поломкой). В Нижнем мире и Энде ночи нет,
     * поэтому там ограничение не действует.</p>
     */
    public boolean allowSpawnNow(boolean normalWorld, boolean dayTime) {
        return !onlyAtNight || !normalWorld || !dayTime;
    }

    /** Случайный моб волны. */
    public EntityType mob(Random random) {
        if (mobs.isEmpty()) {
            return null;
        }
        return mobs.get(random.nextInt(mobs.size()));
    }

    private static List<EntityType> copyTypes(List<EntityType> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<EntityType> copy = new ArrayList<>();
        for (EntityType type : source) {
            if (type != null && !copy.contains(type)) {
                copy.add(type);
            }
        }
        return List.copyOf(copy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
