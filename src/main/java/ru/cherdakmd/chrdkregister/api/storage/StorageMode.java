package ru.cherdakmd.chrdkregister.api.storage;

/** Режим хранения, выбранный в {@code plugins/CHRDK_Core/database.yml}. */
public enum StorageMode {
    /** Легаси: JSON/YAML на диске, запись разрешена. */
    FILE,
    /** Рабочий режим: единственный источник истины — MySQL. */
    MYSQL,
    /** Только чтение замороженных файлов для сверки мигратором; gameplay не стартует. */
    FILE_READONLY
}
