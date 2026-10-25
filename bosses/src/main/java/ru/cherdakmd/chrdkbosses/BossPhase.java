package ru.cherdakmd.chrdkbosses;

import java.util.ArrayList;
import java.util.List;

/**
 * Фаза боя (1.4.162).
 *
 * <p>Фаза начинается, когда здоровье босса опускается до её порога. Порог —
 * процент от максимума: так один и тот же конфиг работает и для босса на
 * 100 сердец, и для босса на 1000. Первая фаза всегда 100 % — это состояние
 * «пришёл, познакомились».</p>
 *
 * @param fromPercent       процент здоровья, с которого действует фаза
 * @param name              имя фазы для полосы сверху и объявления
 * @param damageMultiplier  множитель урона босса
 * @param speedMultiplier   множитель скорости босса
 * @param abilities         способности фазы
 */
public record BossPhase(int fromPercent, String name, double damageMultiplier,
                        double speedMultiplier, List<BossAbility> abilities) {

    /** Сколько фаз максимум: больше не помещается в полосу и в голову игроку. */
    public static final int MAX_PHASES = 4;
    /** Сколько способностей на фазу. */
    public static final int MAX_ABILITIES = 6;

    public BossPhase {
        fromPercent = Math.clamp(fromPercent, 1, 100);
        name = name == null || name.isBlank() ? "Фаза " + fromPercent : name.trim();
        damageMultiplier = Math.clamp(damageMultiplier, 0.5, 3.0);
        speedMultiplier = Math.clamp(speedMultiplier, 0.5, 2.0);
        abilities = copy(abilities);
    }

    private static List<BossAbility> copy(List<BossAbility> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<BossAbility> out = new ArrayList<>();
        for (BossAbility ability : source) {
            if (ability == null || !ability.usable()) {
                continue;
            }
            if (out.size() >= MAX_ABILITIES) {
                break;
            }
            out.add(ability);
        }
        return List.copyOf(out);
    }

    /** Есть ли чем удивить во второй половине боя. */
    public boolean hasAbilities() {
        return !abilities.isEmpty();
    }

    /** Нормализует список фаз: порядок по убыванию, первый порог — 100. */
    public static List<BossPhase> normalize(List<BossPhase> source) {
        List<BossPhase> cleaned = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return List.of(new BossPhase(100, "Фаза 1", 1.0, 1.0, List.of()),
                    new BossPhase(50, "Фаза 2", 1.0, 1.0, List.of()));
        }
        for (BossPhase phase : source) {
            if (phase == null) {
                continue;
            }
            if (cleaned.size() >= MAX_PHASES) {
                break;
            }
            cleaned.add(phase);
        }
        if (cleaned.isEmpty()) {
            return normalize(List.of());
        }
        cleaned.sort((left, right) -> Integer.compare(right.fromPercent(), left.fromPercent()));
        // Пороги обязаны убывать: фаза не может начаться выше предыдущей.
        List<BossPhase> out = new ArrayList<>();
        out.add(new BossPhase(100, cleaned.get(0).name(), cleaned.get(0).damageMultiplier(),
                cleaned.get(0).speedMultiplier(), cleaned.get(0).abilities()));
        int last = 100;
        for (int index = 1; index < cleaned.size(); index++) {
            BossPhase phase = cleaned.get(index);
            int threshold = Math.min(last - 1, phase.fromPercent());
            if (threshold <= 0) {
                break;
            }
            out.add(new BossPhase(threshold, phase.name(), phase.damageMultiplier(),
                    phase.speedMultiplier(), phase.abilities()));
            last = threshold;
        }
        return List.copyOf(out);
    }
}
