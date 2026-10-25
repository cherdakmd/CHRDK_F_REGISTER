package ru.cherdakmd.chrdkbosses;

import org.bukkit.entity.EntityType;

import java.util.Locale;

/**
 * Способность босса (1.4.162).
 *
 * <p>Способность — это не скрипт, а короткое действие с понятным эффектом:
 * топнуть по земле, призвать свиту, притянуть игрока, вылечиться. Параметры
 * общие для всех видов, а какие из них нужны — решает {@link Type}. Поэтому
 * конфиг остаётся плоским списком, а не вложенным языком сценариев.</p>
 *
 * @param type            вид действия
 * @param cooldownSeconds пауза между повторами
 * @param radius          радиус действия в блоках
 * @param amount          урон, количество свиты или процент лечения
 * @param entity          кого призывать (только {@link Type#SUMMON})
 * @param effect          ид эффекта (только {@link Type#CURSE} и {@link Type#ENRAGE})
 * @param amplifier       уровень эффекта с нуля
 * @param durationSeconds длительность эффекта или горения
 */
public record BossAbility(Type type, int cooldownSeconds, double radius, double amount,
                          EntityType entity, String effect, int amplifier, int durationSeconds) {

    /** Потолок паузы: способность не должна ждать дольше десяти минут. */
    public static final int MAX_COOLDOWN_SECONDS = 600;
    /** Потолок радиуса: босс не должен доставать через полкарты. */
    public static final double MAX_RADIUS = 32.0;
    /** Потолок свиты: больше — уже не босс, а рейд. */
    public static final int MAX_SUMMONS = 8;
    /** Потолок уровня эффекта. */
    public static final int MAX_AMPLIFIER = 9;

    public enum Type {
        /** Удар по земле: урон и отброс игроков вокруг. */
        STOMP,
        /** Призыв свиты вокруг босса. */
        SUMMON,
        /** Огненная волна: поджигает игроков вокруг. */
        FIRE_NOVA,
        /** Притягивает игроков к боссу. */
        PULL,
        /** Прыжок к случайному игроку на арене. */
        LEAP,
        /** Проклятие: накладывает эффект на игроков вокруг. */
        CURSE,
        /** Лечение босса на процент от максимума. */
        HEAL,
        /** Ярость: ускоряет босса на время. */
        ENRAGE;

        /** Разбирает вид способности; регистр и пробелы не важны. */
        public static Type parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        /** Ид вида для конфига и сообщений. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public BossAbility {
        type = type == null ? Type.STOMP : type;
        cooldownSeconds = Math.clamp(cooldownSeconds, 3, MAX_COOLDOWN_SECONDS);
        radius = Math.clamp(radius, 1.0, MAX_RADIUS);
        amount = Math.clamp(amount, 0.0, 100.0);
        entity = entity == null ? EntityType.ZOMBIE : entity;
        effect = effect == null ? "" : effect.trim().toLowerCase(Locale.ROOT);
        amplifier = Math.clamp(amplifier, 0, MAX_AMPLIFIER);
        durationSeconds = Math.clamp(durationSeconds, 0, 60);
        if (type == Type.SUMMON) {
            amount = Math.clamp((int) Math.round(amount <= 0 ? 1 : amount), 1, MAX_SUMMONS);
        }
    }

    /** Есть ли у способности всё, что нужно для её вида. */
    public boolean usable() {
        return switch (type) {
            case STOMP, HEAL -> amount > 0;
            case FIRE_NOVA -> durationSeconds > 0;
            case CURSE -> !effect.isEmpty() && durationSeconds > 0;
            case SUMMON -> entity != null;
            case PULL, LEAP, ENRAGE -> true;
        };
    }

    /** Сколько свиты призывать. */
    public int summons() {
        return Math.clamp((int) Math.round(amount), 1, MAX_SUMMONS);
    }

    /** Процент лечения, зажатый в безопасные 1..50. */
    public int healPercent() {
        return (int) Math.clamp(Math.round(amount), 1, 50);
    }

    /** Короткая подпись для меню и логов. */
    public String summary() {
        return switch (type) {
            case STOMP -> type.id() + " " + (int) amount + " урона / " + (int) radius + " бл.";
            case SUMMON -> type.id() + " " + entity.name().toLowerCase(Locale.ROOT)
                    + " x" + summons();
            case FIRE_NOVA -> type.id() + " " + durationSeconds + " с";
            case PULL, LEAP -> type.id() + " / " + (int) radius + " бл.";
            case CURSE -> type.id() + " " + effect + " " + (amplifier + 1);
            case HEAL -> type.id() + " +" + healPercent() + "%";
            case ENRAGE -> type.id() + " " + durationSeconds + " с";
        };
    }
}
