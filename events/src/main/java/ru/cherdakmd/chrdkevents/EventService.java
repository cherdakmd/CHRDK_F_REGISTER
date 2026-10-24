package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Расписание и состояние мировых событий (1.4.160).
 *
 * <p>Класс чистый: время приходит параметром {@code now}, онлайн — числом,
 * случайность — {@link Random}. Поэтому «событие закончилось», «все на паузе»
 * и «онлайн меньше порога» проверяются тестами без сервера.</p>
 *
 * <p>Жизненный цикл: {@code planNext → tryStart → tick → stop → planNext}.
 * Пауза между событиями случайна в заданном диапазоне, а после окончания
 * событие уходит в свой {@code cooldown-seconds}: одно и то же не повторяется
 * подряд.</p>
 */
public final class EventService {

    /** Пауза перед повтором, если старт не удался (мало игроков или все на паузе). */
    public static final long RETRY_MILLIS = 60_000L;

    private volatile EventConfig config;
    private final Random random;
    private WorldEvent current;
    private long endsAt;
    private long nextAt;
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();

    public EventService(EventConfig config, Random random) {
        this.config = config == null ? EventConfig.defaults() : config;
        this.random = random == null ? new Random() : random;
    }

    /** Применяет новый конфиг, не обрывая текущее событие. */
    public void applyConfig(EventConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    /**
     * Восстанавливает расписание после перезапуска: паузы и момент следующего
     * события. Активное событие не восстанавливается — см. {@link EventsStore}.
     *
     * @param nextAt    сохранённый момент следующего запуска (0 — не было)
     * @param cooldowns сохранённые паузы
     * @param now       текущее время
     */
    public void restoreSchedule(long nextAt, Map<String, Long> cooldowns, long now) {
        this.cooldowns.clear();
        if (cooldowns != null) {
            cooldowns.forEach((id, until) -> {
                if (id != null && until != null && until > now) {
                    this.cooldowns.put(id, until);
                }
            });
        }
        if (nextAt > now) {
            this.nextAt = nextAt;
        } else {
            planNext(now);
        }
    }

    public EventConfig config() {
        return config;
    }

    /* --------------------------------- состояние ------------------------------- */

    /** Текущее событие или {@code null}. */
    public WorldEvent current() {
        return current;
    }

    public boolean active() {
        return current != null;
    }

    /** Момент окончания текущего события (мс); 0 — события нет. */
    public long endsAt() {
        return endsAt;
    }

    /** Момент следующего автоматического старта (мс); 0 — ещё не запланирован. */
    public long nextAt() {
        return nextAt;
    }

    /** Сколько секунд осталось у текущего события (0 — нет события). */
    public long secondsLeft(long now) {
        if (current == null) {
            return 0L;
        }
        long left = endsAt - now;
        return left <= 0L ? 0L : (left + 999L) / 1_000L;
    }

    /** Сколько секунд до следующего события (0 — событие уже идёт). */
    public long secondsToNext(long now) {
        if (current != null) {
            return 0L;
        }
        if (nextAt <= 0L) {
            return 0L;
        }
        long left = nextAt - now;
        return left <= 0L ? 0L : (left + 999L) / 1_000L;
    }

    /** Копия пауз: ид события → момент, до которого оно недоступно. */
    public Map<String, Long> cooldowns() {
        return Map.copyOf(cooldowns);
    }

    /** Событие на паузе? */
    public boolean coolingDown(String id, long now) {
        Long until = cooldowns.get(id);
        return until != null && until > now;
    }

    /* -------------------------------- расписание ------------------------------- */

    /** Планирует следующее событие через случайную паузу. */
    public void planNext(long now) {
        int min = config.minDelaySeconds();
        int max = config.maxDelaySeconds();
        int span = Math.max(0, max - min);
        int delay = min + (span <= 0 ? 0 : random.nextInt(span + 1));
        nextAt = now + delay * 1_000L;
    }

    /** Принудительный запуск по иду (админ). Возвращает событие или {@code null}. */
    public WorldEvent start(String id, long now) {
        if (!config.enabled() || current != null) {
            return null;
        }
        WorldEvent event = config.event(id);
        if (event == null || !event.usable()) {
            return null;
        }
        return begin(event, now);
    }

    /**
     * Пытается запустить событие по расписанию.
     *
     * @param online сколько игроков в сети
     * @return запущенное событие или {@code null}
     */
    public WorldEvent tryStart(long now, int online) {
        if (!config.enabled() || current != null) {
            return null;
        }
        if (nextAt <= 0L) {
            planNext(now);
            return null;
        }
        if (now < nextAt) {
            return null;
        }
        if (online < config.minPlayers()) {
            nextAt = now + RETRY_MILLIS;
            return null;
        }
        WorldEvent picked = EventPicker.pick(config.events(), cooldowns, now, random);
        if (picked == null) {
            // Все на паузе — попробуем через минуту, а не через полный диапазон.
            nextAt = now + RETRY_MILLIS;
            return null;
        }
        return begin(picked, now);
    }

    private WorldEvent begin(WorldEvent event, long now) {
        this.current = event;
        this.endsAt = now + event.durationSeconds() * 1_000L;
        this.nextAt = 0L;
        return event;
    }

    /** Завершает текущее событие досрочно или по времени. */
    public WorldEvent stop(long now) {
        if (current == null) {
            return null;
        }
        WorldEvent stopped = current;
        current = null;
        endsAt = 0L;
        cooldowns.put(stopped.id(), now + stopped.cooldownSeconds() * 1_000L);
        planNext(now);
        return stopped;
    }

    /**
     * Один шаг таймера.
     *
     * @return событие, которое только что закончилось, или {@code null}
     */
    public WorldEvent tick(long now) {
        if (current != null && now >= endsAt) {
            return stop(now);
        }
        if (current == null && nextAt <= 0L) {
            planNext(now);
        }
        return null;
    }

    /* ---------------------------------- бонусы --------------------------------- */

    /** Надбавка к опыту в процентах (0 — событие не про опыт). */
    public int xpPercent() {
        return current != null && current.kind() == EventKind.XP ? current.percent() : 0;
    }

    /** Шанс лишнего куска руды (0 — событие не про руду). */
    public int miningPercent() {
        return current != null && current.kind() == EventKind.MINING ? current.percent() : 0;
    }

    /** Руда, с которой работает текущее событие. */
    public List<Material> miningBlocks() {
        return current != null && current.kind() == EventKind.MINING
                ? current.blocks() : List.of();
    }

    /** Шанс лишнего дропа (0 — событие не про дроп). */
    public int lootPercent() {
        return current != null && current.kind() == EventKind.LOOT ? current.percent() : 0;
    }

    /** Предметы, которые падают сверху. */
    public List<Material> lootItems() {
        return current != null && current.kind() == EventKind.LOOT
                ? current.items() : List.of();
    }

    /** Эффекты текущего события (пусто — событие не про эффекты). */
    public List<WorldEvent.Effect> effects() {
        return current != null && current.kind() == EventKind.EFFECT
                ? current.effects() : List.of();
    }
}
