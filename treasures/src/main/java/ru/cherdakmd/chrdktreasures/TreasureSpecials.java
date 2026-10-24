package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChrdkArtifactsApi;
import ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Особые находки в кладах (1.4.166): реликвии CHRDK_Artifacts и книги чар
 * CHRDK_Enchants.
 *
 * <p>Оба аддона — мягкие соседи: мосты берутся из {@code ServicesManager} в
 * момент находки, поэтому порядок включения плагинов не важен, а без аддона
 * источник просто не участвует. Это важно: сервер без артефактов не должен
 * терять клады, а сервер без чар — оставаться с пустыми книгами.</p>
 *
 * <p>Предмет кладётся в ту же добычу, что и всё остальное, поэтому попадает в
 * бочку, в инвентарь или под ноги тем же путём — отдельной ветки выдачи нет.
 * Наружу возвращаются только подписи для сообщения и журнала.</p>
 */
public final class TreasureSpecials {

    private final TreasuresPlugin plugin;
    private final Random random = new Random();
    /** Мосты кешируются: {@code ServicesManager#load} не должен зваться на каждый клад. */
    private volatile ChrdkArtifactsApi artifacts;
    private volatile ChrdkEnchantsApi enchants;

    TreasureSpecials(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- источники ------------------------------- */

    /** Сбрасывает кеш мостов — при reload и включении аддонов. */
    void reload() {
        this.artifacts = null;
        this.enchants = null;
    }

    /** Мост к артефактам или {@code null}, если аддон не установлен/старый. */
    private ChrdkArtifactsApi artifacts() {
        ChrdkArtifactsApi api = artifacts;
        if (api == null) {
            api = Bukkit.getServicesManager().load(ChrdkArtifactsApi.class);
            artifacts = api;
        }
        return api;
    }

    /** Мост к чарам или {@code null}, если аддон не установлен/старый. */
    private ChrdkEnchantsApi enchants() {
        ChrdkEnchantsApi api = enchants;
        if (api == null) {
            api = Bukkit.getServicesManager().load(ChrdkEnchantsApi.class);
            enchants = api;
        }
        return api;
    }

    /** Есть ли хоть один источник — для лога при включении. */
    boolean artifactsAvailable() {
        ChrdkArtifactsApi api = artifacts();
        return api != null && !api.ids().isEmpty();
    }

    /** Доступны ли книги чар. */
    boolean enchantmentsAvailable() {
        ChrdkEnchantsApi api = enchants();
        return api != null && !api.lightIds().isEmpty();
    }

    /* --------------------------------- находка -------------------------------- */

    /**
     * Добавляет особые находки в добычу клада.
     *
     * @param tier    редкость найденного клада
     * @param payload готовые предметы добычи — сюда же кладутся реликвия и книга
     * @return подписи для сообщения и журнала (пусто — ничего не выпало)
     */
    List<TreasureSpecial> grant(TreasureTier tier, List<ItemStack> payload) {
        TreasuresConfig.Specials settings = plugin.config().specials();
        if (payload == null || !settings.active()) {
            return List.of();
        }
        TreasureSpecialRolls.Plan plan = TreasureSpecialRolls.plan(settings, tier, random);
        if (!plan.any()) {
            return List.of();
        }
        List<TreasureSpecial> notes = new ArrayList<>(2);
        if (plan.artifact()) {
            TreasureSpecial note = artifact(plan.artifactCursed(), payload);
            if (note != null) {
                notes.add(note);
            }
        }
        if (plan.enchant()) {
            TreasureSpecial note = book(plan.enchantCursed(), plan.levelCap(), payload);
            if (note != null) {
                notes.add(note);
            }
        }
        return notes;
    }

    private TreasureSpecial artifact(boolean cursed, List<ItemStack> payload) {
        ChrdkArtifactsApi api = artifacts();
        if (api == null) {
            return null;
        }
        List<String> pool = api.ids(cursed);
        if (pool.isEmpty()) {
            // Пула нужной стороны нет — берём другую, но подпись честная.
            pool = api.ids(!cursed);
            cursed = !cursed;
        }
        if (pool.isEmpty()) {
            return null;
        }
        String id = pool.get(random.nextInt(pool.size()));
        ItemStack item = api.create(id, 1);
        if (item == null) {
            return null;
        }
        payload.add(item);
        return new TreasureSpecial(
                cursed ? TreasureSpecial.Kind.ARTIFACT_CURSED : TreasureSpecial.Kind.ARTIFACT,
                api.name(id), 0);
    }

    private TreasureSpecial book(boolean cursed, int levelCap, List<ItemStack> payload) {
        ChrdkEnchantsApi api = enchants();
        if (api == null) {
            return null;
        }
        List<String> pool = cursed ? api.curseIds() : api.lightIds();
        if (pool.isEmpty()) {
            // Проклятий нет или они выключены — книга будет светлой.
            pool = api.lightIds();
            cursed = false;
        }
        if (pool.isEmpty()) {
            return null;
        }
        String id = pool.get(random.nextInt(pool.size()));
        int cap = Math.min(levelCap, Math.max(1, api.maxLevel(id)));
        int level = TreasureSpecialRolls.level(cap, random);
        ItemStack book = api.book(id, level);
        if (book == null) {
            return null;
        }
        payload.add(book);
        return new TreasureSpecial(TreasureSpecial.Kind.ENCHANT, api.name(id), level);
    }
}
