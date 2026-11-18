package ru.cherdakmd.chrdkanticheat;

/**
 * Разбор служебной команды моста {@code chrdkac flag <игрок> <чек> <vl> [verbose…]}.
 *
 * <p>Команду исполняет детектор (Grim) из консоли со своими плейсхолдерами;
 * разбор обязан терпеть имена с пробелами в verbose и не падать на мусоре.</p>
 */
final class FlagIngest {

    /** Результат разбора: кого, за что и с каким уровнем нарушения поймали. */
    record Parsed(String player, String check, int vl, String verbose) {
    }

    private FlagIngest() {
    }

    /**
     * Разбирает аргументы команды целиком (включая слово {@code flag}).
     *
     * @throws IllegalArgumentException если форма команды нарушена
     */
    static Parsed parse(String[] args) {
        if (args == null || args.length < 4 || !args[0].equalsIgnoreCase("flag")) {
            throw new IllegalArgumentException("форма команды: chrdkac flag <игрок> <чек> <vl> [verbose…]");
        }
        String player = args[1].trim();
        String check = args[2].trim();
        if (player.isEmpty() || check.isEmpty()) {
            throw new IllegalArgumentException("игрок и чек не могут быть пустыми");
        }
        int vl;
        try {
            vl = Integer.parseInt(args[3].trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("vl должен быть целым числом", error);
        }
        if (vl < 0) {
            throw new IllegalArgumentException("vl не может быть отрицательным");
        }
        StringBuilder details = new StringBuilder();
        for (int i = 4; i < args.length; i++) {
            if (i > 4) {
                details.append(' ');
            }
            details.append(args[i]);
        }
        return new Parsed(player, check, vl, details.toString().trim());
    }
}
