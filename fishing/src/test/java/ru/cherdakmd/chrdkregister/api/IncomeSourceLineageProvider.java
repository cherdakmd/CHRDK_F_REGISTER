package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/** Test-only stand-in for the Core runtime capability, never packaged in Fishing. */
public interface IncomeSourceLineageProvider {
    UUID incomeSourceLineage(String source);
}
