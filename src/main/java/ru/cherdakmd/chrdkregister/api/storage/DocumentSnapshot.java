package ru.cherdakmd.chrdkregister.api.storage;

/**
 * Неизменяемый снимок документа аддона. {@code checksum} — SHA-256 payload,
 * {@code revision} используется для compare-and-set.
 */
public record DocumentSnapshot(
        String namespace,
        String key,
        long revision,
        String payload,
        String checksum,
        long updatedAtMillis
) {
}
