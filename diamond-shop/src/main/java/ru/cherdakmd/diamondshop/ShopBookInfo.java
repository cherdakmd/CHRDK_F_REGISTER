package ru.cherdakmd.diamondshop;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;
import ru.cherdakmd.chrdkregister.api.ShopBooks;

/**
 * Русские названия и краткие описания кастомных чар для витрины {@code /shop}.
 *
 * <p>Справочник синхронизирован с {@code CHRDK_Enchants/messages.yml}: название
 * и первая фраза описания берутся оттуда, поэтому в магазине игрок видит
 * «Громобой» и понятное действие, а не технический id {@code thunderstrike}.
 * Таблица лежит в аддоне магазина и не меняет публичный Core API: книги по-прежнему
 * создаёт {@link ru.cherdakmd.chrdkregister.api.ShopBooks}, а сделка проверяется
 * по PDC и ключу товара, никогда по отображаемому имени.</p>
 *
 * <p>Проклятия выделены отдельно: в названии стоит ☠, описание красное, чтобы
 * игрок не купил штрафную чару по ошибке. Ванильные книги и неизвестные id
 * остаются как были — для них имя даёт сам {@link ShopBooks#displayName}.</p>
 */
public final class ShopBookInfo {

    /** Название, короткое описание и признак проклятия. */
    public record Label(String name, String description, boolean curse) {
        public Label {
            if (name == null || name.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException("book label");
            }
        }
    }

