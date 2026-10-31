package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/** Главный поток: долговечный личный долг, резервируемый перед любым банковским расходом.
 * Провайдер не меняет банк внутри запроса. Отрицательное значение означает неизвестный исход. */
@FunctionalInterface
public interface PersonalDebtIndex {
    long reserved(UUID owner);
}
