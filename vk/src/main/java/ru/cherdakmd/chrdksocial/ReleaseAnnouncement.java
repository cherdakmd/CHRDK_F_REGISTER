package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;

/** Чистый парсер и форматтер новости о выпуске: без сети и без Bukkit, покрывается тестами. */
public final class ReleaseAnnouncement {

    public record Release(String tag, String title, String url, boolean stable) { }

    private ReleaseAnnouncement() { }

    /** GitHub Releases /releases/latest. Пререлизы, черновики и пустые теги не анонсируются. */
    public static Release parseLatest(JsonObject json) {
        if (json == null) return null;
        if (json.has("draft") && !json.get("draft").isJsonNull() && json.get("draft").getAsBoolean()) return null;
        if (json.has("prerelease") && !json.get("prerelease").isJsonNull()
                && json.get("prerelease").getAsBoolean()) return null;
        String tag = text(json, "tag_name");
        if (tag.isEmpty()) return null;
        String title = text(json, "name");
        if (title.isEmpty()) title = tag;
        String url = text(json, "html_url");
        if (url.isEmpty()) url = "https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/" + tag;
        return new Release(tag, title, url, true);
    }

    public static String format(String template, Release release) {
        if (release == null) return "";
        String safe = template == null || template.isBlank()
                ? SocialConfig.DEFAULT_NEWS_TEMPLATE : template;
        return safe.replace("%tag%", release.tag())
                .replace("%title%", release.title())
                .replace("%url%", release.url());
    }

    private static String text(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return "";
        try {
            return json.get(key).getAsString().trim();
        } catch (RuntimeException bad) {
            return "";
        }
    }
}
