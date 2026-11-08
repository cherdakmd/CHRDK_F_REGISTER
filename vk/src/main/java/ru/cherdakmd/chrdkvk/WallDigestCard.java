package ru.cherdakmd.chrdkvk;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Постер дайджеста 1280×720 в стиле уже утверждённых карточек сообщества:
 * тот же фон, градиент, логотип «ЧЕРДАК», бейдж и подвал. Рисуется на самом
 * сервере через Java2D, без Python/Pillow и без обращения к GitHub Actions.
 *
 * <p>Шрифт и фон упакованы в JAR, поэтому результат не зависит от того, какие
 * шрифты установлены на машине с Minecraft. Если ресурсы недоступны, есть
 * плоский запасной фон и логический шрифт — плагин не падает.</p>
 */
public final class WallDigestCard {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    private static final Color CREAM = new Color(0xff, 0xf5, 0xdf);
    private static final Color ACCENT = new Color(0xff, 0xd6, 0x8a);
    private static final Color BADGE = new Color(0xf1, 0xcf, 0x8e);
    private static final Color SHADOW = new Color(0x20, 0x31, 0x2b);
    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    private WallDigestCard() {
    }

    /** Текст записи на стене: короткая выжимка плюс служебный маркер дедупликации. */
    public static String message(WallDigestCollector.Digest digest, WallDigestConfig config, String marker) {
        StringBuilder out = new StringBuilder();
        out.append("CHERDAK · ").append(config.title()).append(" · ")
                .append(humanDate(digest.day())).append("\n\n");
        for (String line : lines(digest, config)) {
            out.append("— ").append(line).append('\n');
        }
        out.append("\n").append(config.footer());
        if (marker != null && !marker.isBlank()) {
            out.append("\n").append(marker);
        }
        return out.toString();
    }

    /** PNG в памяти; вызывающая сторона загружает его через photos.getWallUploadServer. */
    public static byte[] render(WallDigestCollector.Digest digest, WallDigestConfig config) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            drawBackground(g);
            drawGradient(g);

