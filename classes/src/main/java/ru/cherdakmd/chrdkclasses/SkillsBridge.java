package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

import java.util.UUID;

/**
 * Чтение уровней навыков через публичный API {@code CHRDK_Skills} (1.4.164).
 *
 * <p>Аддон навыков необязателен: если его нет, {@link #available()} возвращает
 * {@code false}, уровни нулевые, а класс работает на пассивной основе. Ошибка
 * {@link LinkageError} ловится отдельно — так плагин не падает на сервере, где
 * стоит несовместимая версия навыков.</p>
 *
 * <p>API принимает {@link Player}, поэтому уровни читаются только у онлайн-игрока.
 * Для оффлайн-карточки в {@code /profile} показывается выбор класса и таланты:
 * прокачка подтянется, когда игрок зайдёт.</p>
 */
final class SkillsBridge implements SkillSource {

    private final ClassesPlugin plugin;
    private ChrdkSkillsApi api;
    private boolean lookedUp;

    SkillsBridge(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /** API навыков или {@code null}; повторный поиск — только после перезагрузки. */
    ChrdkSkillsApi api() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                api = Bukkit.getServicesManager().load(ChrdkSkillsApi.class);
            } catch (RuntimeException | LinkageError error) {
                api = null;
                plugin.getLogger().warning("[CHRDK_Classes] API навыков недоступен: "
                        + error.getMessage());
            }
        }
        return api;
    }

    /** Забывает найденный сервис: после {@code /classadmin reload} навыки могли включиться. */
    void reset() {
        api = null;
        lookedUp = false;
    }

    @Override
    public boolean available() {
        return api() != null;
    }

    @Override
    public int level(UUID player, String skillId) {
        ChrdkSkillsApi found = api();
        Player online = player == null ? null : Bukkit.getPlayer(player);
        if (found == null || online == null || skillId == null || skillId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, found.level(online, skillId));
        } catch (RuntimeException | LinkageError error) {
            return 0;
        }
    }

    @Override
    public int totalLevel(UUID player) {
        ChrdkSkillsApi found = api();
        Player online = player == null ? null : Bukkit.getPlayer(player);
        if (found == null || online == null) {
            return 0;
        }
        try {
            return Math.max(0, found.totalLevel(online));
        } catch (RuntimeException | LinkageError error) {
            return 0;
        }
    }
}
