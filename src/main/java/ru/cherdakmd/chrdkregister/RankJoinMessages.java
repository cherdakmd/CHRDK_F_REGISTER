package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Пулы приветствий и ограниченная память последнего варианта, без повторов подряд. */
final class RankJoinMessages {
    private static final List<String> PHRASES = List.of(
            "вернулся к общему костру", "открыл новую главу приключений", "снова шагает по тропам CHERDAK",
            "заглянул на огонёк", "принёс с собой настроение для приключений", "готов оставить след в этом мире",
            "сменил реальный мир на кубический", "вновь распахнул двери мастерской", "проверяет, всё ли на месте в сундуках",
            "вернулся за новыми открытиями", "стряхнул пыль с походного плаща", "снова с нами — встречайте!",
            "снаряжает рюкзак для новой вылазки", "прибыл с большими планами на вечер", "зажёг ещё один огонёк на карте",
            "выбрал отличный день для приключений", "готов строить то, чего ещё не было", "вернулся туда, где ждут друзья",
            "отправляется навстречу новым историям", "снова пополнил ряды искателей приключений");
    private final Map<String, String> previous = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 4096; }
    };

    static List<String> defaults(RankTier tier) {
        String title = tier.format().replace("%name%", Messages.escape(tier.badge() + " " + tier.name()));
        return PHRASES.stream().map(text -> title + " <white>%player%</white> <gray>" + text + "</gray>").toList();
    }

    synchronized String next(int level, String name, List<String> pool) {
        if (pool.isEmpty()) return "";
        String key = level + ":" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        String last = previous.get(key);
        List<String> candidates = pool.size() > 1 ? pool.stream().filter(line -> !line.equals(last)).toList() : pool;
        String selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        previous.put(key, selected);
        return selected.replace("%player%", Messages.escape(name));
    }
}
