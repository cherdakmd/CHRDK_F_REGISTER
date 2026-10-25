package ru.cherdakmd.chrdkregister.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Запись состояния аддонов (1.4.172): файл на диске, никаких {@code .tmp}
 * после записи, один писатель на файл и понятный лог при сбое диска.
 */
class SafeJsonWriterTest {

    private static final String TAG = "[CHRDK_Test]";

    @TempDir
    Path folder;

    private CapturingHandler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        SafeJsonWriter.resetReports();
        handler = new CapturingHandler();
        logger = Logger.getLogger("safe-json-writer-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @Test
    void writePutsFileAndLeavesNoTemp() throws IOException {
        Path file = folder.resolve("nested/data.json");

        assertTrue(SafeJsonWriter.write(file, "{\"ok\":true}", logger, TAG));

        assertEquals("{\"ok\":true}", Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + SafeJsonWriter.TEMP_SUFFIX)));
        assertTrue(handler.messages().isEmpty(), "успешная запись не должна шуметь в лог");
    }

    @Test
    void failedWriteReportsCauseAndDoesNotThrow() throws IOException {
        // Родитель — обычный файл: создать папку невозможно, запись обязана сдаться.
        Path blocker = folder.resolve("blocked");
        Files.writeString(blocker);
        Path file = blocker.resolve("state.json");

        assertFalse(SafeJsonWriter.write(file, "{}", logger, TAG));

        List<String> messages = handler.messages();
        assertEquals(1, messages.size(), "три попытки — одно сообщение");
        assertTrue(messages.get(0).contains("Не удалось записать"), messages.get(0));
        assertTrue(messages.get(0).contains("FileAlreadyExistsException"), messages.get(0));
        assertTrue(messages.get(0).contains("Данные остались в памяти"), messages.get(0));
    }

    @Test
    void repeatedFailuresAreThrottledAndCounted() throws IOException {
        Path blocker = folder.resolve("blocked");
        Files.writeString(blocker);
        Path file = blocker.resolve("state.json");

        assertFalse(SafeJsonWriter.write(file, "{}", logger, TAG));
        assertFalse(SafeJsonWriter.write(file, "{}", logger, TAG));
        assertFalse(SafeJsonWriter.write(file, "{}", logger, TAG));

        assertEquals(1, handler.messages().size(), "в минуту — одно сообщение на файл");
    }

    @Test
    void recoveryIsReportedOnce() throws IOException {
        Path folderTarget = folder.resolve("data");
        Files.writeString(folderTarget); // папка занята файлом — запись сломана
        Path file = folderTarget.resolve("state.json");
        assertFalse(SafeJsonWriter.write(file, "{\"a\":1}", logger, TAG));

        Files.delete(folderTarget); // «диск починили»
        assertTrue(SafeJsonWriter.write(file, "{\"a\":2}", logger, TAG));
        assertEquals("{\"a\":2}", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(handler.messages().get(handler.messages().size() - 1)
                .contains("снова работает"), handler.messages().toString());
    }

    @Test
    void describeNamesFilesystemReason() {
        String silent = SafeJsonWriter.describe(
                new FileSystemException("state.json.tmp", "state.json", null));
        assertTrue(silent.contains("FileSystemException"), silent);
        assertTrue(silent.contains("state.json.tmp -> state.json"), silent);
        assertTrue(silent.contains("не назвала причину"), silent);

        String explicit = SafeJsonWriter.describe(
                new FileSystemException("state.json.tmp", "state.json", "No space left on device"));
        assertTrue(explicit.contains("причина: No space left on device"), explicit);
    }

    @Test
    void concurrentWritersNeverMixContent() throws Exception {
        Path file = folder.resolve("state.json");
        String first = "{\"who\":\"first\"}";
        String second = "{\"who\":\"second\"}";
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String payload = index % 2 == 0 ? first : second;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 40; round++) {
                        SafeJsonWriter.write(file, payload, logger, TAG);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000L);
        }

        assertNull(failure.get());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.equals(first) || written.equals(second),
                "файл не должен быть смесью двух записей: " + written);
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + SafeJsonWriter.TEMP_SUFFIX)));
    }

    @Test
    void nullArgumentsAreRejectedQuietly() {
        assertFalse(SafeJsonWriter.write(null, "{}", logger, TAG));
        assertFalse(SafeJsonWriter.write(folder.resolve("state.json"), null, logger, TAG));
        assertTrue(handler.messages().isEmpty());
    }

    /** Копит текст сообщений, чтобы проверять их без консоли. */
    private static final class CapturingHandler extends Handler {

        private final List<String> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                records.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
            // нечего сбрасывать
        }

        @Override
        public void close() {
            // нечего закрывать
        }

        synchronized List<String> messages() {
            return new ArrayList<>(records);
        }
    }
}
