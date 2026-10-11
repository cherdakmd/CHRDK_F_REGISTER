package ru.cherdakmd.chrdkregister;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Password quality rules for {@code /register} and {@code /changepassword}.
 *
 * <p>Beyond the length limits the policy rejects passwords that equal (or almost equal) the nickname,
 * a compact list of the most common leaked passwords, monotonic sequences ({@code 12345678}) and repeated
 * characters ({@code aaaaaaaa}). It also reports the strength so the player gets actionable feedback.</p>
 */
public final class PasswordPolicy {
    /** Verdict of the policy check. */
    public record Result(boolean accepted, String message, String strength) {
    }

    /** Compact list of the most frequently used passwords that must never be accepted. */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456", "1234567", "12345678", "123456789", "1234567890", "password", "password1", "password123",
            "qwerty", "qwerty123", "qwertyuiop", "111111", "000000", "222222", "333333", "123123", "123321",
            "abc123", "654321", "iloveyou", "admin", "admin123", "root", "letmein", "welcome", "monkey",
            "dragon", "sunshine", "princess", "football", "baseball", "master", "shadow", "superman",
            "batman", "trustno1", "login", "starwars", "hello", "hello123", "freedom", "whatever", "ninja",
            "minecraft", "minecraft123", "mc12345", "steve", "alex", "herobrine", "notch", "mojang",
            "fuckyou", "fuckyou1", "love", "love123", "danil", "данил", "йцукен", "йцукенг", "пароль",
            "привет", "привет123", "россия", "макс", "максим", "наташа", "сергей", "андрей", "алексей",
            "ivan", "ivanov", "petrov", "sokolov", "qazwsx", "qazwsxedc", "1q2w3e4r", "1qaz2wsx", "zaq12wsx",
            "asdfgh", "asdfghjkl", "zxcvbnm", "qwe123", "qweqwe", "123qwe", "pass", "pass123", "player",
            "gamer", "csgo", "dota2", "gta5", "samp", "samp123", "майнкрафт", "кот", "котик", "дом",
            "тест", "test", "test123", "user", "user123", "default", "changeme", "secret", "minecraft1",
            "chrdk", "donatepay", "prohodka", "проходка", "донат", "server", "server123");

    private PasswordPolicy() {
    }

    /**
     * Checks a password-against the rules.
     *
     * @param nickname the player's nickname (checked against the password)
     * @param config the current plugin configuration
     */
    public static Result check(String nickname, String password, PluginConfig config) {
        if (password == null || password.isEmpty()) {
            return new Result(false, "Пароль не может быть пустым.", "нет");
        }
        if (password.length() < config.minPasswordLength() || password.length() > config.maxPasswordLength()) {
            return new Result(false, "Длина пароля должна быть от " + config.minPasswordLength()
                    + " до " + config.maxPasswordLength() + " символов.", strengthOf(password));
        }
        if (password.contains(" ")) {
            return new Result(false, "Пароль не должен содержать пробелы.", strengthOf(password));
        }
        String strength = strengthOf(password);
        String lowered = password.toLowerCase(Locale.ROOT);
        String nick = nickname == null ? "" : nickname.toLowerCase(Locale.ROOT);

        if (config.rejectNicknameAsPassword() && !nick.isEmpty()) {
            if (lowered.equals(nick)
                    || DonorNameMatcher.levenshtein(DonorNameMatcher.normalize(lowered),
                    DonorNameMatcher.normalize(nick)) <= 1
                    || (nick.length() >= 4 && lowered.contains(nick))) {
                return new Result(false, "Пароль не должен совпадать с вашим ником или быть слишком похожим на него.",
                        strength);
            }
        }
        if (config.rejectCommonPasswords()) {
            if (COMMON_PASSWORDS.contains(lowered)) {
                return new Result(false, "Этот пароль слишком часто используется и легко подбирается. Придумайте другой.",
                        strength);
            }
            if (isMonotonic(lowered) || isRepeated(lowered)) {
                return new Result(false, "Пароль из одинаковых или последовательных символов легко подобрать.",
                        strength);
            }
        }
        return new Result(true, "", strength);
    }

    /** Returns {@code weak}, {@code medium} or {@code strong} in Russian for player feedback. */
    public static String strengthOf(String password) {
        if (password == null || password.isEmpty()) return "нет";
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(character -> !Character.isLetterOrDigit(character))) classes++;
        int score = 0;
        if (password.length() >= 8) score++;
        if (password.length() >= 12) score++;
        if (classes >= 2) score++;
        if (classes >= 3) score++;
        if (isMonotonic(password.toLowerCase(Locale.ROOT)) || isRepeated(password.toLowerCase(Locale.ROOT))) {
            score = Math.min(score, 0);
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) score = 0;
        if (score >= 3) return "надёжный";
        if (score == 2) return "средний";
        return "слабый";
    }

    /** Returns a player-facing hint list for improving a weak password. */
    public static List<String> hints(String password) {
        List<String> hints = new java.util.ArrayList<>();
        if (password == null || password.length() < 10) {
            hints.add("сделайте пароль длиннее (10+ символов)");
        }
        if (password != null && password.chars().noneMatch(Character::isUpperCase)) {
            hints.add("добавьте заглавные буквы");
        }
        if (password != null && password.chars().noneMatch(Character::isDigit)) {
            hints.add("добавьте цифры");
        }
        if (password != null && password.chars().allMatch(Character::isLetterOrDigit)) {
            hints.add("добавьте символы вроде ! ? # _");
        }
        return hints;
    }

    /**
     * Detects a password shared with another account that recently logged in from the same IP.
     * Only the accounts from the given candidate list are checked, and nicknames are never disclosed.
     */
    public static Optional<String> findSharedAccount(
            RegistrationStore store,
            String selfName,
            String candidatePassword,
            List<String> sameIpAccounts
    ) {
        for (String other : sameIpAccounts) {
            if (other == null || other.isBlank()) continue;
            if (other.equalsIgnoreCase(selfName)) continue;
            if (store.verifyPassword(other, candidatePassword)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    private static boolean isMonotonic(String lowered) {
        if (lowered.length() < 6) return false;
        boolean ascending = true;
        boolean descending = true;
        for (int index = 1; index < lowered.length(); index++) {
            int delta = lowered.charAt(index) - lowered.charAt(index - 1);
            if (delta != 1) ascending = false;
            if (delta != -1) descending = false;
        }
        return ascending || descending;
    }

    private static boolean isRepeated(String lowered) {
        if (lowered.length() < 6) return false;
        char first = lowered.charAt(0);
        for (int index = 1; index < lowered.length(); index++) {
            if (lowered.charAt(index) != first) return false;
        }
        return true;
    }
}
