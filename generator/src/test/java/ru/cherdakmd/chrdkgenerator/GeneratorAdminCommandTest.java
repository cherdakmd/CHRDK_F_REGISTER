package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorAdminCommandTest {
    @Test void recoveryAndDiagnosticRoutesAreExplicitAndDoNotExposeManualResolution() {
        assertTrue(GeneratorAdminCommand.paymentCommand(new String[]{"finance"}));
        assertTrue(GeneratorAdminCommand.paymentCommand(new String[]{"RECOVER"}));
        assertFalse(GeneratorAdminCommand.paymentCommand(new String[]{"reload"}));
        assertFalse(GeneratorAdminCommand.paymentCommand(new String[]{"resolve", "id", "refund", "confirm"}));
        assertFalse(GeneratorAdminCommand.paymentCommand(new String[]{"recover", "extra"}));
        assertFalse(GeneratorAdminCommand.paymentCommand(new String[]{}));
        assertFalse(GeneratorAdminCommand.paymentCommand(null));
    }
}
