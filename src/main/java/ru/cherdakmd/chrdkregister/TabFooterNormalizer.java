package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Рантайм-совместимость подвала TAB без перезаписи владельческого config.yml.
 *
 * <p>Раньше в {@code tablist.footer} было две строки про один и тот же срок:
 * крупный {@code %chrdksocial_status%} и «Привязка / Проход» с двумя счётчиками.
 * Теперь эталон использует одну строку {@code %chrdksocial_tab%}, а старые
 * шаблоны сливаются в неё при чтении конфига. Строка-подсказка
 * {@code %auth_hint%} скрывается, только если она состоит лишь из этой
 * подстановки и повторяет уже показанный статус «в игре».</p>
 */
public final class TabFooterNormalizer {

    /** Одна строка социального статуса в эталонном config.yml. */
    public static final String SOCIAL_LINE = "%chrdksocial_tab%";
    /** Маркер строки, которую нужно убрать целиком, а не оставить пустой. */
    public static final String HIDE_LINE = "\u0000chrdk-hide-line\u0000";

    private static final Set<String> LEGACY_SOCIAL_TOKENS = Set.of(
            "%chrdksocial_status%", "%chrdksocial_link%", "%chrdksocial_access%");
    private static final Pattern MARKUP = Pattern.compile("<[^>]*>|&[0-9a-fk-orA-FK-OR]");
    private static final String LEGACY_AUTH_HINT = "вы в игре";

    private TabFooterNormalizer() {
    }

    /** Заменяет все старые социальные строки одной цельной и убирает дубли. */
    public static List<String> normalizeFooter(List<String> footer) {
        if (footer == null || footer.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(footer.size());
        boolean socialWritten = false;
        for (String raw : footer) {
            String line = raw == null ? "" : raw;
            if (line.contains(SOCIAL_LINE)) {
                if (!socialWritten) {
                    out.add(line);
                    socialWritten = true;
                }
                continue;
            }
            if (isLegacySocialLine(line)) {
                if (!socialWritten) {
                    out.add(SOCIAL_LINE);
                    socialWritten = true;
                }
                continue;
            }
            out.add(line);
        }
        return List.copyOf(out);
    }

    public static boolean isLegacySocialLine(String line) {
        if (line == null || line.isBlank()) return false;
        for (String token : LEGACY_SOCIAL_TOKENS) {
            if (line.contains(token)) return true;
        }
        return false;
    }

    /** True для строк вида {@code <gray>%auth_hint%</gray>} без соседнего текста. */
    public static boolean isAuthHintOnlyLine(String line) {
        if (line == null || !line.contains("%auth_hint%")) return false;
        String skeleton = MARKUP.matcher(line.replace("%auth_hint%", "")).replaceAll("");
        skeleton = skeleton.replace("│", "").replace("|", "")
                .replace("·", "").replace("•", "").trim();
        return skeleton.isEmpty();
    }

    /** Прячем только исторический дубль «Вы в игре» рядом со статусом «в игре». */
    public static boolean isRedundantAuthenticatedHint(PresentationContext presentation) {
        if (presentation == null) return false;
        String status = presentation.authStatus() == null ? ""
                : presentation.authStatus().toLowerCase(Locale.ROOT);
        String hint = presentation.authHint() == null ? ""
                : presentation.authHint().toLowerCase(Locale.ROOT);
        return status.equals("в игре") && hint.startsWith(LEGACY_AUTH_HINT);
    }

    /** Без PAPI или без CHRDK_VK строку нельзя показывать как сырой плейсхолдер. */
    public static String hideUnresolvedSocialLine(String template) {
        if (template == null || !template.contains(SOCIAL_LINE)) return template;
        return template.replace(SOCIAL_LINE, HIDE_LINE);
    }
}
