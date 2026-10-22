package ru.cherdakmd.chrdkachievements;

import java.util.Locale;

/**
 * Счётчики, из которых складываются достижения.
 *
 * <p>Ид метрики — строковый («play-seconds», «mobs-killed»): он попадает в
 * `achievements.json` и в конфиг, поэтому переименование поля enum не должно
 * ломать сохранённый прогресс. {@link #byId(String)} принимает и ид, и имя
 * константы — старые файлы и ручные правки читаются одинаково.</p>
 */
public enum AchievementMetric {

    /** Секунды в игре (начисляются пульсом, пока игрок онлайн). */
    PLAY_SECONDS("play-seconds"),
    /** Число входов на сервер. */
    JOINS("joins"),
    /** Пройденные блоки (по горизонтали и вертикали, без телепортов). */
    DISTANCE_BLOCKS("distance-blocks"),
    /** Сломанные блоки. */
    BLOCKS_MINED("blocks-mined"),
    /** Алмазная руда и древние обломки. */
    ORES_MINED("ores-mined"),
    /** Убитые мобы (кроме боссов). */
    MOBS_KILLED("mobs-killed"),
    /** Дракон Края, Визер и Варден. */
    BOSSES_KILLED("bosses-killed"),
    /** Пойманная рыба (только успешный улов). */
    FISH_CAUGHT("fish-caught"),
    /** Скрафченные предметы. */
    ITEMS_CRAFTED("items-crafted"),
    /** Сообщения в общий и локальный чат. */
    CHAT_MESSAGES("chat-messages"),
    /** Действующая проходка (0/1) — опрашивается через API ядра. */
    PASS_ACTIVE("pass-active"),
    /** Аккаунт зарегистрирован (0/1). */
    REGISTERED("registered"),
    /** Привязана 2FA (0/1). */
    TWO_FACTOR("two-factor"),
    /** Служебная: сколько достижений уже открыто (для мета-достижения). */
    ACHIEVEMENTS("achievements"),

    /* ------------------- волна 28: 24 новых достижения (1.4.138) ------------------- */

    /** Поставленные блоки (строительство). */
    BLOCKS_PLACED("blocks-placed"),
    /** Зачарованные предметы (стол или наковальня). */
    ITEMS_ENCHANTED("items-enchanted"),
    /** Сваренные зелья (завершённая варка). */
    POTIONS_BREWED("potions-brewed"),
    /** Удачные сделки с жителями. */
    VILLAGER_TRADES("villager-trades"),
    /** Съеденная еда. */
    FOOD_EATEN("food-eaten"),
    /** Прирученные животные. */
    ANIMALS_TAMED("animals-tamed"),
    /** Ночи, проведённые в кровати (успешный сон). */
    BEDS_SLEPT("beds-slept"),
    /** Переходы в Нижний мир. */
    NETHER_VISITS("nether-visits"),
    /** Переходы в Край. */
    END_VISITS("end-visits"),
    /** Выпущенные стрелы и болты. */
    ARROWS_SHOT("arrows-shot"),
    /** Нанесённый урон (по мобам, без PvP). */
    DAMAGE_DEALT("damage-dealt"),
    /** Полученный урон (любой источник). */
    DAMAGE_TAKEN("damage-taken"),
    /** Смерти: «упорный» возвращается, а не бросает сервер. */
    DEATHS("deaths"),
    /** Удары, принятые на щит. */
    SHIELD_BLOCKS("shield-blocks"),
    /** Сломанные от износа инструменты. */
    TOOLS_BROKEN("tools-broken"),
    /** Пиковый баланс банка (максимум, а не сумма). */
    BANK_BALANCE("bank-balance"),
    /** Сумма переводов, отправленных игроком. */
    BANK_SENT("bank-sent"),
    /** Сумма переводов, полученных игроком. */
    BANK_RECEIVED("bank-received");

    private final String id;

    AchievementMetric(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Строгий разбор ида; неизвестное значение — {@code null}. */
    public static AchievementMetric byId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AchievementMetric metric : values()) {
            if (metric.id.equals(normalized) || metric.name().equalsIgnoreCase(normalized)) {
                return metric;
            }
        }
        return null;
    }

    /** Счётчик «да/нет»: значение больше нуля считается выполненным. */
    public boolean flag() {
        return this == PASS_ACTIVE || this == REGISTERED || this == TWO_FACTOR;
    }
}
