package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/** Внутренний read-only capability Core для безопасной сверки market lineage. */
public interface ShopLineageProvider {
    /** Уже закреплённый market; null — источник отсутствует или банк недоступен. */
    UUID shopLineage();
    /** 0 — сделок нет; -1 — другой market, банк недоступен или есть банковский pending. */
    long shopSequence(UUID market);
}
