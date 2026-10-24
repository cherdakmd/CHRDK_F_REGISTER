package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Мост «CHRDK_Artifacts → другие аддоны» (1.4.166).
 *
 * <p>Реализацию регистрирует <b>CHRDK_Artifacts</b> через {@code ServicesManager};
 * потребители (например, <b>CHRDK_Treasures</b>) спрашивают её мягко и без
 * аддона артефактов просто не выдают их из своих источников.</p>
 *
 * <pre>{@code
 * Bukkit.getServicesManager().register(ChrdkArtifactsApi.class,
 *         new ArtifactsApiBridge(this), this, ServicePriority.Normal);
 * }</pre>
 *
 * <p>Все методы безопасны в основном потоке и терпимы к неизвестным идам:
 * выключенный или удалённый артефакт даёт {@code null}/{@code false}/пустой
 * список, а не исключение.</p>
 */
public interface ChrdkArtifactsApi {

    /** Идентификаторы всех артефактов каталога (порядок стабилен). */
    List<String> ids();

    /** Идентификаторы артефактов одной стороны. */
    List<String> ids(boolean cursed);

    /** Есть ли такой артефакт в каталоге (регистр не важен). */
    boolean isKnown(String id);

    /** Проклятая ли это реликвия (иначе — светлая). */
    boolean isCursed(String id);

    /**
     * Имя артефакта для сообщений (MiniMessage из {@code messages.yml} аддона).
     * Пустая строка — артефакта нет.
     */
    String name(String id);

    /**
     * Готовый предмет артефакта со свернувшимися свойствами и именем.
     *
     * @param amount количество (1..64, зажимается размером стака материала)
     * @return предмет или {@code null}, если ид неизвестен
     */
    ItemStack create(String id, int amount);
}
