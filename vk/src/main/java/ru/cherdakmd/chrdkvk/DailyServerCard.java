package ru.cherdakmd.chrdkvk;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Карточка «День сервера» (2.0.6): пик онлайна, новички и активность суток.
 * Это не номинация игрока, а агрегат из {@link WallDigestCollector} — имена,
 * суммы платежей, UUID и IP не публикуются.
 *
 * <p>Композиция повторяет {@link DailyPlayerCard}: тот же фон, тот же бейдже-
 * вый блок сверху, тот же футер с контактом сервера и датой суток.</p>
 */
public final class DailyServerCard {

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    /** Суточный срез статистики сервера для PNG и текста записи. */
    public record Stats(int peakOnline, int newPlayers, long mobKills, long blocks) {

        public Stats {
            if (peakOnline < 0) {
                peakOnline = 0;
            }
            if (newPlayers < 0) {
                newPlayers = 0;
            }
            if (mobKills < 0) {
                mobKills = 0;
            }
            if (blocks < 0) {
                blocks = 0;
            }
        }

        /** Пустые сутки без единого наблюдения карточку не публикуют. */
        public boolean hasData() {
            return peakOnline > 0 || newPlayers > 0 || mobKills > 0 || blocks > 0;
        }
    }

    private DailyServerCard() {
    }

    /** Текст записи на стене; маркер нужен для ручной сверки дедупа. */
    public static String message(Stats stats, DailyCardsConfig config, String day, String marker) {
        StringBuilder out = new StringBuilder();
        out.append("CHERDAK · ").append(DailyCardKind.SERVER.title())
                .append(" · ").append(DailyPlayerCard.humanDate(day)).append("\n\n");
        for (String line : rows(stats, config.showValues())) {
            out.append("— ").append(line).append('\n');
        }
        out.append('\n').append(config.footer());
        if (!config.contact().isBlank()) {
            out.append('\n').append(config.contact());
        }
        if (marker != null && !marker.isBlank()) {
            out.append('\n').append(marker);
        }
        return out.toString();
    }

    /** Строки показателей; без значений остаются только подписи. */
    static String[] rows(Stats stats, boolean showValues) {
        String peak = stats.peakOnline() + " "
                + DailyNomination.plural(stats.peakOnline(), "игрок", "игрока", "игроков");
        String newbies = stats.newPlayers() + " "
                + DailyNomination.plural(stats.newPlayers(), "новичок", "новичка", "новичков");
        String mobs = stats.mobKills() + " "
                + DailyNomination.plural(stats.mobKills(), "победа", "победы", "побед")
                + " над монстрами";
        String blocks = stats.blocks() + " "
                + DailyNomination.plural(stats.blocks(), "блок", "блока", "блоков") + " добыто";
        return new String[] {
                showValues ? "Пик онлайна: " + peak : "Пик онлайна",
                showValues ? "Новичков за сутки: " + newbies : "Новички за сутки",
                showValues ? "Активность: " + mobs : "Активность",
                showValues ? "Добыча: " + blocks : "Добыча",
        };
    }

    /** PNG 1280×720 в памяти; загрузкой занимается {@link VkWallPublisher}. */
    public static byte[] render(Stats stats, DailyCardsConfig config, String day) throws IOException {
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
            Font numberFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 62f);
            Font labelFont = WallDigestCard.font("/assets/DejaVuSans.ttf", Font.PLAIN, 23f);
            Font dateFont = WallDigestCard.font("/assets/DejaVuSans.ttf", Font.PLAIN, 22f);
            Font footerFont = WallDigestCard.font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 19f);
            Color dark = new Color(0x24, 0x35, 0x2d);

            WallDigestCard.text(g, "ЧЕРДАК", 62, 68, logoFont, WallDigestCard.CREAM);

            String badge = DailyCardKind.SERVER.badge();
            g.setFont(badgeFont);
            int badgeWidth = g.getFontMetrics().stringWidth(badge);
            g.setColor(WallDigestCard.BADGE);
            g.fillRoundRect(62, 112, 32 + badgeWidth, 43, 21, 21);
            g.setColor(dark);
            g.drawString(badge, 78, 141);

            // Сетка 2×2: крупное число с подписью под ним.
            String[] numbers = {
                    String.valueOf(stats.peakOnline()),
                    String.valueOf(stats.newPlayers()),
                    String.valueOf(stats.mobKills()),
                    String.valueOf(stats.blocks()),
            };
            String[] labels = {"ПИК ОНЛАЙНА", "НОВИЧКОВ ЗА СУТКИ", "ПОБЕД НАД МОНСТРАМИ", "БЛОКОВ ДОБЫТО"};
            int[] xs = {64, 664};
            int[] numberBaselines = {300, 472};
            for (int cell = 0; cell < 4; cell++) {
                int x = xs[cell % 2];
                int numberY = numberBaselines[cell / 2];
                if (config.showValues()) {
                    WallDigestCard.text(g, numbers[cell], x, numberY, numberFont, WallDigestCard.CREAM);
                }
                WallDigestCard.text(g, labels[cell], x, numberY + 42, labelFont, WallDigestCard.ACCENT);
            }

            WallDigestCard.text(g, DailyPlayerCard.humanDate(day), 64, 560, dateFont, WallDigestCard.CREAM);

            g.setColor(WallDigestCard.ACCENT);
            g.drawLine(64, 645, 118, 645);
            WallDigestCard.text(g, config.footer(), 132, 651, footerFont, WallDigestCard.CREAM);
            String contact = config.contact();
            if (!contact.isBlank()) {
                int contactX = WIDTH - 64 - g.getFontMetrics(footerFont).stringWidth(contact);
                if (contactX > 560) {
                    WallDigestCard.text(g, contact, contactX, 651, footerFont, WallDigestCard.ACCENT);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("Не удалось закодировать PNG карточки дня сервера");
            }
            return out.toByteArray();
        } finally {
            g.dispose();
        }
    }
}
