package ru.cherdakmd.chrdkevents;

import org.bukkit.Bukkit;
import org.bukkit.World;
import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;

/**
 * Проверка «это место в привате?» (1.4.176).
 *
 * <p>Событие живёт своей жизнью и не должно трогать чужие участки: звезда не
 * падает в приват, воронка не выбивается в привате, волна мобов не поднимается
 * внутри забора. Индекс приватов регистрирует аддон CHRDK_Claims через
 * {@code ServicesManager}; если аддона нет или он сломался, отвечаем «приват» —
 * лучше пропустить точку, чем снести чужой дом.</p>
 */
final class ClaimGuard {

    private ClaimGuard() {
    }

    /** Занят ли чанк, в который попадает блок {@code (blockX, blockZ)} мира. */
    static boolean claimed(World world, int blockX, int blockZ) {
        if (world == null) {
            return true;
        }
        try {
            SpawnClaimIndex index = Bukkit.getServicesManager().load(SpawnClaimIndex.class);
            return index != null && index.isClaimed(world.getName(), blockX, blockZ);
        } catch (RuntimeException | LinkageError error) {
            return true;
        }
    }
}
