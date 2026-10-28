package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;
class FinanceNumbersTest {
    @Test void rejectsFractionAndOverflowBeforeGsonCanTruncateLong() {
        for (String json:new String[]{"{\"amount\":1.25}","[9223372036854775808]","{\"nested\":[{\"amount\":-0.5}]}"})
            assertThrows(IllegalArgumentException.class,()->FinanceNumbers.requireIntegral(JsonParser.parseString(json)));
    }
    @Test void acceptsWholeNumbersIncludingExactDecimalNotationAndLongBoundary() {
        assertDoesNotThrow(()->FinanceNumbers.requireIntegral(JsonParser.parseString("{\"amount\":1.0,\"other\":[null,\"1.5\",9223372036854775807]}")));
    }
}
