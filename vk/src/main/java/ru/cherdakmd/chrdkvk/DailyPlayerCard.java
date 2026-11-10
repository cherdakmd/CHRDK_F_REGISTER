package ru.cherdakmd.chrdkvk;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Персональная карточка номинации дня: тот же фон, градиент, шрифты и подвал,
 * что у {@link WallDigestCard}, но с ником игрока и его результатом.
 *
 * <p>Рисуется Java2D на сервере, без Python/Pillow и без GitHub Actions.
 * Приватные данные (UUID, IP, координаты, суммы платежей) не попадают ни на
 * картинку, ни в текст записи.</p>
 */
public final class DailyPlayerCard {

    private static final int WIDTH = WallDigestCard.WIDTH;
    private static final int HEIGHT = WallDigestCard.HEIGHT;
    private static final Color MUTED = new Color(0xd8, 0xda, 0xc4);
    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    private DailyPlayerCard() {
    }

    /** Короткий текст записи на стене; маркер нужен для ручной сверки дедупа. */
    public static String message(DailyNomination nomination, DailyCardsConfig config,
                                 String day, String marker) {
        String date = humanDate(day);
        StringBuilder out = new StringBuilder();
        out.append("CHERDAK · ").append(nomination.kind().title())
                .append(" · ").append(date).append("\n\n");
        out.append("★ ").append(nomination.playerName()).append('\n');
        String value = nomination.valueLine(config.showValues());
        if (!value.isEmpty()) {
            out.append(value).append('\n');
        }
        out.append('\n').append(config.footer());
        if (marker != null && !marker.isBlank()) {
            out.append('\n').append(marker);
        }
        return out.toString();
    }

    /** PNG 1280×720 в памяти; загрузкой занимается {@link VkWallPublisher}. */
    public static byte[] render(DailyNomination nomination, DailyCardsConfig config, String day)
            throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            WallDigestCard.drawBackground(g);
            WallDigestCard.drawGradient(g);

            Font logoFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 29f);
            Font badgeFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 21f);
            Font nickFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 68f);
            Font valueFont = WallDigestCard.font("/assets/DejaVuSans.ttf", Font.PLAIN, 29f);
            Font dateFont = WallDigestCard.font("/assets/DejaVuSans.ttf", Font.PLAIN, 22f);
            Font footerFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 19f);
            Color dark = new Color(0x24, 0x35, 0x2d);

            WallDigestCard.text(g, "ЧЕРДАК", 62, 68, logoFont, WallDigestCard.CREAM);

            String badge = nomination.kind().badge();
            g.setFont(badgeFont);
            int badgeWidth = g.getFontMetrics().stringWidth(badge);
            g.setColor(WallDigestCard.BADGE);
            g.fillRoundRect(62, 112, 32 + badgeWidth, 43, 21, 21);
            g.setColor(dark);
            g.drawString(badge, 78, 141);

            // Ник — главная строка карточки. Базовая линия 268: верх букв ~210,
            // то есть заметно ниже бейджа, который заканчивается на 155.
            List<String> nickLines = WallDigestCard.wrap(nomination.playerName(),
                    g.getFontMetrics(nickFont), 1100);
            if (nickLines.size() > 1) {
                nickFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 46f);
                nickLines = WallDigestCard.wrap(nomination.playerName(),
                        g.getFontMetrics(nickFont), 1100);
            }
            int y = 268;
            for (String line : nickLines) {
                WallDigestCard.text(g, line, 62, y, nickFont, WallDigestCard.CREAM);
                y += 82;
            }

            String value = nomination.valueLine(config.showValues());
            if (!value.isEmpty()) {
                int valueY = Math.max(390, y + 24);
                for (String line : WallDigestCard.wrap(value, g.getFontMetrics(valueFont), 1000)) {
                    WallDigestCard.text(g, line, 64, valueY, valueFont, WallDigestCard.ACCENT);
                    valueY += 38;
                }
            }

            WallDigestCard.text(g, humanDate(day), 64, 560, dateFont, MUTED);

            g.setColor(WallDigestCard.ACCENT);
            g.drawLine(64, 645, 118, 645);
            WallDigestCard.text(g, config.footer(), 132, 651, footerFont, WallDigestCard.CREAM);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("Не удалось закодировать PNG карточки игрока");
            }
            return out.toByteArray();
        } finally {
            g.dispose();
        }
    }

    static String humanDate(String day) {
        try {
            LocalDate date = LocalDate.parse(day);
            return date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
        } catch (RuntimeException badDay) {
            return day == null ? "" : day;
        }
    }
}
