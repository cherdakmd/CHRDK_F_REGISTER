package ru.cherdakmd.chrdkguilds;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Экспорт реальных RGB для фиксированного безопасного тега; не имитация формул на Python. */
public final class GuildAnimationPreview {
    private static final String TAG = "СЕВЕР";
    private static final Pattern RGB = Pattern.compile("<#([0-9a-f]{6})>(.*?)</#\\1>");

    static String frames() throws Exception {
        Path source = Path.of("src/main/java/ru/cherdakmd/chrdkguilds/GuildStyleAnimation.java");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        StringBuilder out = new StringBuilder("{\"schema\":1,\"sourceSha256\":\"" + sha
                + "\",\"tag\":\"" + TAG + "\",\"stepMillis\":250,\"frames\":{");
        boolean firstEffect = true;
        for (String effect : GuildStyleDefs.ANIMATIONS) {
            if (!firstEffect) out.append(',');
            firstEffect = false;
            out.append('"').append(effect).append("\":[");
            for (long time = 0; time < 12000; time += 250) {
                if (time > 0) out.append(',');
                Matcher matches = RGB.matcher(GuildStyleAnimation.render(TAG, null, null, effect, time));
                List<Integer> colors = new ArrayList<>();
                while (matches.find()) {
                    int rgb = Integer.parseInt(matches.group(1), 16);
                    for (int i = 0; i < matches.group(2).codePointCount(0, matches.group(2).length()); i++) colors.add(rgb);
                }
                if (colors.size() != TAG.codePointCount(0, TAG.length()))
                    throw new IllegalStateException("Потеря символа или RGB в " + effect);
                out.append(colors.toString());
            }
            out.append(']');
        }
        return out.append("}}\n").toString();
    }

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), frames());
    }
}