    private static final Map<String, Label> LABELS = Map.ofEntries(
            Map.entry("thunderstrike", new Label("Громобой", "При ближнем ударе: шанс 10/15/20% вызвать молнию. Дополнительный урон 4 ед.", false)),
            Map.entry("lifesteal", new Label("Вампиризм", "При ближнем ударе лечит на 10/15/20% итогового урона.", false)),
            Map.entry("chainspark", new Label("Цепная молния", "При ближнем ударе задевает ещё до 1/2 живых целей. Каждой — 40% итогового урона основного удара.", false)),
            Map.entry("bleeding", new Label("Кровоточность", "Ближний удар накладывает Иссушение I на 2/3/4 сек.", false)),
            Map.entry("paralyze", new Label("Паралич", "Ближний удар: шанс 10/14% сковать живую цель. Медлительность V на 1,5 сек. и Усталость IV на 2 сек.", false)),
            Map.entry("execution", new Label("Казнь", "Если до удара у цели не более 20% максимального HP, базовый урон удара умножается на 2/2,5.", false)),
            Map.entry("berserk", new Label("Берсерк", "При своём здоровье не выше 50%: +10/15/20% к удару. При здоровье выше половины бонус не действует.", false)),
            Map.entry("slayer", new Label("Истребитель", "После убийства с оружием в основной руке: Сила I на 6/10 сек.; не постоянный бонус оружия.", false)),
            Map.entry("souleater", new Label("Пожиратель душ", "После убийства: +1/2 ед. сытости и +1/2 ед. HP.", false)),
            Map.entry("stormarrow", new Label("Грозовая стрела", "При попадании стрелы — молния и урон по области. Живые цели в ±2 блоках получают 2/3/4 ед. урона.", false)),
            Map.entry("explosive", new Label("Взрывная стрела", "При попадании стрелы создаёт взрыв силой 0,8/1,6. Без поджога и разрушения блоков.", false)),
            Map.entry("sniper", new Label("Снайперская стрела", "Урон стрелы: +3/5/7% за каждые 10 блоков от старта. Бонус растёт плавно; потолок +45/60/75%.", false)),
            Map.entry("frostarrow", new Label("Морозная стрела", "Попадание: Медлительность I на 4/6 сек. Добавляет замерзание, не выше предела цели.", false)),
            Map.entry("tracer", new Label("Следящая стрела", "Попавшая стрела подсвечивает живую цель на 10 сек.", false)),
            Map.entry("headhunter", new Label("Охотник за головами", "Стрелы наносят игрокам на 15% больше урона. По мобам эта прибавка не действует.", false)),
            Map.entry("retribution", new Label("Возмездие", "Возвращает 8/12/16% итогового урона ближнему атакующему.", false)),
            Map.entry("laststand", new Label("Последний шанс", "После несмертельного удара осталось не более 3 ед. HP: Поглощение I и Сопротивление I на 25 сек.", false)),
            Map.entry("fortified", new Label("Крепость", "Снижает входящий урон на 6/9/12% до остальных расчётов. Берётся лучший уровень на надетой броне.", false)),
            Map.entry("lightningrod", new Label("Громоотвод", "Отменяет урон типа Молния и даёт Скорость I на 10 сек.", false)),
            Map.entry("phoenix", new Label("Феникс", "При готовности отменяет смертельный урон. Устанавливает здоровье до 8 ед. (4 сердца), тушит огонь.", false)),
            Map.entry("iceward", new Label("Страж льдов", "Ближнему атакующему: Медлительность I на 2/3 сек.", false)),
            Map.entry("truesight", new Label("Орлиный глаз", "Каждые 4 сек. подсвечивает невидимых живых существ и монстров.", false)),
            Map.entry("seer", new Label("Провидец", "Ночное зрение I, пока шлем надет. Постоянная аура без мигания; после снятия источник исчезает.", false)),
            Map.entry("swiftfeet", new Label("Стремительные", "Во время спринта поддерживает Скорость I/II. При обычной ходьбе этот источник скорости не действует.", false)),
            Map.entry("froggy", new Label("Лягушачьи лапки", "Пока ботинки надеты, поддерживает Прыгучесть I/II. Не даёт полёт или отдельную защиту от падения.", false)),
            Map.entry("lavawalker", new Label("Лавоступ", "Лава под ногами превращается в корку 3×3 примерно на 10 сек.", false)),
            Map.entry("autosmelt", new Label("Автоплавка", "Железная/золотая/медная руда: сырьё сразу в слитки.", false)),
            Map.entry("veinminer", new Label("Жилодробитель", "Присев, разрушьте руду: до 6/10/14 блоков той же жилы.", false)),
            Map.entry("excavator", new Label("Дробитель", "Присев, ломайте блок: область 3×3 / 3×3×2 по направлению взгляда.", false)),
            Map.entry("midas", new Label("Рука Мидаса", "Шанс 10% добавить одну базовую партию минерала. Только природная руда, без Шёлкового касания.", false)),
            Map.entry("keen_edge", new Label("Острый край", "Урон атаки оружия в основной руке: +4/6/8%.", false)),
            Map.entry("frenzy", new Label("Ярость", "При своём здоровье не выше 50%: +10/15/20% урона удара. Выше половины здоровья прибавки нет.", false)),
            Map.entry("plunder", new Label("Мародёр", "Расчёт добавки к дропу убитого: 10/15/20%. Дробная часть разыгрывается; стек не выше его максимума.", false)),
            Map.entry("duelist", new Label("Дуэлянт", "Ближние удары по игрокам сильнее на 8/12/16%.", false)),
            Map.entry("steady_aim", new Label("Верный прицел", "Урон выпущенных стрел увеличивается на 8/12/16%.", false)),
            Map.entry("quickdraw", new Label("Скорый выстрел", "Начальная скорость полёта стрелы: +4/6/8%. Не сокращает время натяжения лука или зарядки арбалета.", false)),
            Map.entry("bulwark", new Label("Оплот", "Атрибут брони надетой части: +5/7/9%. Не равно такому же проценту снижения любого урона.", false)),
            Map.entry("second_skin", new Label("Вторая кожа", "Входящий урон снижается на 4/6/8% до остальных расчётов.", false)),
            Map.entry("vitality", new Label("Живучесть", "Восстанавливает 0,5/0,75/1 ед. HP за секундный пульс.", false)),
            Map.entry("grounded", new Label("Устойчивость", "Урон именно от падения снижается на 10/15/20%.", false)),
            Map.entry("lightfoot", new Label("Лёгкая поступь", "После убийства в этих ботинках: Скорость I/II/II. Длительность по уровню чары: 4/6/8 сек.", false)),
            Map.entry("featherstep", new Label("Пёрышко", "Скорость передвижения в надетых ботинках: +4/6/8%.", false)),
            Map.entry("prospector", new Label("Старатель", "С инструментом в основной руке поддерживает Спешку I/II/II. III уровень чары не даёт Спешку III.", false)),
            Map.entry("fortunate", new Label("Золотая жила", "Шанс 10/18% добавить базовую партию минерала. Только природная руда, без Шёлкового касания.", false)),
            Map.entry("scholar", new Label("Книжник", "Получаемый ванильный опыт: +5/8/11% с каждой экипированной копии.", false)),
            Map.entry("crippling_edge", new Label("Подрезание", "Замедление I на 2/3/4 сек., шанс 20/25/30%.", false)),
            Map.entry("sapping_edge", new Label("Изнуряющий клинок", "Слабость I на 2/3 сек., шанс 15/20%. Только враждебные мобы: не игроки, питомцы, NPC или боссы.", false)),
            Map.entry("pinning_shot", new Label("Сковывающий выстрел", "Замедление I на 2/3/4 сек., шанс 20/25/30%.", false)),
            Map.entry("sapping_shot", new Label("Изнуряющий выстрел", "Слабость I на 2/3 сек., шанс 15/20%. Только враждебные мобы: не игроки, питомцы, NPC или боссы.", false)),
            Map.entry("briar_guard", new Label("Терновый заслон", "Замедление I атакующему мобу на 2/3 сек., шанс 15/20%.", false)),
            Map.entry("faltering_guard", new Label("Ослабляющий заслон", "Слабость I атакующему мобу на 2/3 сек., шанс 15/20%.", false)),
            Map.entry("provoking_strike", new Label("Провоцирующий удар", "После разрешённого ближнего удара: шанс 15/20% переключить цель моба на себя.", false)),
            Map.entry("signal_guard", new Label("Сигнальный заслон", "После разрешённого попадания моба: шанс 15/20% подсветить его на 2/3 сек.", false)),
            Map.entry("careful_work", new Label("Бережная работа", "Шанс 10/20/30% сберечь 1 ед. износа после разбитого блока.", false)),
            Map.entry("garden_step", new Label("Садовая поступь", "Не вытаптываете грядки в этих ботинках. Не ускоряет урожай и не даёт обходить защиту блоков.", false)),
            Map.entry("air_reserve", new Label("Запас воздуха", "При падении воздуха до 40: +40/60 ед. (базово 2/3 сек.).", false)),
            Map.entry("quiet_step", new Label("Тихий шаг", "При обычной ходьбе не создаётся вибрация шага для скалка.", false)),
            Map.entry("tool_safeguard", new Label("Предохранитель инструмента", "При остатке ≤2 ед. прочности запрещает разрушать блоки.", false)),
            Map.entry("frost_resistance", new Label("Морозостойкость", "На 25/50% меньше урона замерзания в рыхлом снегу у ног.", false)),
            Map.entry("pve_anchor", new Label("PvE-якорь", "Ботинки I/II: −15/30% отбрасывания от враждебного моба в ближнем бою.", false)),
            Map.entry("harvest_replant", new Label("Бережная пересадка", "Мотыга I: после сбора зрелой культуры сажает росток за одно семя из инвентаря.", false)),
            Map.entry("dull_blade", new Label("Тупое лезвие", "Урон атаки оружия в основной руке: −5/8/11%. Минус к атрибуту, а не отдельный периодический урон.", true)),
            Map.entry("clumsy_swing", new Label("Неуклюжий замах", "Скорость атаки оружия в основной руке: −8/12/16%.", true)),
            Map.entry("brittle", new Label("Хрупкость", "Износ при повреждении предмета: +15/25/35%.", true)),
            Map.entry("weak_draw", new Label("Слабый натяг", "Урон выпущенных стрел: −8/12/16%. Скорость полёта эта чара не меняет.", true)),
            Map.entry("sluggish_shot", new Label("Вялый выстрел", "Начальная скорость стрелы уменьшается на 10/20%. Не увеличивает время натяжения или зарядки оружия.", true)),
            Map.entry("thin_padding", new Label("Тонкая подкладка", "Атрибут брони надетой части: −4/6/8%. Это потеря брони, не прямой процент входящего урона.", true)),
            Map.entry("exposed", new Label("Открытость", "Входящий урон увеличивается на 8/12/16%. Берётся лучший уровень на надетой броне, не сумма частей.", true)),
            Map.entry("heavy_hull", new Label("Тяжёлый корпус", "Скорость передвижения с надетой частью: −10/15/20%. Работает как отрицательный атрибут экипировки.", true)),
            Map.entry("fire_prone", new Label("Боязнь огня", "Огонь, лава и горячий пол: +15/25/35% урона.", true)),
            Map.entry("fragile_bones", new Label("Хрупкие кости", "Урон от падения увеличивается на 10/15/20%. На другие причины урона эта прибавка не действует.", true)),
            Map.entry("dull_pick", new Label("Тупой резец", "С киркой в основной руке поддерживает Усталость I/II/II. III уровень чары не даёт Усталость III.", true)),
            Map.entry("jinxed", new Label("Порча", "При добыче шанс 8/14/20% потерять предметный дроп блока.", true)),
            Map.entry("gluttony", new Label("Обжорство", "Каждую секунду добавляет 0,05/0,0875/0,125 истощения.", true)),
            Map.entry("slow_learner", new Label("Тугодум", "Ванильный получаемый опыт: −10/18/25% за копию на экипировке.", true)),
            Map.entry("cursed_blood", new Label("Проклятая кровь", "Раз в 5 сек. шанс 25/50/75% получить 1 ед. урона. Проверяется, только пока здоровье больше 1 ед.", true))
    );

