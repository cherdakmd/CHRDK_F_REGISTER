package ru.cherdakmd.chrdktreasures;

import java.util.List;
import java.util.Random;

/**
 * Страховочный набор клада (1.4.169): клад не может быть пустым.
 *
 * <p>Обычно добычу собирают лут-таблицы, склянки опыта и особые находки. Но
 * если администратор почистил {@code loot.yml}, выключил склянки и снял все
 * шансы — бросок честно возвращал пустой список, и на месте находки вставала
 * пустая бочка. Игрок видел «клад», открывал его и ничего не получал.</p>
 *
 * <p>Теперь перед материализацией бочки сервис проверяет список: пусто —
 * добавляется этот набор. Склянки опыта выбраны не случайно: они не занимают
 * экономику алмазов, полезны любому уровню и совпадают с правилом «опыт в
 * каждом кладе».</p>
 *
 * <p>Класс чистый: ни Bukkit, ни {@code Messages} — количества проверяются
 * юнит-тестами без сервера.</p>
 */
public final class TreasureFallback {

    /** Минимум склянок в страховочном наборе. */
    public static final int MIN_BOTTLES = 2;

    /** Максимум склянок в страховочном наборе. */
    public static final int MAX_BOTTLES = 4;

    private TreasureFallback() {
    }

    /**
     * Собирает страховочный набор.
     *
     * @param random источник случайности; {@code null} — минимальный набор
     * @return непустой список: 2–4 склянки опыта
     */
    public static List<TreasureLoot.LootStack> roll(Random random) {
        int amount = MIN_BOTTLES;
        if (random != null) {
            amount += random.nextInt(MAX_BOTTLES - MIN_BOTTLES + 1);
        }
        return List.of(new TreasureLoot.LootStack(TreasureExperience.MATERIAL, amount));
    }

    /** Похоже ли содержимое на страховочный набор — для сообщений и тестов. */
    public static boolean isFallback(TreasureLoot.LootStack stack) {
        return stack != null
                && TreasureExperience.MATERIAL.equalsIgnoreCase(stack.material())
                && stack.amount() >= MIN_BOTTLES
                && stack.amount() <= MAX_BOTTLES
                && stack.name().isBlank()
                && !stack.hasElixir();
    }
}
