package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/**
 * Внутренний capability реализации Core: читает lineage, уже закреплённый
 * банком за источником накопительных доходов. Не расширяет stable
 * {@link ChrdkRegisterApi} и не требует bump {@code API_VERSION}; аддоны,
 * скомпилированные со старым контрактом, продолжают работать через default
 * методы. Возвращает {@code null}, если источник не зарегистрирован или банк
 * недоступен.
 */
public interface IncomeSourceLineageProvider {
    UUID incomeSourceLineage(String source);
}
