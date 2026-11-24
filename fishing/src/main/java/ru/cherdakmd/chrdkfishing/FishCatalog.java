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
    private static final List<Species> FISH = List.of(
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
        fish("willow_spirit","Ивовый дух",Rarity.LEGENDARY,true,500,10,19,20,34,35,49,50,70)
    );
    private static final Map<String, Species> BY_ID = FISH.stream().collect(Collectors.toUnmodifiableMap(Species::id, Function.identity()));
    private FishCatalog() { }
    public static List<Species> all() { return FISH; }
    public static Species byId(String id) { return id == null ? null : BY_ID.get(id); }
    public static List<Species> byRarity(Rarity rarity) {
        return FISH.stream().filter(fish -> fish.rarity() == rarity).toList();
    }
}
