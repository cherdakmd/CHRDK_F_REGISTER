package ru.cherdakmd.chrdkstreams;

/**
 * Срок жизни неподтверждённой заявки зрителя.
 *
 * <p>Заявка, которую стример так и не принял, не должна висеть годами: она
 * занимает место в панели и путает и зрителя, и стримера. По истечении TTL
 * заявка удаляется (прогресс зрителя при этом остаётся — он живёт отдельно).
 * Подтверждённые привязки срок не трогает.</p>
 */
final class ViewerRequests {

    private ViewerRequests() {
    }

    /**
     * Заявка устарела?
     *
     * @param binding   заявка зрителя
     * @param ttlMillis срок жизни; {@code <= 0} — заявки не истекают
     * @param nowMillis текущее время
     */
    static boolean expired(ViewerBinding binding, long ttlMillis, long nowMillis) {
        if (binding == null || binding.accepted() || ttlMillis <= 0L) {
            return false;
        }
        return nowMillis - binding.requestedAt() > ttlMillis;
    }

    /** Час в миллисекундах из конфига; {@code 0} — бессрочно. */
    static long ttlMillis(int hours) {
        return hours <= 0 ? 0L : hours * 3_600_000L;
    }
}
