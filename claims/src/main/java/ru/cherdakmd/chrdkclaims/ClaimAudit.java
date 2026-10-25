package ru.cherdakmd.chrdkclaims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Журнал операций с приватами ({@code claims-audit.log}): кто, что и когда сделал
 * с деньгами и территорией. Нужен для разбора спорных ситуаций («куда ушла казна»).
 *
 * <p>Файл дописывается построчно в формате
 * {@code 2026-10-18 12:34:56 | действие | подробности}. При превышении размера
 * {@link #MAX_BYTES} он откладывается как {@code claims-audit.log.1} (предыдущая
 * ротация перезаписывается), поэтому журнал не растёт бесконечно.</p>
 */
public final class ClaimAudit {

    /** Порог ротации: 2 МиБ — примерно 20 тысяч строк. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Сколько строк отдаёт хвост журнала по умолчанию. */
    public static final int DEFAULT_TAIL = 15;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final Path rotated;
    private final Logger logger;
    private final long maxBytes;

    public ClaimAudit(Path dataFolder, Logger logger) {
        this(dataFolder, logger, MAX_BYTES);
    }

    public ClaimAudit(Path dataFolder, Logger logger, long maxBytes) {
        this.file = dataFolder.resolve("claims-audit.log");
        this.rotated = dataFolder.resolve("claims-audit.log.1");
        this.logger = logger;
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /** Записывает событие; ошибки диска не должны ломать игровую логику. */
    public void write(String action, String details) {
        String line = LocalDateTime.now().format(STAMP)
                + " | " + clean(action)
                + " | " + clean(details) + System.lineSeparator();
        synchronized (this) {
            try {
                Files.createDirectories(file.getParent());
                rotateIfNeeded();
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException exception) {
                logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось записать claims-audit.log: "
                        + exception.getMessage(), exception);
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < maxBytes) {
            return;
        }
        Files.move(file, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Последние {@code lines} строк журнала (новые в конце); пустой список, если файла нет. */
    public List<String> tail(int lines) {
        int limit = Math.max(1, lines);
        synchronized (this) {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            try {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (all.size() <= limit) {
                    return List.copyOf(all);
                }
                return new ArrayList<>(all.subList(all.size() - limit, all.size()));
            } catch (IOException exception) {
                logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось прочитать claims-audit.log: "
                        + exception.getMessage(), exception);
                return List.of();
            }
        }
    }

    /** Значения из ников/миров не должны ломать построчный формат. */
    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }
}
