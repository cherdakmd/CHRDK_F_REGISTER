package ru.cherdakmd.chrdkfishing;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static ru.cherdakmd.chrdkfishing.FishingModel.*;

/** Initial proposal: lengths and prices are game values, not ecological claims. */
public final class FishCatalog {
    private static Species fish(String id, String name, Rarity rarity, boolean fictional, long price, int... bounds) {
        if (bounds.length != 8) throw new IllegalArgumentException("lengths");
        return new Species(id, name, rarity, fictional, price, List.of(
                new Range(bounds[0], bounds[1]), new Range(bounds[2], bounds[3]),
                new Range(bounds[4], bounds[5]), new Range(bounds[6], bounds[7])));
    }
    private static final List<Species> BASE = List.of(
        fish("ruffe","Ёрш",Rarity.COMMON,false,20,5,9,10,14,15,19,20,25),
        fish("bleak","Уклейка",Rarity.COMMON,false,20,5,9,10,14,15,19,20,24),
        fish("roach","Плотва",Rarity.COMMON,false,25,8,14,15,24,25,34,35,45),
        fish("perch","Окунь",Rarity.COMMON,false,30,8,14,15,24,25,34,35,50),
        fish("silver_bream","Густера",Rarity.COMMON,false,30,10,16,17,25,26,34,35,45),
        fish("crucian_carp","Карась",Rarity.COMMON,false,30,10,16,17,25,26,34,35,45),
        fish("gudgeon","Пескарь",Rarity.COMMON,false,20,5,8,9,12,13,16,17,20),
        fish("bream","Лещ",Rarity.UNCOMMON,false,55,15,24,25,39,40,54,55,75),
        fish("ide","Язь",Rarity.UNCOMMON,false,60,12,19,20,34,35,49,50,65),
        fish("pike","Щука",Rarity.UNCOMMON,false,70,20,34,35,59,60,89,90,120),
        fish("burbot","Налим",Rarity.UNCOMMON,false,70,15,24,25,44,45,69,70,100),
        fish("vendace","Ряпушка",Rarity.UNCOMMON,false,65,8,12,13,19,20,26,27,35),
        fish("smelt","Корюшка",Rarity.UNCOMMON,false,55,8,12,13,19,20,26,27,35),
        fish("dace","Елец",Rarity.UNCOMMON,false,45,8,12,13,19,20,26,27,32),
        fish("tench","Линь",Rarity.UNCOMMON,false,55,10,17,18,29,30,44,45,60),
        fish("whitefish","Сиг",Rarity.RARE,false,120,15,24,25,39,40,54,55,75),
        fish("grayling","Хариус",Rarity.RARE,false,130,15,22,23,34,35,44,45,60),
        fish("trout","Форель",Rarity.RARE,false,145,15,24,25,39,40,59,60,80),
        fish("brown_trout","Кумжа",Rarity.RARE,false,160,20,29,30,49,50,69,70,100),
        fish("palia","Палия",Rarity.RARE,false,160,20,29,30,49,50,69,70,90),
        fish("zander","Судак",Rarity.RARE,false,130,20,29,30,49,50,69,70,100),
        fish("moon_perch","Лунный окунь",Rarity.RARE,true,220,8,14,15,24,25,34,35,50),
        fish("ladoga_lanternfish","Ладожский фонарник",Rarity.RARE,true,260,5,9,10,14,15,19,20,25),
        fish("lake_salmon","Озёрный лосось",Rarity.VERY_RARE,false,280,25,39,40,59,60,84,85,110),
        fish("atlantic_salmon","Сёмга",Rarity.VERY_RARE,false,300,25,39,40,59,60,84,85,120),
        fish("arctic_char","Арктический голец",Rarity.VERY_RARE,false,250,15,24,25,39,40,59,60,80),
        fish("sturgeon","Осётр",Rarity.VERY_RARE,false,350,30,49,50,79,80,119,120,160),
        fish("northern_crystal","Северный кристальник",Rarity.VERY_RARE,true,320,10,19,20,34,35,49,50,70),
        fish("shadow_burbot","Теневой налим",Rarity.VERY_RARE,true,350,15,24,25,44,45,69,70,100),
        fish("willow_spirit","Ивовый дух",Rarity.LEGENDARY,true,500,10,19,20,34,35,49,50,70),
        fish("rudd","Краснопёрка",Rarity.COMMON,false,25,8,14,15,22,23,32,33,45),
        fish("minnow","Гольян",Rarity.COMMON,false,20,4,6,7,9,10,12,13,16),
        fish("rotan","Ротан",Rarity.COMMON,false,25,6,9,10,15,16,21,22,30),
        fish("carp","Карп",Rarity.UNCOMMON,false,75,15,24,25,39,40,59,60,90),
        fish("chub","Голавль",Rarity.UNCOMMON,false,65,12,19,20,34,35,49,50,70),
        fish("sichel","Чехонь",Rarity.UNCOMMON,false,60,10,16,17,25,26,34,35,50),
        fish("asp","Жерех",Rarity.RARE,false,140,18,29,30,44,45,64,65,90),
        fish("eel","Угорь",Rarity.RARE,false,150,20,34,35,54,55,79,80,120),
        fish("sterlet","Стерлядь",Rarity.RARE,false,150,20,29,30,44,45,64,65,90),
        fish("arctic_cisco","Омуль",Rarity.RARE,false,150,15,24,25,39,40,54,55,75),
        fish("taimen","Таймень",Rarity.VERY_RARE,false,370,30,49,50,79,80,119,120,180),
        fish("nelma","Нельма",Rarity.VERY_RARE,false,360,25,39,40,59,60,89,90,130),
        fish("arctic_whitefish","Муксун",Rarity.VERY_RARE,false,300,18,29,30,44,45,64,65,90),
        fish("golden_tench","Золотой линь",Rarity.LEGENDARY,true,520,10,17,18,29,30,44,45,60),
        fish("aurora_salmon","Северное сияние",Rarity.LEGENDARY,true,550,25,39,40,59,60,84,85,120)
    );
    /** Each premium species is exactly ten times the matching existing species at the same size. */
    public record Premium(String id, String name, String referenceId) { }
    private static final List<Premium> PREMIUM = List.of(
            new Premium("abyss_whitefish", "Глубинный сиг", "whitefish"),
            new Premium("star_grayling", "Звёздный хариус", "grayling"),
            new Premium("crystal_trout", "Кристальная форель", "trout"),
            new Premium("royal_brown_trout", "Королевская кумжа", "brown_trout"),
            new Premium("azure_palia", "Лазурная палия", "palia"),
            new Premium("ancient_zander", "Древний судак", "zander"),
            new Premium("eclipse_perch", "Окунь затмения", "moon_perch"),
            new Premium("northern_lantern", "Северный фонарник", "ladoga_lanternfish"),
            new Premium("astral_salmon", "Астральный лосось", "lake_salmon"),
            new Premium("imperial_salmon", "Императорская сёмга", "atlantic_salmon"),
            new Premium("polar_char", "Полярный голец", "arctic_char"),
            new Premium("ancient_sturgeon", "Древний осётр", "sturgeon"),
            new Premium("ice_crystal", "Ледяной кристальник", "northern_crystal"),
            new Premium("midnight_burbot", "Полуночный налим", "shadow_burbot"),
            new Premium("willow_guardian", "Хранитель ивы", "willow_spirit"),
            new Premium("storm_asp", "Грозовой жерех", "asp"),
            new Premium("thunder_eel", "Громовой угорь", "eel"),
            new Premium("pearl_sterlet", "Жемчужная стерлядь", "sterlet"),
            new Premium("moon_cisco", "Лунный омуль", "arctic_cisco"),
            new Premium("ancient_taimen", "Древний таймень", "taimen"),
            new Premium("celestial_nelma", "Небесная нельма", "nelma"),
            new Premium("glacier_whitefish", "Ледниковый муксун", "arctic_whitefish"),
            new Premium("sun_tench", "Солнечный линь", "golden_tench"),
            new Premium("aurora_keeper", "Хранитель сияния", "aurora_salmon"),
            new Premium("aurora_dragonfish", "Дракон северных вод", "aurora_salmon"));
    private static final Map<String, String> PREMIUM_REFERENCES = PREMIUM.stream()
            .collect(Collectors.toUnmodifiableMap(Premium::id, Premium::referenceId));
    private static final List<Species> FISH = buildCatalog();
    private static List<Species> buildCatalog() {
        var all = new java.util.ArrayList<>(BASE);
        var baseById = BASE.stream().collect(Collectors.toUnmodifiableMap(Species::id, Function.identity()));
        for (var premium : PREMIUM) {
            var source = java.util.Objects.requireNonNull(baseById.get(premium.referenceId()), "premium reference");
            all.add(new Species(premium.id(), premium.name(), Rarity.MYTHIC, true,
                    Math.multiplyExact(source.basePrice(), 10), source.lengths()));
        }
        return List.copyOf(all);
    }
    public static List<Premium> premium() { return PREMIUM; }
    public static Species priceReference(String id) {
        var reference = PREMIUM_REFERENCES.get(id);
        return reference == null ? null : BY_ID.get(reference);
    }
    private static final Map<String, Species> BY_ID = FISH.stream().collect(Collectors.toUnmodifiableMap(Species::id, Function.identity()));
    private FishCatalog() { }
    public static List<Species> all() { return FISH; }
    public static Species byId(String id) { return id == null ? null : BY_ID.get(id); }
    public static List<Species> byRarity(Rarity rarity) {
        return FISH.stream().filter(fish -> fish.rarity() == rarity).toList();
    }
}
