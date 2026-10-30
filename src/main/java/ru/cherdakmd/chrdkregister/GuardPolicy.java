package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Проверяем только корень команды: пароли, коды и текст сообщений не анализируем и не журналируем. */
final class GuardPolicy {
    record Target(String canonical, List<String> expansions) { }

    static String token(String raw) {
        if (raw == null) return "";
        String s = raw.stripLeading();
        int start = 0;
        while (start < s.length() && s.charAt(start) == '/') start++;
        int end = start;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(start,end).toLowerCase(Locale.ROOT);
    }

    static String label(String raw) {
        String token = token(raw);
        return token.substring(token.lastIndexOf(':')+1);
    }

    static boolean consoleOnly(String raw, Set<String> blocked, Function<String,Target> resolver) {
        return inspect(raw,blocked,resolver,new HashSet<>(),new int[]{32},0);
    }

    private static boolean inspect(String raw, Set<String> blocked, Function<String,Target> resolver,
                                   Set<String> path, int[] remaining, int depth) {
        String root = token(raw);
        if (root.isEmpty() || root.contains("$") || depth > 8 || --remaining[0] < 0) return true;
        if (blocked.contains(label(root))) return true;
        if (!path.add(root)) return true;
        try {
            Target target = resolver.apply(root);
            if (target == null) return false;
            if (blocked.contains(label(target.canonical()))) return true;
            for (String expansion : target.expansions()) {
                if (inspect(expansion,blocked,resolver,path,remaining,depth+1)) return true;
            }
            return false;
        } finally { path.remove(root); }
    }

    private GuardPolicy() { }
}
