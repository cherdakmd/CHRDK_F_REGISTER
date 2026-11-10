package ru.cherdakmd.chrdkdisasters;

/**
 * Один запущенный экземпляр катастрофы: состояние машины фаз и счётчики.
 * Класс намеренно не ссылается на Bukkit-типы — координаты хранятся
 * примитивами, поэтому переходы фаз целиком проверяются JUnit без сервера.
 * Bukkit-движок держит рядом {@code Location} и превращает его в эффекты.
 */
public final class DisasterRun {

    public enum Phase {
        WARNING,
        ACTIVE,
        FADING,
        DONE
    }

    private final int id;
    private final DisasterKind kind;
    private final int level;
    private final String player;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final DisasterPhasePlan plan;

    private Phase phase = Phase.WARNING;
    private int ticksLeft;
    private int activeStep;
    private int changed;
    private boolean stopped;

    public DisasterRun(int id, DisasterKind kind, int level, String player,
                       String world, double x, double y, double z,
                       DisasterPhasePlan plan) {
        this.id = id;
        this.kind = kind;
        this.level = Math.max(1, level);
        this.player = player == null ? "" : player;
        this.world = world == null ? "" : world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.plan = plan;
        this.ticksLeft = plan.warnTicks();
    }

    /** Один тик движка: сначала отрабатывает текущая фаза, потом переход. */
    public void advance() {
        if (phase == Phase.DONE) {
            return;
        }
        if (stopped) {
            phase = Phase.DONE;
            ticksLeft = 0;
            return;
        }
        if (ticksLeft > 1) {
            ticksLeft--;
            return;
        }
        switch (phase) {
            case WARNING -> {
                phase = Phase.ACTIVE;
                ticksLeft = plan.activeTicks();
                activeStep = 0;
            }
            case ACTIVE -> {
                phase = Phase.FADING;
                ticksLeft = plan.fadeTicks();
            }
            case FADING -> {
                phase = Phase.DONE;
                ticksLeft = 0;
            }
            default -> {
                phase = Phase.DONE;
                ticksLeft = 0;
            }
        }
    }

    /** Следующий шаг активной фазы: по нему распределяется лимит блоков. */
    public void countStep() {
        if (phase == Phase.ACTIVE) {
            activeStep++;
        }
    }

    public void addChanged(int blocks) {
        changed += Math.max(0, blocks);
    }

    /** Досрочная остановка командой: эффекты снимаются, мир откатывается. */
    public void stopNow() {
        stopped = true;
        phase = Phase.DONE;
        ticksLeft = 0;
    }

    public int secondsLeft() {
        return ticksLeft * plan.tickSeconds();
    }

    public boolean done() {
        return phase == Phase.DONE;
    }

    public int id() {
        return id;
    }

    public DisasterKind kind() {
        return kind;
    }

    public int level() {
        return level;
    }

    public String player() {
        return player;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public DisasterPhasePlan plan() {
        return plan;
    }

    public Phase phase() {
        return phase;
    }

    public int ticksLeft() {
        return ticksLeft;
    }

    public int activeStep() {
        return activeStep;
    }

    public int changed() {
        return changed;
    }

    public boolean stopped() {
        return stopped;
    }
}
