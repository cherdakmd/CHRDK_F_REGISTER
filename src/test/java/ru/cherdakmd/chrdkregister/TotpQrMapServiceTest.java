package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.qrcode.QrCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QR-код привязки 2FA на карте (1.4.3): кодирование otpauth:// URI минимальной
 * матрицей (ECC LOW) и подбор масштаба модуля для холста 128×128.
 */
class TotpQrMapServiceTest {

    private static String sampleUri() {
        return TotpService.buildOtpAuthUri("CHRDK", "Steve", "JBSWY3DPEHPK3PXP");
    }

    @Test
    void encodeProducesCompactDeterministicQr() {
        QrCode first = TotpQrMapService.encode(sampleUri());
        assertNotNull(first, "типовой otpauth:// URI должен помещаться в QR");
        assertTrue(first.size >= 21 && first.size <= 41,
                "URI ~70 символов при ECC LOW кодируется компактно (QR v1–v5), size=" + first.size);
        assertEquals(QrCode.Ecc.LOW, first.errorCorrectionLevel);

        QrCode second = TotpQrMapService.encode(sampleUri());
        assertNotNull(second);
        assertEquals(first.size, second.size, "кодирование детерминировано");
        for (int x = 0; x < first.size; x++) {
            for (int y = 0; y < first.size; y++) {
                assertEquals(first.getModule(x, y), second.getModule(x, y),
                        "модуль (" + x + "," + y + ") различается между кодированиями");
            }
        }
    }

    @Test
    void encodedQrHasFinderPatternsInCorners() {
        QrCode qr = TotpQrMapService.encode(sampleUri());
        assertNotNull(qr);
        // Поисковые узоры (finder patterns) — тёмные квадраты в трёх углах матрицы
        assertTrue(qr.getModule(0, 0), "левый верхний угол");
        assertTrue(qr.getModule(3, 3), "центр левого верхнего узора");
        assertTrue(qr.getModule(qr.size - 1, 0), "правый верхний угол");
        assertTrue(qr.getModule(0, qr.size - 1), "левый нижний угол");
    }

    @Test
    void longUrisStillEncode() {
        String longName = "VeryLongPlayerName16";
        String uri = TotpService.buildOtpAuthUri("MySuperServer", longName,
                "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        assertNotNull(TotpQrMapService.encode(uri), "длинный ник и issuer не должны ломать QR");
    }

    @Test
    void chooseScaleKeepsQuietZone() {
        assertEquals(4, TotpQrMapService.chooseScale(21), "QR v1 помещается в 16 модулей масштаба 4");
        assertEquals(3, TotpQrMapService.chooseScale(33), "QR v4 — масштаб 3");
        assertEquals(3, TotpQrMapService.chooseScale(37), "QR v5 — масштаб 3");
        assertEquals(2, TotpQrMapService.chooseScale(41), "QR v6 — масштаб 2");
        assertEquals(2, TotpQrMapService.chooseScale(45));
        assertEquals(1, TotpQrMapService.chooseScale(61), "крупные QR — минимум масштаб 1");
    }
}