            Font logoFont = font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 29f);
            Font badgeFont = font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 21f);
            Font titleFont = font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 62f);
            Font bodyFont = font("/assets/DejaVuSans.ttf", Font.PLAIN, 27f);
            Font footerFont = font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 19f);

            text(g, "ЧЕРДАК", 62, 68, logoFont, CREAM);

            String badge = "ИТОГИ ДНЯ";
            g.setFont(badgeFont);
            int badgeWidth = g.getFontMetrics().stringWidth(badge);
            g.setColor(BADGE);
            g.fillRoundRect(62, 112, 32 + badgeWidth, 43, 21, 21);
            g.setColor(new Color(0x24, 0x35, 0x2d));
            g.drawString(badge, 78, 141);

            int y = 194;
            List<String> title = wrap(humanDate(digest.day()), g.getFontMetrics(titleFont), 770);
            if (title.size() > 3) {
                titleFont = font("/assets/DejaVuSans-Bold.ttf", Font.BOLD, 52f);
                title = wrap(humanDate(digest.day()), g.getFontMetrics(titleFont), 770);
            }
            for (String line : title) {
                text(g, line, 58, y, titleFont, CREAM);
                y += 83;
            }

            y = Math.max(420, y + 24);
            List<String> body = lines(digest, config);
            if (body.isEmpty()) {
                body = List.of("Тихий день на сервере — без громких событий.");
            }
            for (String line : body) {
                List<String> wrapped = wrap(line, g.getFontMetrics(bodyFont), 730);
                for (String part : wrapped) {
                    text(g, part, 64, y, bodyFont, CREAM);
                    y += 39;
                }
            }
            if (y > 604) {
                throw new IOException("Подпись дайджеста перекрывает подвал карточки");
            }

            g.setColor(ACCENT);
            g.drawLine(64, 645, 118, 645);
            text(g, config.footer(), 132, 651, footerFont, CREAM);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Не удалось закодировать PNG дайджеста");
        }
        return out.toByteArray();
    }

    /** Одни и те же строки попадают и на картинку, и в текст записи. */
    private static List<String> lines(WallDigestCollector.Digest digest, WallDigestConfig config) {
        List<String> lines = new ArrayList<>();
        if (config.includePrestige() && digest.prestigeEvents() > 0) {
            String line = "Престиж: " + digest.prestigeEvents() + " "
                    + plural(digest.prestigeEvents(), "ступень", "ступени", "ступеней");
            if (!digest.leaders().isEmpty()) {
                line += " · лидеры: " + String.join(", ", digest.leaders());
            }
            lines.add(line);
        }
        if (config.includeDonations() && digest.donationEvents() > 0) {
            lines.add("Поддержка сервера: " + digest.donationEvents() + " "
                    + plural(digest.donationEvents(), "покупка", "покупки", "покупок"));
        }
        if (config.includeOnline() && (digest.peakOnline() > 0 || digest.newPlayers() > 0)) {
            lines.add("Онлайн: пик " + digest.peakOnline() + " · новичков " + digest.newPlayers());
        }
        return lines;
    }

    private static void drawBackground(Graphics2D g) {
        try (InputStream in = WallDigestCard.class.getResourceAsStream("/assets/world-background.jpg")) {
            if (in != null) {
                BufferedImage source = ImageIO.read(in);
                if (source != null) {
                    g.drawImage(source, 0, 0, WIDTH, HEIGHT, null);
                    return;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Запасной плоский фон ниже.
        }
        g.setColor(new Color(0x12, 0x2a, 0x28));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    /** Затемнение слева, чтобы кремовый текст читался на любом участке фона. */
    private static void drawGradient(Graphics2D g) {
        for (int x = 0; x < WIDTH; x++) {
            double falloff = Math.max(0, 1 - x / 1160.0);
            int alpha = (int) Math.round(218 * Math.pow(falloff, 0.65));
            g.setColor(new Color(0x06, 0x18, 0x19, alpha));
            g.drawLine(x, 0, x, HEIGHT);
        }
    }

    private static Font font(String resource, int style, float size) {
        try (InputStream in = WallDigestCard.class.getResourceAsStream(resource)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(style, size);
            }
        } catch (Exception | LinkageError bad) {
            // Логический шрифт ниже.
        }
        return new Font(Font.SANS_SERIF, style, (int) size);
    }

    private static void text(Graphics2D g, String line, int x, int y, Font face, Color color) {
        g.setFont(face);
        g.setColor(SHADOW);
        g.drawString(line, x + 2, y + 2);
        g.setColor(color);
        g.drawString(line, x, y);
    }

    private static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> result = new ArrayList<>();
        String current = "";
        for (String raw : text.split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String word = raw;
            // Слишком длинное слово режем по символам, чтобы не вылезти за карточку.
            while (metrics.stringWidth(word) > width && word.length() > 1) {
                if (!current.isEmpty()) {
                    result.add(current);
                    current = "";
                }
                int cut = 1;
                while (cut < word.length() && metrics.stringWidth(word.substring(0, cut + 1)) <= width) {
                    cut++;
                }
                result.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (metrics.stringWidth(candidate) <= width) {
                current = candidate;
            } else {
                if (!current.isEmpty()) {
                    result.add(current);
                }
                current = word;
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private static String humanDate(String day) {
        LocalDate date;
        try {
            date = LocalDate.parse(day);
        } catch (RuntimeException bad) {
            return day == null ? "" : day;
        }
        return date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
    }

    private static String plural(int number, String one, String few, String many) {
        int modulo100 = Math.abs(number) % 100;
        int modulo10 = modulo100 % 10;
        if (modulo100 > 10 && modulo100 < 20) {
            return many;
        }
        if (modulo10 > 1 && modulo10 < 5) {
            return few;
        }
        return modulo10 == 1 ? one : many;
    }
}
