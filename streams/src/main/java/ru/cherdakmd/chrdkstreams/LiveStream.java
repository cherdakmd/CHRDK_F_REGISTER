package ru.cherdakmd.chrdkstreams;

/**
 * Снимок эфира канала: идёт ли, как называется и сколько зрителей.
 *
 * @param live      эфир идёт
 * @param title     название (может быть пустым)
 * @param viewers   зрителей сейчас
 */
record LiveStream(boolean live, String title, int viewers) {

    static LiveStream offline() {
        return new LiveStream(false, "", 0);
    }

    static LiveStream live(String title, int viewers) {
        return new LiveStream(true, title == null ? "" : title.trim(), Math.max(0, viewers));
    }
}
