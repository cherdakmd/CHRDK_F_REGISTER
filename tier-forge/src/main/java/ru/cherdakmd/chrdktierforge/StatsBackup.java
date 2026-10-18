package ru.cherdakmd.chrdktierforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Ротация резервных копий {@code stats.yml} перед перезаписью (1.4.95).
 *
 * <p>Сейв атомарный (временный файл + {@code ATOMIC_MOVE}), но если сам файл
 * окажется битым или его подменит другой процесс, история находок теряется.
 * Перед заменой текущий `stats.yml` сдвигается в `stats.yml.bak1`, предыдущие
 * копии — на шаг глубже; самая старая удаляется.
 *
 * <p>Работает с обычными файлами, без Bukkit — покрыто {@link StatsBackupTest}.
 */
final class StatsBackup {

    /** Верхняя граница числа копий: больше десяти держать бессмысленно. */
    static final int MAX_COPIES = 10;

    private final Path target;
    private final int keep;

    StatsBackup(Path target, int keep) {
        this.target = target;
        this.keep = clampKeep(keep);
    }

    /** 0 — резервные копии выключены, иначе 1..{@link #MAX_COPIES}. */
    static int clampKeep(int keep) {
        return Math.max(0, Math.min(MAX_COPIES, keep));
    }

    /** Имена копий от свежей к старой: {@code stats.yml.bak1 … .bakN}. */
    List<Path> backups() {
        List<Path> names = new ArrayList<>(keep);
        for (int i = 1; i <= keep; i++) {
            names.add(target.resolveSibling(target.getFileName() + ".bak" + i));
        }
        return names;
    }

    /**
     * Сдвинуть существующие копии и сохранить текущий файл как `.bak1`.
     * Если файла ещё нет (первый запуск) — просто ничего не делает.
     */
    void rotate() throws IOException {
        if (keep <= 0 || !Files.isRegularFile(target)) {
            return;
        }
        List<Path> backups = backups();
        Files.deleteIfExists(backups.get(backups.size() - 1)); // самая старая уходит
        for (int i = backups.size() - 2; i >= 0; i--) {
            Path from = backups.get(i);
            if (Files.isRegularFile(from)) {
                Files.move(from, backups.get(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.copy(target, backups.get(0), StandardCopyOption.REPLACE_EXISTING);
    }
}
