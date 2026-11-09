package ru.cherdakmd.chrdkregister.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Общие правила для человекочитаемых файлов данных игроков в папке {@code players/}.
 *
 * <p>Эти YAML-файлы администратор может править вручную, поэтому у них нет
 * встроенной контрольной суммы: иначе любая правка выглядела бы как подделка.
 * Зато сохраняются атомарная запись через {@link SafeJsonWriter}, обязательный
 * backup перед миграцией из старых JSON и карантин {@code .broken} для файла,
 * который не удалось разобрать. Глобальные JSON с настройками по-прежнему
 * защищены SHA-256 — ручная правка данных игроков не ослабляет их.</p>
 */
public final class PlayerDataFiles {

    /** Имя папки с редактируемыми данными игроков внутри dataFolder плагина. */
    public static final String FOLDER = "players";

    private PlayerDataFiles() {
    }

    public static Path folder(Path dataFolder) {
        return dataFolder.resolve(FOLDER);
    }

    /**
     * Копирует исходный JSON перед тем, как вынести из него данные игроков.
     * Без успешного backup миграция не перезаписывает оригинал.
     */
    public static Path backup(Path source, String label, Logger logger, String tag) throws IOException {
        String suffix = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path backup = source.resolveSibling(source.getFileName() + ".before-" + label + "-" + suffix + ".bak");
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (logger != null) {
            logger.info(tag + " Создан backup перед выносом данных игроков: " + backup.getFileName());
        }
        return backup;
    }

    /** Атомарная запись UTF-8 текста (JSON или YAML) рядом с основным файлом. */
    public static boolean write(Path file, String text, Logger logger, String tag) {
        return SafeJsonWriter.write(file, text, logger, tag);
    }

    /** Не даёт следующему save() затереть подозрительный YAML без следа. */
    public static void preserveBroken(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Оригинал остаётся на диске; загрузка всё равно откажется от него.
        }
    }

    /** Один раз создаёт короткую инструкцию по ручной правке в папке players/. */
    public static void writeReadme(Path dataFolder, String content, Logger logger, String tag) {
        Path readme = folder(dataFolder).resolve("README.md");
        if (Files.isRegularFile(readme)) return;
        write(readme, content, logger, tag);
    }
}