    private ShopBookInfo() {
    }

    /** Запись справочника; {@code null} для ванильной или неизвестной чары. */
    public static Label label(String id) {
        return id == null ? null : LABELS.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** Русское имя для поиска {@code /shop find}; {@code null} для ванильных книг. */
    public static String searchName(ShopBookKey.Spec spec) {
        if (spec == null || !spec.custom()) {
            return null;
        }
        Label label = label(spec.id());
        return label == null ? null : label.name();
    }

    /** Имя книги в витрине, в окне количества и в выданном предмете. */
    public static Component displayName(ShopBookKey.Spec spec) {
        if (spec == null) {
            return null;
        }
        if (!spec.custom()) {
            return ShopBooks.displayName(spec); // ванильные книги остаются как были
        }
        Label label = label(spec.id());
        if (label == null) {
            return ShopBooks.displayName(spec); // неизвестный id: не подменяем данные
        }
        String prefix = label.curse() ? "☠ Книга проклятия: " : "✦ Книга чар: ";
        return Component.text(prefix + label.name() + " " + roman(spec.level()),
                label.curse() ? NamedTextColor.DARK_RED : NamedTextColor.LIGHT_PURPLE);
    }

    /** Одна строка описания в лоре иконки витрины; {@code null} для ванильных книг. */
    public static Component description(ShopBookKey.Spec spec) {
        if (spec == null || !spec.custom()) {
            return null;
        }
        Label label = label(spec.id());
        if (label == null) {
            return null;
        }
        String text = label.curse() ? "✶ Проклятие: " + label.description()
                : "✶ " + label.description();
        return Component.text(text, label.curse() ? NamedTextColor.RED : NamedTextColor.GRAY);
    }

    private static String roman(int level) {
        return switch (level) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> "I";
        };
    }
}
