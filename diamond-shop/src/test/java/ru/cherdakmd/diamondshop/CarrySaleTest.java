package ru.cherdakmd.diamondshop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CarrySaleTest {
    @Test void allCheapPiecesIncludingLastUnpaidPieceAreSelected() {
        assertEquals(1,CarrySale.maximum("0/1",64,1,1,0));
        assertEquals(63,CarrySale.maximum("0/1",64,1,63,0));
        assertEquals(65,CarrySale.maximum("0/1",64,1,65,1));
        assertEquals(2304,CarrySale.maximum("0/1",64,1,2304,100));
    }
    @Test void capAndPriorRemainderNeverDiscardUnaffordableItems() {
        assertEquals(0,CarrySale.maximum("63/64",64,1,64,0));
        assertEquals(64,CarrySale.maximum("63/64",64,1,64,1));
        assertEquals(0,CarrySale.maximum(null,64,1,64,1));
        assertEquals(0,CarrySale.maximum("0/1",64,0,64,100));
    }
}
