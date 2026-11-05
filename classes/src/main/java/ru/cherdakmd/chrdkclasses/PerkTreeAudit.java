package ru.cherdakmd.chrdkclasses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Журнал операций с деревьями перков: {@code unlock}, {@code select},
 * {@code reset}. Финансоподобные операции (сброс с возвратом пыли и токенов)
 * обязаны оставлять след, который можно сверить после рестарта.
 *
 * <p>Формат — JSONL в {@code audit/perk-trees.jsonl}, одна строка на действие.
 * Запись через {@code APPEND}, без переписывания истории. Ошибка ввода-вывода
 * не роняет игровой поток: она логируется, а операция деревьев продолжается.</p>
 *
 * <p>Потолок строк считается от <b>уже существующего</b> файла, иначе каждый
 * рестарт начинался бы с нуля и журнал рос бы бесконечно. Если прочитать файл
 * не удалось, запись закрывается (fail-closed) до вмешательства
 * администратора.</p>
 */
final class PerkTreeAudit {

    static final int MAX_LINES = 200_000;
    private static final String FILE = "perk-trees.jsonl";

    private final Path file;
    private final Logger logger;
    private int lines;

    PerkTreeAudit(Path folder, Logger logger) {
        this.file = folder.resolve("audit").resolve(FILE);
        this.logger = logger;
        this.lines = countLines();
    }

    private int countLines() {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            return (int) Math.min(MAX_LINES, stream.count());
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.warning("Аудит деревьев перков не прочитан, запись закрыта: "
                        + error.getMessage());
            }
            return MAX_LINES;
        }
    }

    synchronized void record(String action, UUID player, String domainId,
                             long refundPowder, int refundTokens, String reason) {
        if (lines >= MAX_LINES) {
            return;
        }
        String line = "{\"at\":" + System.currentTimeMillis()
                + ",\"action\":\"" + escape(action)
                + "\",\"player\":\"" + escape(String.valueOf(player))
                + "\",\"domain\":\"" + escape(domainId == null ? "" : domainId)
                + "\",\"refundPowder\":" + Math.max(0L, refundPowder)
                + ",\"refundTokens\":" + Math.max(0, refundTokens)
                + ",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lines++;
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.warning("Не удалось записать аудит деревьев перков: " + error.getMessage());
            }
        }
    }

    synchronized int lines() {
        return lines;
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
