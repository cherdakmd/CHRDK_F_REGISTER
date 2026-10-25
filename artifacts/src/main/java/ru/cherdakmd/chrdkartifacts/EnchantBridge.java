package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkenchants.EnchantDef;
import ru.cherdakmd.chrdkenchants.EnchantService;
import ru.cherdakmd.chrdkenchants.EnchantsPlugin;

/**
 * Мост к CHRDK_Enchants — единственное место модуля, которое грузит типы чар.
 * Аддон чар объявлен мягкой зависимостью: без него работают все 64 остальных
 * артефакта, а «Хартия чар» честно отвечает «Архив чар недоступен». Класс
 * грузится только в момент активации хартии, поэтому отсутствие JAR чар не
 * приводит к {@link NoClassDefFoundError} при включении плагина.
 */
final class EnchantBridge {

    /** Итог наката: имя чары для сообщения и уровень. */
    record Rolled(String name, int level) {
    }

    /** Итог попытки: отдельно «нет места» и «архив недоступен». */
    enum Status {OK, NO_ROOM, UNAVAILABLE}

    record Result(Status status, Rolled rolled) {

        static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null);
        }

        static Result noRoom() {
            return new Result(Status.NO_ROOM, null);
        }

        static Result ok(Rolled rolled) {
            return new Result(Status.OK, rolled);
        }
    }

    /** Установлен и включён ли аддон чар (только Bukkit — без типов чар). */
    static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("CHRDK_Enchants");
    }

    /**
     * Накатывает случайную кастомную чару второго ценового тира на предмет.
     *
     * <p>1.4.128: только светлые чары. «Хартия» — награда, и подсунуть ею
     * проклятие было бы обманом; проклятия выпадают лишь со стола чар.</p>
     */
    static Result roll(ItemStack item) {
        if (!available()) {
            return Result.unavailable();
        }
        EnchantsPlugin enchants = EnchantsPlugin.getPlugin(EnchantsPlugin.class);
        EnchantService service = enchants == null ? null : enchants.service();
        if (service == null) {
            return Result.unavailable();
        }
        EnchantService.RollResult rolled = service.rollOnto(item, 2, EnchantDef.Polarity.LIGHT);
        if (rolled == null) {
            return Result.noRoom();
        }
        String name = enchants.messages().raw("en." + rolled.def().id() + ".name");
        return Result.ok(new Rolled(ArtifactText.orFallback(name, rolled.def().id()), rolled.level()));
    }

    private EnchantBridge() {
    }
}
