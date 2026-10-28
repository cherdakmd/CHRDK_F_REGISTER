package ru.cherdakmd.chrdkregister.api;

import com.google.gson.JsonElement;

/** Финансовые ledger-поля целые: Gson не должен молча усекать дробь/переполнение до long. */
public final class FinanceNumbers {
    public static void requireIntegral(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) element.getAsJsonObject().entrySet().forEach(row -> requireIntegral(row.getValue()));
        else if (element.isJsonArray()) element.getAsJsonArray().forEach(FinanceNumbers::requireIntegral);
        else if (element.getAsJsonPrimitive().isNumber()) {
            try { element.getAsBigDecimal().longValueExact(); }
            catch (ArithmeticException | NumberFormatException error) { throw new IllegalArgumentException("Дробное/переполненное значение финансового ledger", error); }
        }
    }
    private FinanceNumbers() { }
}
