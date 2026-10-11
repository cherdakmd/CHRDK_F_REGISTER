package ru.cherdakmd.chrdkregister;

import java.util.Locale;

/**
 * Helpers that compare a DonatePay donor name with known Minecraft nicknames without ever granting a pass
 * on their own: they only produce hints (case-insensitive match, separator-insensitive match, wrong keyboard
 * layout, single typo) that administrators can confirm via {@code /pass approve}.
 */
public final class DonorNameMatcher {
    /** Maps a Russian letter typed on the ЙЦУКЕН layout to the Latin key it shares with QWERTY. */
    private static final String RU_LAYOUT = "йцукенгшщзхъфывапролджэячсмитьбюё";
    private static final String EN_LAYOUT = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";

    private DonorNameMatcher() {
    }

    /** Lower-cases the text and drops every character that is not a letter or a digit. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder result = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = Character.toLowerCase(raw.charAt(index));
            if (Character.isLetterOrDigit(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    /** Converts a nickname typed on the wrong keyboard layout into the other layout. */
    public static String toOtherLayout(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String normalized = normalize(raw);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            int ruIndex = RU_LAYOUT.indexOf(character);
            if (ruIndex >= 0) {
                result.append(EN_LAYOUT.charAt(ruIndex));
                continue;
            }
            int enIndex = EN_LAYOUT.indexOf(character);
            if (enIndex >= 0 && enIndex < RU_LAYOUT.length()) {
                result.append(RU_LAYOUT.charAt(enIndex));
                continue;
            }
            result.append(character);
        }
        return result.toString();
    }

    /** Returns {@code true} when both names become identical after a layout switch. */
    public static boolean layoutEquivalent(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        return toOtherLayout(left).equals(right) || left.equals(toOtherLayout(right));
    }

    /** Standard Levenshtein distance with a length shortcut for very different inputs. */
    public static int levenshtein(String first, String second) {
        String left = first == null ? "" : first;
        String right = second == null ? "" : second;
        if (left.equals(right)) return 0;
        if (left.isEmpty()) return right.length();
        if (right.isEmpty()) return left.length();
        if (Math.abs(left.length() - right.length()) > 2) return Math.abs(left.length() - right.length());

        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * Returns {@code true} when the donor name plausibly refers to the nickname: exact (after normalization),
     * wrong layout, or a single typo away.
     */
    public static boolean looksLikeDonor(String donorName, String nickname) {
        String donor = normalize(donorName);
        String candidate = normalize(nickname);
        if (donor.isEmpty() || candidate.isEmpty()) return false;
        if (donor.equals(candidate)) return true;
        if (layoutEquivalent(donor, candidate)) return true;
        return levenshtein(donor, candidate) <= 1;
    }

    /** Formats a similarity description for admin output, e.g. {@code "совпадение без учёта регистра"}. */
    public static String describeMatch(String donorName, String nickname) {
        String donor = normalize(donorName);
        String candidate = normalize(nickname);
        if (donor.equals(candidate)) {
            return donorName.equals(nickname) ? "точное совпадение" : "совпадение без учёта регистра/разделителей";
        }
        if (layoutEquivalent(donor, candidate)) {
            return "похоже на опечатку в раскладке клавиатуры";
        }
        int distance = levenshtein(donor, candidate);
        return distance <= 1 ? "одна опечатка" : "слабое совпадение (расстояние " + distance + ")";
    }

    /** Returns the name lower-cased for logging. */
    public static String lowercase(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }
}
