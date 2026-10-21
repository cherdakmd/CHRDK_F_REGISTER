package ru.cherdakmd.chrdktreasures;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Публичный API кладоискательства для других аддонов.
 *
 * <p>Регистрируется в {@code ServicesManager}, поэтому квесты, ивенты и магазины
 * могут спросить прогресс игрока или выдать именную находку, не завися от
 * внутренних классов. Иды — строки из каталога ({@code plains-reap-hook}):
 * неизвестный ид не бросает исключение, а возвращает 0/{@code false}.</p>
 */
public interface ChrdkTreasuresApi {

    /** Сколько блоков игрок добыл с момента установки плагина. */
    long mined(Player player);

    /** Сколько кладов найдено всего. */
    long found(Player player);

    /** Сколько кладов найдено конкретной редкости ({@code common}..{@code mythical}). */
    long tierFound(Player player, String tierId);

    /** Сколько уникальных именных предметов в коллекции игрока. */
    int collection(Player player);

    /** Сколько всего именных предметов в каталоге. */
    int totalItems();

    /** Сколько раз игрок находил конкретный предмет. */
    int itemCount(Player player, String itemId);

    /** Есть ли предмет в коллекции игрока. */
    boolean hasItem(Player player, String itemId);

    /**
     * Сумма событийного эффекта по надетым находкам.
     *
     * @param kind вид эффекта в нижнем регистре с дефисами ({@code lifesteal})
     */
    double effect(Player player, String kind);

    /**
     * Выдаёт именной предмет (награда квеста, подарок).
     *
     * @return {@code true}, если предмет выдан и записан в коллекцию
     */
    boolean give(Player player, String itemId);

    /** Звание кладоискателя: {@code novice}..{@code legend}. */
    String rank(Player player);

    /** Текущая серия находок (0 — серии нет). */
    int streak(Player player);

    /** Личный рекорд серии. */
    int bestStreak(Player player);

    /** Регион клада в точке ({@code plains}..{@code end}) или {@code null}. */
    String region(Location location);

    /**
     * Регион действующего сета игрока ({@code plains}..{@code end}) или
     * {@code null}, если собрано меньше двух предметов одного биома.
     */
    String activeSet(Player player);

    /** Сколько предметов сета региона надето на игрока. */
    int setPieces(Player player, String regionId);

    /** Готова ли активная способность предмета: остаток перезарядки в секундах. */
    long abilityCooldown(Player player, String itemId);
}
