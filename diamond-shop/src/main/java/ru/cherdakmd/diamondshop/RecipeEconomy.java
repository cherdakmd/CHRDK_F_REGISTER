package ru.cherdakmd.diamondshop;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Нижняя стоимость приобретения по проверенным vanilla-цепочкам, без оценки труда/топлива. */
final class RecipeEconomy {
    /** Точные доли: цикл слиток → 9 самородков → слиток не теряет десятитысячные. */
    record Cost(BigInteger numerator, BigInteger denominator) implements Comparable<Cost> {
        Cost {
            if (numerator.signum()<0 || denominator.signum()<=0) throw new IllegalArgumentException("cost");
            var gcd=numerator.gcd(denominator);numerator=numerator.divide(gcd);denominator=denominator.divide(gcd);
        }
        static Cost of(double value) { return decimal(BigDecimal.valueOf(value)); }
        static Cost decimal(BigDecimal value) {
            return value.scale()<0 ? new Cost(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())),BigInteger.ONE)
                    : new Cost(value.unscaledValue(),BigInteger.TEN.pow(value.scale()));
        }
        Cost add(Cost other) { return new Cost(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),denominator.multiply(other.denominator)); }
        Cost multiply(int count) { return new Cost(numerator.multiply(BigInteger.valueOf(count)),denominator); }
        Cost multiply(Cost other) { return new Cost(numerator.multiply(other.numerator),denominator.multiply(other.denominator)); }
        Cost divide(int count) { return new Cost(numerator,denominator.multiply(BigInteger.valueOf(count))); }
        Cost min(Cost other) { return compareTo(other)<=0?this:other; }
        public int compareTo(Cost other) { return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)); }
    }
    private static final Cost ZERO = Cost.of(0);
    record Recipe(String output, int count, Map<String, Integer> inputs) { }
    static final List<Recipe> RECIPES = recipes();
    private RecipeEconomy() { }

    /** Релаксация учитывает промежуточные материалы, которых нет на витрине. */
    static Map<String, Cost> costs(Map<String, Cost> purchases) {
        var costs = new HashMap<>(purchases);
        // В этих цепочках нет размножающих циклов. Простой путь не длиннее числа рецептов.
        for (int pass = 0; pass <= RECIPES.size(); pass++) {
            boolean changed = false;
            for (var recipe : RECIPES) {
                Cost total = ZERO;
                for (var input : recipe.inputs().entrySet()) {
                    var cost = costs.get(input.getKey());
                    if (cost == null) { total = null; break; }
                    total = total.add(cost.multiply(input.getValue()));
                }
                if (total == null) continue;
                var unit = total.divide(recipe.count());
                var previous = costs.get(recipe.output());
                if (previous == null || unit.compareTo(previous) < 0) {
                    costs.put(recipe.output(), unit); changed = true;
                }
            }
            if (!changed) break;
        }
        return Map.copyOf(costs);
    }

    /** Только рецептурные пути: собственная цена товара не ограничивает добытое сырьё. */
    static Map<String, Cost> recipeCosts(Map<String, Cost> purchases) {
        var all = costs(purchases);
        var derived = new HashMap<String, Cost>();
        for (var recipe : RECIPES) {
            Cost total = ZERO;
            for (var input : recipe.inputs().entrySet()) {
                var cost = all.get(input.getKey());
                if (cost == null) { total = null; break; }
                total = total.add(cost.multiply(input.getValue()));
            }
            if (total != null) derived.merge(recipe.output(),
                    total.divide(recipe.count()), Cost::min);
        }
        return Map.copyOf(derived);
    }

    static Cost unit(long price, int batch) { return Cost.of(price).divide(batch); }
    static Cost withSpread(Cost cost, double spread) {
        return cost.multiply(Cost.decimal(BigDecimal.ONE.subtract(BigDecimal.valueOf(spread).divide(BigDecimal.valueOf(200)))));
    }
    static long cap(Cost cost, int batch, double spread) {
        var total=withSpread(cost,spread).multiply(batch);
        return total.numerator().divide(total.denominator()).min(BigInteger.valueOf(WholeTrades.MAX)).longValueExact();
    }
    static int batch(Cost minimumBid) {
        if (minimumBid.numerator().signum()==0) return 0;
        var units=minimumBid.denominator().add(minimumBid.numerator()).subtract(BigInteger.ONE).divide(minimumBid.numerator());
        for (int size:new int[]{1,2,4,8,16,32,64,128,256,512,1024,2304})
            if (units.compareTo(BigInteger.valueOf(size))<=0) return size;
        return 0;
    }
    private static List<Recipe> recipes() {
        var r = new ArrayList<Recipe>();
        for (String[] pair : new String[][]{{"COAL","COAL_BLOCK"},{"COPPER_INGOT","COPPER_BLOCK"},
                {"IRON_INGOT","IRON_BLOCK"},{"GOLD_INGOT","GOLD_BLOCK"},{"LAPIS_LAZULI","LAPIS_BLOCK"},
                {"REDSTONE","REDSTONE_BLOCK"},{"EMERALD","EMERALD_BLOCK"},{"SLIME_BALL","SLIME_BLOCK"}}) {
            add(r,pair[1],1,pair[0],9); add(r,pair[0],9,pair[1],1);
        }
        add(r,"HONEY_BLOCK",1,"HONEY_BOTTLE",4);
        // Для обратного крафта нужны пустые бутылки: их стоимость намеренно не добавляется.
        add(r,"HONEY_BOTTLE",4,"HONEY_BLOCK",1);
        add(r,"GOLD_NUGGET",9,"GOLD_INGOT",1);
        add(r,"GOLD_INGOT",1,"GOLD_NUGGET",9);
        for (String wood : List.of("OAK","SPRUCE","BIRCH","JUNGLE","ACACIA","DARK_OAK","MANGROVE","CHERRY")) {
            add(r,"PLANKS",4,wood+"_LOG",1); add(r,"CHARCOAL",1,wood+"_LOG",1);
        }
        add(r,"PLANKS",4,"CRIMSON_STEM",1); add(r,"PLANKS",4,"WARPED_STEM",1);
        add(r,"STICK",4,"PLANKS",2); add(r,"STICK",1,"BAMBOO",2);
        add(r,"WOOD_SLAB",6,"PLANKS",3); add(r,"CHEST",1,"PLANKS",8);
        for (String metal : List.of("IRON","GOLD","COPPER")) add(r,metal+"_INGOT",1,"RAW_"+metal,1);
        add(r,"STONE",1,"COBBLESTONE",1); add(r,"GLASS",1,"SAND",1);
        add(r,"SANDSTONE",1,"SAND",4); add(r,"CLAY_BALL",4,"CLAY",1);
        add(r,"BRICK",1,"CLAY_BALL",1); add(r,"TERRACOTTA",1,"CLAY",1);
        add(r,"PACKED_ICE",1,"ICE",9); add(r,"BLUE_ICE",1,"PACKED_ICE",9);
        add(r,"QUARTZ_BLOCK",1,"QUARTZ",4); add(r,"BLAZE_POWDER",2,"BLAZE_ROD",1);
        add(r,"LEATHER",1,"RABBIT_HIDE",4); add(r,"SUGAR",1,"SUGAR_CANE",1);
        add(r,"PAPER",3,"SUGAR_CANE",3); add(r,"BREAD",1,"WHEAT",3);
        add(r,"BOOK",1,"PAPER",3,"LEATHER",1);
        add(r,"BOOKSHELF",1,"PLANKS",6,"BOOK",3); add(r,"BOOK",3,"BOOKSHELF",1);
        add(r,"TORCH",4,"COAL",1,"STICK",1); add(r,"TORCH",4,"CHARCOAL",1,"STICK",1);
        add(r,"ARROW",4,"FLINT",1,"STICK",1,"FEATHER",1);
        add(r,"FIREWORK_ROCKET",3,"PAPER",1,"GUNPOWDER",1);
        add(r,"RAIL",16,"IRON_INGOT",6,"STICK",1);
        add(r,"POWERED_RAIL",6,"GOLD_INGOT",6,"STICK",1,"REDSTONE",1);
        add(r,"REDSTONE_TORCH",1,"REDSTONE",1,"STICK",1);
        add(r,"REPEATER",1,"STONE",3,"REDSTONE_TORCH",2,"REDSTONE",1);
        add(r,"COMPARATOR",1,"STONE",3,"REDSTONE_TORCH",3,"QUARTZ",1);
        add(r,"OBSERVER",1,"COBBLESTONE",6,"REDSTONE",2,"QUARTZ",1);
        add(r,"PISTON",1,"PLANKS",3,"COBBLESTONE",4,"IRON_INGOT",1,"REDSTONE",1);
        add(r,"STICKY_PISTON",1,"PISTON",1,"SLIME_BALL",1);
        add(r,"DAYLIGHT_DETECTOR",1,"GLASS",3,"QUARTZ",3,"WOOD_SLAB",3);
        add(r,"MAP",1,"PAPER",8,"COMPASS",1);
        add(r,"COMPASS",1,"IRON_INGOT",4,"REDSTONE",1);
        add(r,"CLOCK",1,"GOLD_INGOT",4,"REDSTONE",1);
        add(r,"HOPPER",1,"IRON_INGOT",5,"CHEST",1);
        add(r,"CAULDRON",1,"IRON_INGOT",7);
        add(r,"ANVIL",1,"IRON_BLOCK",3,"IRON_INGOT",4);
        add(r,"BREWING_STAND",1,"COBBLESTONE",3,"BLAZE_ROD",1);
        add(r,"ENCHANTING_TABLE",1,"OBSIDIAN",4,"DIAMOND",2,"BOOK",1);
        add(r,"GOLDEN_APPLE",1,"GOLD_INGOT",8,"APPLE",1);
        add(r,"GOLDEN_CARROT",1,"GOLD_NUGGET",8,"CARROT",1);
        add(r,"NETHERITE_INGOT",1,"NETHERITE_SCRAP",4,"GOLD_INGOT",4);
        add(r,"MAGMA_CREAM",1,"SLIME_BALL",1,"BLAZE_POWDER",1);
        add(r,"MAGMA_BLOCK",1,"MAGMA_CREAM",4);
        add(r,"PRISMARINE",1,"PRISMARINE_SHARD",4);
        add(r,"PRISMARINE_BRICKS",1,"PRISMARINE_SHARD",9);
        add(r,"SEA_LANTERN",1,"PRISMARINE_SHARD",4,"PRISMARINE_CRYSTALS",5);
        return List.copyOf(r);
    }
    private static void add(List<Recipe> r, String output, int count, Object... inputs) {
        var map = new LinkedHashMap<String,Integer>();
        for (int i=0;i<inputs.length;i+=2) map.put((String)inputs[i],(Integer)inputs[i+1]);
        r.add(new Recipe(output,count,Map.copyOf(map)));
    }
}
