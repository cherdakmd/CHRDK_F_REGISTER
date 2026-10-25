package ru.cherdakmd.chrdkregister.storage;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Запись файлов состояния: временный файл рядом, атомарная замена, один
 * писатель на файл и понятный лог, если диск не принял данные.
 *
 * <p>Зачем это нужно:</p>
 * <ul>
 *   <li>запись из двух потоков (таймер автосохранения и выключение плагина)
 *       больше не делят один {@code .tmp} — файл защищён блокировкой на путь;</li>
 *   <li>после сбоя попытка повторяется: разовый «залип» диска на сотые доли
 *       секунды не оставляет состояние несохранённым;</li>
 *   <li>в логе видна настоящая причина — класс исключения, сообщение и errno
 *       файловой системы, — а не бесполезная строка {@code a.json.tmp -> a.json};</li>
 *   <li>лежащий диск не заливает консоль одинаковыми ошибками каждые пять
 *       секунд: на файл не больше одного сообщения в минуту, повторы считаются
 *       и печатаются числом;</li>
 *   <li>когда запись снова проходит, в лог уходит одна строка «запись
 *       восстановлена», чтобы админ видел конец аварии.</li>
 * </ul>
 *
 * <p>Класс живёт в ядре и используется аддонами, поэтому словарь блокировок
 * общий: даже два плагина с одним файлом не испортят его. Методы не трогают
 * Bukkit — поведение покрыто юнит-тестами.</p>
 */
public final class SafeJsonWriter {

    /** Сколько раз пробуем записать, прежде чем сдаться до следующего цикла. */
    public static final int ATTEMPTS = 3;

    /** Пауза перед повтором; растёт линейно: 50 мс, 100 мс. */
    static final long RETRY_DELAY_MILLIS = 50L;

    /** Не чаще одного сообщения об ошибке на файл за этот интервал. */
    public static final long REPORT_INTERVAL_MILLIS = 60_000L;

    /** Суффикс временного файла: имя состояния + суффикс. */
    public static final String TEMP_SUFFIX = ".tmp";

    private static final Map<Path, Slot> SLOTS = new ConcurrentHashMap<>();

    private SafeJsonWriter() {
    }

    /**
     * Записывает текст в файл, если это возможно прямо сейчас.
     *
     * <p>Вызывать можно из любого потока: записи в один и тот же файл
     * выстраиваются в очередь, а папка создаётся сама.</p>
     *
     * @param file    куда писать; {@code null} — писать некуда
     * @param content готовый JSON
     * @param logger  лог плагина; {@code null} — не логировать
     * @param tag     префикс сообщений, например {@code [CHRDK_Claims]}
     * @return {@code true} — файл на диске; {@code false} — не вышло, данные
     *         остались в памяти и будут записаны следующим сохранением
     */
    public static boolean write(Path file, String content, Logger logger, String tag) {
        if (file == null || content == null) {
            return false;
        }
        Path target = file.toAbsolutePath().normalize();
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        Slot slot = SLOTS.computeIfAbsent(target, key -> new Slot());
        slot.lock.lock();
        try {
            IOException failure = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                        writer.write(content);
                    }
                    move(temp, target);
                    reportRecovered(slot, target, logger, tag);
                    return true;
                } catch (IOException exception) {
                    failure = exception;
                    if (attempt < ATTEMPTS && !pause(attempt)) {
                        break;
                    }
                }
            }
            deleteQuietly(temp);
            reportFailed(slot, target, logger, tag, failure);
            return false;
        } finally {
            slot.lock.unlock();
        }
    }

    /** Забывает счётчики сообщений — нужно тестам и ручному сбросу. */
    public static void resetReports() {
        SLOTS.values().forEach(slot -> {
            slot.lock.lock();
            try {
                slot.lastReportAt = 0L;
                slot.suppressed = 0;
                slot.failing = false;
            } finally {
                slot.lock.unlock();
            }
        });
    }

    /** Сколько файлов класс знает прямо сейчас (диагностика и тесты). */
    public static int tracked() {
        return SLOTS.size();
    }

    /** Замена файла: сначала атомарная, при неподдерживающей ФС — обычная. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Пауза перед повтором; {@code false} — поток прервали, повторять нельзя. */
    private static boolean pause(int attempt) {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS * attempt);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // мусорный .tmp безвреден: следующая запись его перезапишет
        }
    }

    /** Пишет ошибку не чаще раза в минуту; повторы считаются. Вызывать под блокировкой. */
    private static void reportFailed(Slot slot, Path target, Logger logger, String tag,
                                     IOException failure) {
        slot.failing = true;
        if (logger == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean fresh = slot.lastReportAt == 0L
                || now < slot.lastReportAt
                || now - slot.lastReportAt >= REPORT_INTERVAL_MILLIS;
        if (!fresh) {
            slot.suppressed++;
            return;
        }
        String suppressed = slot.suppressed > 0
                ? " (подавлено повторов: " + slot.suppressed + ")"
                : "";
        slot.lastReportAt = now;
        slot.suppressed = 0;
        logger.severe(tag + " Не удалось записать " + target + " за " + ATTEMPTS
                + " попытки: " + describe(failure) + suppressed
                + ". Данные остались в памяти — запишем при следующем сохранении."
                + " Проверьте свободное место на диске и права на папку.");
    }

    /** После аварии — одна строка о том, что файл снова пишется. */
    private static void reportRecovered(Slot slot, Path target, Logger logger, String tag) {
        if (!slot.failing) {
            slot.lastReportAt = 0L;
            slot.suppressed = 0;
            return;
        }
        int suppressed = slot.suppressed;
        slot.failing = false;
        slot.lastReportAt = 0L;
        slot.suppressed = 0;
        if (logger != null) {
            logger.info(tag + " Запись " + target + " снова работает"
                    + (suppressed > 0 ? " (после " + suppressed + " неудачных попыток)." : "."));
        }
    }

    /** Человеческое описание ошибки: класс, сообщение и причина от файловой системы. */
    static String describe(Throwable failure) {
        if (failure == null) {
            return "неизвестная ошибка";
        }
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            text.append(": ").append(message.trim());
        }
        if (failure instanceof FileSystemException system) {
            String reason = system.getReason();
            if (reason != null && !reason.isBlank()) {
                text.append(" · причина: ").append(reason.trim());
            } else {
                text.append(" · файловая система не назвала причину (сбой ввода-вывода)");
            }
        }
        return text.toString();
    }

    /** Блокировка и счётчики одного файла. Поля меняются только под блокировкой. */
    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private long lastReportAt;
        private int suppressed;
        private boolean failing;
    }
}
