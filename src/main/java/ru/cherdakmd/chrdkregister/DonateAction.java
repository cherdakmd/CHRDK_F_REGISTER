package ru.cherdakmd.chrdkregister;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Консольное действие по входящему донату (идея BitHeaven DonatePay Integration):
 * при успешной оплате с суммой ≥ {@link #minSumRubles} ядро выполняет команду
 * от имени консоли. Плейсхолдеры в команде:
 *
 * <ul>
 *   <li>{@code {username}} — имя, введённое донатором в форме оплаты;</li>
 *   <li>{@code {player}} — ник, которому выдана проход (или username, если донат ещё не заматчился);</li>
 *   <li>{@code {sum}} — сумма в рублях (целое);</li>
 *   <li>{@code {message}} — комментарий донатора к платежу.</li>
 * </ul>
 *
 * <p>Подстановка донорских строк всегда проходит через {@link #sanitize(String)}:
 * кавычки, обратные слэши и управляющие символы вырезаются, чтобы комментарий
 * «сломай title JSON» не превращался в инъекцию.</p>
 */
public record DonateAction(long minSumRubles, String command) {

    /** Разбор секции {@code donation-actions:} (список карт sum/command). */
    public static List<DonateAction> parseList(List<Map<?, ?>> maps) {
        if (maps == null || maps.isEmpty()) {
            return List.of();
        }
        List<DonateAction> result = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            if (map == null) continue;
            Object sumRaw = map.get("sum");
            Object commandRaw = map.get("command");
            long minSum = toLong(sumRaw, 0);
            String command = commandRaw == null ? "" : commandRaw.toString().trim();
            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (minSum < 0 || command.isEmpty()) {
                continue;
            }
            result.add(new DonateAction(minSum, command));
        }
        return List.copyOf(result);
    }

    /** Сумма транзакции в целых рублях. */
    public static long rublesOf(BigDecimal amount) {
        if (amount == null) {
            return 0;
        }
        return amount.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** Вырезает из донорского ввода всё, чем можно сломать консольную команду/JSON. */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'' || c == '\\' || Character.isISOControl(c)) {
                continue;
            }
            clean.append(c);
        }
        String trimmed = clean.toString().trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    /** Подставляет плейсхолдеры в шаблон команды. */
    public String render(String username, String player, String sum, String message) {
        return command
                .replace("{username}", username)
                .replace("{player}", player)
                .replace("{sum}", sum)
                .replace("{message}", message)
                .trim();
    }

    private static long toLong(Object raw, long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw != null) {
            try {
                return Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
