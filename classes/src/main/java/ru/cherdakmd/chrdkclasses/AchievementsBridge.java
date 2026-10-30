package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkachievements.ChrdkAchievementsApi;

import java.util.UUID;

/**
 * Чтение открытых достижений через публичный API {@code CHRDK_Achievements}
 * (1.4.175).
 *
 * <p>Устроено как {@link SkillsBridge}: сервис ищется один раз (и после
 * {@code /classadmin reload} — заново), ошибка {@link LinkageError} ловится
 * отдельно, поэтому сервер без достижений или с несовместимой версией аддона
 * спокойно работает без бонусных очков.</p>
 *
 * <p>API достижений принимает {@link Player}, поэтому счётчик читается только у
 * онлайн-игрока. Для оффлайн-карточек бонус равен нулю — очков всё равно не
 * потратить, а при входе число подтянется само.</p>
 */
final class AchievementsBridge implements AchievementSource {

    private final ClassesPlugin plugin;
    private ChrdkAchievementsApi api;
    private boolean lookedUp;

    AchievementsBridge(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /** API достижений или {@code null}; повторный поиск — только после reload. */
    ChrdkAchievementsApi api() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                api = Bukkit.getServicesManager().load(ChrdkAchievementsApi.class);
            } catch (RuntimeException | LinkageError error) {
                api = null;
                plugin.getLogger().warning("[CHRDK_Classes] API достижений недоступен: "
                        + error.getMessage());
            }
        }
        return api;
    }

    /** Забывает найденный сервис: после reload достижения могли включиться. */
    void reset() {
        api = null;
        lookedUp = false;
    }

    @Override
    public boolean available() {
        return api() != null;
    }

    @Override
    public int unlockedCount(UUID player) {
        ChrdkAchievementsApi found = api();
        Player online = player == null ? null : Bukkit.getPlayer(player);
        if (found == null || online == null) {
            return 0;
        }
        try {
            return Math.max(0, found.unlockedCount(online));
        } catch (RuntimeException error) {
            // Достижения могли выключиться на ходу — бонус просто не считается.
            plugin.getLogger().warning("[CHRDK_Classes] не удалось прочитать достижения: "
                    + error.getMessage());
            return 0;
        }
    }
    @Override
    public ClassDataRead snapshotCount(UUID player) {
        Player online = player == null ? null : Bukkit.getPlayer(player);
        if (online == null || !online.isOnline()) {
            return ClassDataRead.absent(ClassDataRead.Status.OFFLINE);
        }
        return ClassDataRead.read(() -> api() != null, () -> {
            var found = api();
            return found.unlockedCount(online);
        });
    }
}
