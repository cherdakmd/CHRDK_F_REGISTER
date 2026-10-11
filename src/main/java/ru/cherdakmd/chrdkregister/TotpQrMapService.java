package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.qrcode.QrCode;
import ru.cherdakmd.chrdkregister.qrcode.QrSegment;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * QR-код привязки TOTP 2FA на игровой карте (пункт из docs/IMPROVEMENTS.md, Приложение B).
 *
 * <p>Используется одна общая {@link MapView} с контекстным рендерером: каждый игрок видит
 * на своей карте собственный QR с URI {@code otpauth://totp/...}, который строится из
 * ожидающего подтверждения секрета ({@link AccessManager#getPendingTotpSecret}),
 * поэтому утечка чужой карты ничего не раскрывает. Когда настройка 2FA завершена или
 * отменена (секрета больше нет), карта показывает «закрытую» сетку — секрет нельзя
 * отсканировать повторно.</p>
 *
 * <p>QR генерируется встроенной библиотекой (package {@code qrcode}, MIT, Project Nayuki)
 * без внешних зависимостей; избыточность — ECC LOW ради минимального размера матрицы
 * (модуль получается 2–3 пикселя на карте 128×128 — удобнее сканировать с экрана).</p>
 */
public final class TotpQrMapService {

    private static final int CANVAS_SIZE = 128;
    /** Минимальная белая рамка вокруг QR в модулях (спецификация просит 4, экрану карты хватает 2). */
    private static final int MIN_QUIET_MODULES = 2;
    private static final int MAX_SCALE = 4;

    private final AccessManager accessManager;
    private final Logger logger;
    private volatile MapView mapView;

    public TotpQrMapService(AccessManager accessManager, Logger logger) {
        this.accessManager = accessManager;
        this.logger = logger;
    }

    /**
     * Создаёт выданную игроку карту с QR-кодом его привязки 2FA. MapView общий и
     * лениво создаётся один раз (в первом мире, где её попросили).
     */
    public ItemStack createMapItem(World world) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        if (!(item.getItemMeta() instanceof MapMeta meta)) {
            logger.warning("MapMeta недоступен — карта QR-кода 2FA не выдана");
            return item;
        }
        meta.setMapView(ensureMapView(world));
        item.setItemMeta(meta);
        return item;
    }

    private MapView ensureMapView(World world) {
        MapView view = mapView;
        if (view != null) return view;
        synchronized (this) {
            if (mapView == null) {
                MapView created = Bukkit.createMap(world);
                created.getRenderers().forEach(created::removeRenderer);
                created.addRenderer(new TotpQrRenderer());
                mapView = created;
            }
            return mapView;
        }
    }

    /** Рисует QR на холст карты для конкретного смотрящего игрока. */
    void renderFor(MapCanvas canvas, Player player) {
        fill(canvas, Color.WHITE);
        Optional<String> pendingSecret = accessManager.getPendingTotpSecret(player.getUniqueId());
        if (pendingSecret.isEmpty()) {
            drawClosedGrid(canvas);
            return;
        }
        String otpAuthUri = TotpService.buildOtpAuthUri(
                accessManager.config().totpIssuer(), player.getName(), pendingSecret.get());
        QrCode qr = encode(otpAuthUri);
        if (qr == null) {
            drawClosedGrid(canvas);
            return;
        }
        int scale = chooseScale(qr.size);
        int offset = (CANVAS_SIZE - qr.size * scale) / 2;
        for (int moduleY = 0; moduleY < qr.size; moduleY++) {
            for (int moduleX = 0; moduleX < qr.size; moduleX++) {
                if (!qr.getModule(moduleX, moduleY)) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        canvas.setPixelColor(offset + moduleX * scale + dx,
                                offset + moduleY * scale + dy, Color.BLACK);
                    }
                }
            }
        }
    }

    /** Кодирует строку в минимально подходящий QR (byte mode, ECC LOW, без повышения ECC). */
    static QrCode encode(String text) {
        try {
            QrSegment segment = QrSegment.makeBytes(text.getBytes(StandardCharsets.UTF_8));
            return QrCode.encodeSegments(List.of(segment), QrCode.Ecc.LOW,
                    QrCode.MIN_VERSION, QrCode.MAX_VERSION, -1, false);
        } catch (IllegalArgumentException tooLong) {
            return null; // otpauth:// URI не превышает лимит QR (~3 КБ), но страхуемся
        }
    }

    /** Подбирает максимальный целый масштаб модуля, сохраняя белую рамку от {@link #MIN_QUIET_MODULES}. */
    static int chooseScale(int qrSize) {
        int scale = 1;
        while (scale < MAX_SCALE && (long) (qrSize + MIN_QUIET_MODULES * 2) * (scale + 1) <= CANVAS_SIZE) {
            scale++;
        }
        return scale;
    }

    /** Видимая «закрытая» сетка вместо QR: настройка не активна — сканировать нечего. */
    private static void drawClosedGrid(MapCanvas canvas) {
        int cells = 8;
        int scale = CANVAS_SIZE / (cells * 2 + 2);
        int offset = (CANVAS_SIZE - (cells * 2 + 1) * scale) / 2;
        for (int y = 0; y < cells * 2 + 1; y++) {
            for (int x = 0; x < cells * 2 + 1; x++) {
                if ((x + y) % 2 == 0) continue;
                drawModule(canvas, offset + x * scale, offset + y * scale, scale, Color.LIGHT_GRAY);
            }
        }
    }

    private static void drawModule(MapCanvas canvas, int px, int py, int scale, Color color) {
        for (int dy = 0; dy < scale; dy++) {
            for (int dx = 0; dx < scale; dx++) {
                canvas.setPixelColor(px + dx, py + dy, color);
            }
        }
    }

    private static void fill(MapCanvas canvas, Color color) {
        for (int x = 0; x < CANVAS_SIZE; x++) {
            for (int y = 0; y < CANVAS_SIZE; y++) {
                canvas.setPixelColor(x, y, color);
            }
        }
    }

    /** Контекстный рендерер: у каждого игрока — своя картинка от его ожидающего секрета. */
    private final class TotpQrRenderer extends MapRenderer {
        TotpQrRenderer() {
            super(true); // contextual: render() вызывается отдельно для каждого смотрящего
        }

        @Override
        public void render(@NotNull MapView view, @NotNull MapCanvas canvas, @NotNull Player player) {
            renderFor(canvas, player);
        }

        /** Обязательный метод Purpur Explorer Map API: наша карта — не карта сокровищ. */
        @Override
        public boolean isExplorerMap() {
            return false;
        }
    }
}
