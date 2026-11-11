package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Порционные эффекты катастроф. Все изменения блоков проходят через {@link #set}
 * и снимок {@link DisasterRestore}; нативные взрывы, entity rain и физический
 * дроп намеренно не используются. Один тик ограничен и по блокам, и по игрокам.
 */
public final class DisasterEffects {

    private static final String BYPASS = "chrdkdisasters.bypass";
    private static final int HARD_PARTICLE_BUDGET = 1800;
    private static final Set<Material> DESTRUCTIBLE = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.SAND,
            Material.RED_SAND, Material.GRAVEL, Material.CLAY, Material.MUD,
            Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW,
            Material.ICE, Material.PACKED_ICE, Material.STONE,
            Material.COBBLESTONE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.DEEPSLATE,
            Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE,
            Material.MOSS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.BASALT, Material.BLACKSTONE, Material.END_STONE,
            Material.SANDSTONE, Material.RED_SANDSTONE, Material.TERRACOTTA,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH, Material.FIRE,
            Material.WATER, Material.LAVA);

    private final DisastersPlugin plugin;
    private int tickBlockChanges;

    public DisasterEffects(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Экранное предупреждение только для находящихся рядом, не глобальный title. */
    public void warn(DisasterRun run, Location center) {
        if (center == null || !plugin.settings().cataclysm().titles()) {
            return;
        }
        String title = plugin.messages().plain(run.kind().titleKey());
        String subtitle = plugin.messages().plain("cataclysm-warning",
                "seconds", run.secondsLeft(), "level", run.level());
        forNearby(center, plugin.settings().effectRadius(), player ->
                player.sendTitle("§c⚠ " + title, "§e" + subtitle, 10, 50, 15));
    }

    /** Предупреждение и обратный отсчёт не меняют блоки и не наносят урон. */
    public void warning(DisasterRun run, int step) {
        World world = org.bukkit.Bukkit.getWorld(run.world());
        if (world == null) {
            return;
        }
        Location center = new Location(world, run.x(), run.y(), run.z());
        if (step == 0) {
            warn(run, center);
        }
        ring(center, plugin.settings().effectRadius(), 80, Particle.END_ROD, 0.25, 0.0);
        if (plugin.settings().cataclysm().titles()) {
            String text = plugin.messages().plain("cataclysm-countdown",
                    "seconds", run.secondsLeft());
            forNearby(center, plugin.settings().effectRadius(), player ->
                    player.sendActionBar(net.kyori.adventure.text.Component.text("§e⚠ " + text)));
        }
    }

    /** @return число новых позиций в снимке, изменённых этим шагом. */
    public int active(DisasterRun run) {
        World world = org.bukkit.Bukkit.getWorld(run.world());
        if (world == null) {
            return 0;
        }
        int before = plugin.restore().size();
        tickActive(run, new Location(world, run.x(), run.y(), run.z()));
        return plugin.restore().size() - before;
    }

    /** Видимое затухание: никаких новых разрушений и урона. */
    public void fading(DisasterRun run) {
        World world = org.bukkit.Bukkit.getWorld(run.world());
        if (world == null) {
            return;
        }
        Location center = new Location(world, run.x(), run.y(), run.z());
        particles(center.clone().add(0, 2, 0), Particle.CLOUD, 80,
                plugin.settings().effectRadius() * 0.45, 0.01);
    }

    /** Один активный шаг. Метод вызывается строго на главном потоке. */
    private void tickActive(DisasterRun run, Location center) {
        tickBlockChanges = 0;
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        DisastersConfig config = plugin.settings();
        int radius = Math.max(2, config.effectRadius());
        int level = run.level();
        int step = run.activeStep();
        if (config.playerEffectsEnabled()) {
            DisasterFx.apply(world, center, radius, run.kind().effect(),
                    Math.min(4, level - 1),
                    Math.max(40, run.plan().tickSeconds() * 25));
        }
        if (config.soundsEnabled()) {
            DisasterFx.play(world, center, config.soundFor(run.kind()),
                    config.soundVolume(), config.soundPitch());
        }

        switch (run.kind()) {
            case DUST_STORM -> dustStorm(center, radius, level, step);
            case METEOR_SHOWER -> meteorShower(center, radius, level, step);
            case EARTHQUAKE -> earthquake(center, radius, level, step);
            case TORNADO -> tornado(center, radius, level, step);
            case FLOOD -> flood(center, radius, level, step);
            case BLIZZARD -> blizzard(center, radius, level, step);
        }
        if (terrainEnabled() && config.terrain().collapses() && run.kind() != DisasterKind.EARTHQUAKE) {
            collapseNear(center, Math.min(6, 1 + level));
        }
        impactPlayers(run, center, radius);
    }

    public void clearEffects(DisasterRun run) {
        World world = org.bukkit.Bukkit.getWorld(run.world());
        if (world == null) {
            return;
        }
        Location center = new Location(world, run.x(), run.y(), run.z());
        DisasterFx.clear(world, center, plugin.settings().effectRadius(), run.kind().effect());
    }

    private void dustStorm(Location center, int radius, int level, int step) {
        ring(center, radius, Math.min(HARD_PARTICLE_BUDGET, 180 + level * 45),
                Particle.CLOUD, 1.4, 0.05);
        particles(center.clone().add(0, 2, 0), Particle.CAMPFIRE_COSY_SMOKE,
                55 + level * 15, radius * 0.55, 2.5, 0.04);
        // Видимые песчаные струи движутся по тангенсу, без создания entity.
        double angle = step * 0.42;
        for (int arm = 0; arm < 5; arm++) {
            double a = angle + arm * Math.PI * 2 / 5;
            Location at = radial(center, radius * 0.7, a);
            particles(at.add(0, 1.5, 0), Particle.DUST, 24, 1.2, 1.0,
                    new Particle.DustOptions(Color.fromRGB(194, 154, 86), 1.8f));
        }
        if (terrainEnabled()) {
            // Буря снимает почву и растительность, а обнажённые слои заносит песком.
            int columns = 8 + level * 7;
            for (int i = 0; i < columns && !budgetExhausted(); i++) {
                Block top = surface(randomAround(center, radius));
                if (top == null || !isTerrain(top.getType())) {
                    continue;
                }
                int depth = plugin.settings().terrainDepth(1 + level / 2);
                excavateColumn(top, depth, Material.SAND, 0.42);
                if (i % 5 == 0 && top.getRelative(0, 1, 0).getType().isAir()) {
                    set(top.getRelative(0, 1, 0), Material.SAND);
                }
            }
        }
    }

    private void meteorShower(Location center, int radius, int level, int step) {
        World world = center.getWorld();
        int meteors = Math.min(5, 1 + level / 2);
        for (int i = 0; i < meteors; i++) {
            double angle = step * 0.71 + i * 2.17;
            Location impact = radial(center, radius * (0.25 + 0.55 * random().nextDouble()), angle);
            Block ground = surface(impact);
            if (ground == null) {
                continue;
            }
            impact = ground.getLocation().add(0.5, 1, 0.5);
            trail(impact.clone().add(radius * 0.35, 12 + level * 2, -radius * 0.25), impact);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1);
            particles(impact, Particle.FLAME, 45 + level * 8, 2.0, 0.08);
            particles(impact, Particle.LAVA, 12 + level * 3, 1.2, 0.0);
            impactSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.65f);
            if (terrainEnabled()) {
                crater(ground.getLocation(), Math.min(8, 2 + level));
                if (plugin.settings().terrain().wildfire()) {
                    igniteAround(impact, level);
                }
            }
        }
    }

    private void earthquake(Location center, int radius, int level, int step) {
        World world = center.getWorld();
        int waves = Math.min(3, 1 + level / 2);
        for (int wave = 0; wave < waves; wave++) {
            double distance = ((step * 2.4 + wave * radius / waves) % Math.max(4, radius));
            seismicRing(center, distance, level);
        }
        impactSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.55f);
        if (!terrainEnabled()) {
            return;
        }
        int faults = Math.min(3, 1 + level / 2);
        int length = Math.min(radius, 6 + level * 4);
        int width = Math.min(3, 1 + level / 2);
        for (int fault = 0; fault < faults && !budgetExhausted(); fault++) {
            double angle = step * 0.11 + fault * Math.PI / faults;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            for (int i = -length; i <= length && !budgetExhausted(); i++) {
                int bend = (int) Math.round(Math.sin(i * 0.55 + step * 0.17 + fault) * 2.5);
                for (int side = -width; side <= width && !budgetExhausted(); side++) {
                    int x = center.getBlockX() + (int) Math.round(dx * i - dz * (bend + side));
                    int z = center.getBlockZ() + (int) Math.round(dz * i + dx * (bend + side));
                    Block top = surface(new Location(world, x, center.getY(), z));
                    if (top == null || !isTerrain(top.getType())) {
                        continue;
                    }
                    int edgeFade = Math.abs(i) / Math.max(2, length);
                    int depth = plugin.settings().terrainDepth(
                            Math.max(1, 2 + level - Math.abs(side) - edgeFade));
                    excavateColumn(top, depth, Material.AIR, 0);
                    Block lower = world.getBlockAt(x, top.getY() - depth - 1, z);
                    if (level >= 2 && (i + side + fault) % 5 == 0 && isTerrain(lower.getType())) {
                        set(lower, level >= 4 && i % 3 == 0 ? Material.LAVA : Material.MAGMA_BLOCK);
                    }
                    if (plugin.settings().terrain().collapses() && (i + side) % 4 == 0) {
                        collapseNear(top.getLocation(), 1 + level / 2);
                    }
                }
            }
        }
    }

    private void tornado(Location center, int radius, int level, int step) {
        int height = Math.min(32, 12 + level * 4);
        double travel = step * 0.23;
        Location eye = radial(center, radius * 0.45, travel * 0.16);
        int particleCount = 0;
        for (int y = 0; y < height && particleCount < HARD_PARTICLE_BUDGET; y++) {
            double spiralRadius = 1.2 + y * 0.12 + level * 0.2;
            for (int arm = 0; arm < 6 && particleCount < HARD_PARTICLE_BUDGET; arm++) {
                double a = step * 0.52 + y * 0.38 + arm * Math.PI / 3;
                Location at = eye.clone().add(Math.cos(a) * spiralRadius, y, Math.sin(a) * spiralRadius);
                eye.getWorld().spawnParticle(y % 4 == 0 ? Particle.CLOUD : Particle.CAMPFIRE_COSY_SMOKE,
                        at, 1, 0, 0, 0, 0);
                particleCount++;
            }
        }
        ring(eye, radius, 90 + level * 20, Particle.SWEEP_ATTACK, 0.2, 0.0);
        if (terrainEnabled()) {
            Block eyeSurface = surface(eye);
            if (eyeSurface != null) {
                excavateDisc(eyeSurface.getLocation(), Math.min(4, 1 + level / 2),
                        plugin.settings().terrainDepth(1 + level), Material.AIR, 0);
            }
            for (int i = 0; i < 16 + level * 10 && !budgetExhausted(); i++) {
                Block top = surface(randomAround(eye, radius * 0.7));
                if (top != null && isTerrain(top.getType())) {
                    org.bukkit.block.data.BlockData debris = top.getBlockData().clone();
                    excavateColumn(top, plugin.settings().terrainDepth(1 + level / 2), Material.AIR, 0);
                    particles(top.getLocation().add(0.5, 0.5, 0.5),
                            Particle.BLOCK, 12, 0.65, 0.16, debris);
                }
            }
        }
    }

    private void flood(Location center, int radius, int level, int step) {
        particles(center.clone().add(0, 5, 0), Particle.RAIN,
                300 + level * 60, radius * 0.65, 0.15);
        ring(center, Math.min(radius, step * 1.8), 130 + level * 20,
                Particle.SPLASH, 0.35, 0.0);
        if (!terrainEnabled()) {
            return;
        }
        int front = Math.min(radius, 2 + step * (1 + level / 2));
        int samples = Math.min(160, 32 + level * 20);
        for (int i = 0; i < samples && !budgetExhausted(); i++) {
            Location at = radial(center, front, i * Math.PI * 2 / samples + step * 0.08);
            Block top = surface(at);
            if (top == null || !isTerrain(top.getType())) {
                continue;
            }
            set(top, i % 3 == 0 ? Material.CLAY : Material.SAND);
            Block water = top.getRelative(0, 1, 0);
            int depth = 1 + (level >= 3 ? 1 : 0) + (level >= 5 ? 1 : 0);
            for (int y = 0; y < depth; y++) {
                if (water.getRelative(0, y, 0).getType().isAir()) {
                    set(water.getRelative(0, y, 0), Material.WATER);
                }
            }
        }
        // Поток промывает новые русла, а не только ставит воду на ровную поверхность.
        for (int channel = 0; channel < 4 + level * 2 && !budgetExhausted(); channel++) {
            Block top = surface(randomAround(center, Math.max(2, front)));
            if (top == null || !isTerrain(top.getType())) {
                continue;
            }
            int depth = plugin.settings().terrainDepth(1 + level / 2);
            excavateColumn(top, depth, Material.WATER, 1.0);
            if (isTerrain(top.getRelative(1, 0, 0).getType())) {
                set(top.getRelative(1, 0, 0), Material.CLAY);
            }
            if (isTerrain(top.getRelative(-1, 0, 0).getType())) {
                set(top.getRelative(-1, 0, 0), Material.SAND);
            }
        }
    }

    private void blizzard(Location center, int radius, int level, int step) {
        particles(center.clone().add(0, 8, 0), Particle.SNOWFLAKE,
                350 + level * 75, radius * 0.65, 0.12);
        for (int arm = 0; arm < 4; arm++) {
            double a = step * 0.3 + arm * Math.PI / 2;
            Location at = radial(center, radius * 0.65, a).add(0, 1, 0);
            particles(at, Particle.ITEM_SNOWBALL, 22, 1.2, 0.08);
        }
        ring(center, radius * 0.8, 90, Particle.END_ROD, 0.15, 0.0);
        if (!terrainEnabled()) {
            return;
        }
        for (int i = 0; i < 18 + level * 9 && !budgetExhausted(); i++) {
            Block top = surface(randomAround(center, radius));
            if (top == null || !isTerrain(top.getType())) {
                continue;
            }
            if (i % 7 == 0) {
                // Морозобойные трещины и промёрзшая порода меняют сам рельеф.
                excavateColumn(top, plugin.settings().terrainDepth(1 + level / 3),
                        Material.PACKED_ICE, 0.35);
                set(top.getRelative(0, -1, 0), Material.PACKED_ICE);
                continue;
            }
            if (i % 5 == 0) {
                set(top, i % 10 == 0 ? Material.PACKED_ICE : Material.ICE);
            }
            int snowDepth = Math.min(4, 1 + level / 2);
            for (int y = 1; y <= snowDepth; y++) {
                Block above = top.getRelative(0, y, 0);
                if (above.getType().isAir()) {
                    set(above, level >= 3 && (i + y) % 6 == 0 ? Material.POWDER_SNOW : Material.SNOW_BLOCK);
                } else if (above.getType() == Material.WATER) {
                    set(above, level >= 4 ? Material.PACKED_ICE : Material.ICE);
                }
            }
            if (level >= 2 && i % 11 == 0) {
                int spike = Math.min(9, 2 + level);
                for (int y = 1; y <= spike; y++) {
                    if (top.getRelative(0, y, 0).getType().isAir()) {
                        set(top.getRelative(0, y, 0), Material.PACKED_ICE);
                    }
                }
            }
        }
    }

    /** Урон/подброс отделены от визуализации и полностью opt-in. */
    private void impactPlayers(DisasterRun run, Location center, int radius) {
        DisasterSettings.Cataclysm cataclysm = plugin.settings().cataclysm();
        if (!cataclysm.enabled()) {
            return;
        }
        forNearby(center, radius, player -> {
            if (cataclysm.titles() && run.activeStep() % 3 == 0) {
                String action = plugin.messages().plain("cataclysm-action-" + run.kind().id());
                player.sendActionBar(net.kyori.adventure.text.Component.text("§c⚠ " + action));
            }
            double distance = Math.sqrt(player.getLocation().distanceSquared(center));
            double proximity = Math.max(0.2, 1.0 - distance / Math.max(1.0, radius * 1.5));
            if (cataclysm.knockback()) {
                Vector direction = player.getLocation().toVector().subtract(center.toVector());
                direction.setY(0);
                if (direction.lengthSquared() < 0.001) {
                    direction = new Vector(1, 0, 0);
                }
                direction.normalize();
                if (run.kind() == DisasterKind.TORNADO) {
                    direction = new Vector(-direction.getZ(), 0, direction.getX());
                } else if (run.kind() == DisasterKind.EARTHQUAKE) {
                    direction.multiply(0.25);
                }
                double strength = Math.min(1.8, (0.18 + run.level() * 0.1) * proximity);
                player.setVelocity(player.getVelocity().add(direction.multiply(strength))
                        .add(new Vector(0, Math.min(0.9, 0.12 + run.level() * 0.08), 0)));
            }
            if (cataclysm.damage() && run.activeStep() % 2 == 0) {
                double base = switch (run.kind()) {
                    case DUST_STORM -> 0.5;
                    case METEOR_SHOWER, EARTHQUAKE -> 1.4;
                    case TORNADO, FLOOD -> 1.0;
                    case BLIZZARD -> 0.8;
                };
                player.damage(Math.min(cataclysm.maxDamagePerPulse(), base * run.level() * proximity));
            }
            if (run.kind() == DisasterKind.METEOR_SHOWER && cataclysm.damage()
                    && cataclysm.maxFireSeconds() > 0) {
                player.setFireTicks(Math.max(player.getFireTicks(), cataclysm.maxFireSeconds() * 20));
            }
            if (run.kind() == DisasterKind.BLIZZARD) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        60, Math.min(3, run.level() / 2), false, false, false));
            }
        });
    }

    private void forNearby(Location center, int radius, java.util.function.Consumer<Player> action) {
        if (center.getWorld() == null) {
            return;
        }
        int affected = 0;
        int limit = plugin.settings().cataclysm().maxAffectedPlayers();
        double radiusSquared = (double) radius * radius;
        for (Player player : center.getWorld().getPlayers()) {
            if (affected >= limit) {
                break;
            }
            if (!player.isOnline() || player.hasPermission(BYPASS)
                    || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR
                    || player.isInvulnerable()
                    || player.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            action.accept(player);
            affected++;
        }
    }

    private void impactSound(Location at, Sound sound, float volume, float pitch) {
        if (at.getWorld() != null && plugin.settings().soundsEnabled()) {
            at.getWorld().playSound(at, sound,
                    volume * plugin.settings().soundVolume(), pitch * plugin.settings().soundPitch());
        }
    }

    private boolean terrainEnabled() {
        DisastersConfig config = plugin.settings();
        return config.cataclysm().enabled() && config.terrain().enabled();
    }

    private boolean budgetExhausted() {
        return tickBlockChanges >= plugin.settings().blockBudget() || !plugin.restore().canRemember();
    }

    private static boolean isTerrain(Material type) {
        if (DESTRUCTIBLE.contains(type)) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_ORE") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_LEAVES") || name.endsWith("_SAPLING")
                || name.endsWith("_FLOWER") || name.endsWith("_MUSHROOM");
    }

    /** Выемка только природных блоков; set(...) повторно проверяет Claims/spawn. */
    private void excavateColumn(Block top, int depth, Material replacement, double replacementChance) {
        if (top == null) {
            return;
        }
        int cappedDepth = Math.min(24, Math.max(1, depth));
        for (int y = 0; y < cappedDepth && !budgetExhausted(); y++) {
            Block block = top.getRelative(0, -y, 0);
            if (isTerrain(block.getType())) {
                set(block, Material.AIR);
            }
        }
        if (replacement != Material.AIR && random().nextDouble() < replacementChance) {
            Block bottom = top.getRelative(0, -cappedDepth, 0);
            if (isTerrain(bottom.getType())) {
                set(bottom, replacement);
            }
        }
    }

    private void excavateDisc(Location at, int radius, int maxDepth,
                              Material replacement, double replacementChance) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (budgetExhausted()) {
                    return;
                }
                double distance = Math.sqrt(x * x + z * z);
                if (distance > radius) {
                    continue;
                }
                Block top = surface(at.clone().add(x, 0, z));
                if (top == null || !isTerrain(top.getType())) {
                    continue;
                }
                int depth = Math.max(1, (int) Math.ceil(maxDepth * (1 - distance / (radius + 1.0))));
                excavateColumn(top, depth, replacement, replacementChance);
            }
        }
    }

    /** Обвал нависающего грунта без FallingBlock и физического дропа. */
    private void collapseNear(Location center, int radius) {
        for (int i = 0; i < 3 + radius * 2 && !budgetExhausted(); i++) {
            Block top = surface(randomAround(center, Math.max(1, radius)));
            if (top == null || !isTerrain(top.getType())
                    || !top.getRelative(0, -1, 0).getType().isAir()) {
                continue;
            }
            org.bukkit.block.data.BlockData debris = top.getBlockData().clone();
            excavateColumn(top, 1 + radius, Material.AIR, 0);
            particles(top.getLocation().add(0.5, 0.5, 0.5), Particle.BLOCK,
                    10, 0.5, 0.12, debris);
        }
    }

    private void crater(Location at, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (budgetExhausted()) {
                    return;
                }
                double distance = Math.sqrt(x * x + z * z);
                if (distance > radius) {
                    continue;
                }
                Block top = surface(at.clone().add(x, 0, z));
                if (top == null || !isTerrain(top.getType())) {
                    continue;
                }
                int depth = plugin.settings().terrainDepth(
                        Math.max(1, (int) Math.ceil(radius - distance)));
                excavateColumn(top, depth, Material.AIR, 0);
                Block rim = top.getRelative(0, -depth, 0);
                if (isTerrain(rim.getType()) && random().nextDouble() < 0.38) {
                    set(rim, random().nextDouble() < 0.3 ? Material.MAGMA_BLOCK : Material.BLACKSTONE);
                }
            }
        }
    }

    private void igniteAround(Location impact, int level) {
        int fires = Math.min(32, level * 5);
        for (int i = 0; i < fires && !budgetExhausted(); i++) {
            Block top = surface(randomAround(impact, 4 + level * 2));
            if (top == null || !isTerrain(top.getType())) {
                continue;
            }
            Block above = top.getRelative(0, 1, 0);
            if (above.getType().isAir()) {
                set(above, Material.FIRE);
            }
            if (i % 4 == 0) {
                set(top, Material.BLACKSTONE);
            }
        }
    }

    private void seismicRing(Location center, double distance, int level) {
        int samples = Math.min(160, Math.max(16, (int) (distance * 8)));
        for (int i = 0; i < samples; i++) {
            Location at = radial(center, distance, i * Math.PI * 2 / samples);
            Block top = surface(at);
            if (top == null) {
                continue;
            }
            Location particleAt = top.getLocation().add(0.5, 1, 0.5);
            top.getWorld().spawnParticle(Particle.BLOCK, particleAt,
                    1, 0.1, 0.1, 0.1, 0, top.getBlockData());
            if (i % 12 == 0) {
                top.getWorld().spawnParticle(Particle.CRIT, particleAt, 2 + level);
            }
        }
    }

    private void trail(Location from, Location to) {
        int samples = 22;
        for (int i = 0; i <= samples; i++) {
            Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) samples));
            from.getWorld().spawnParticle(i % 3 == 0 ? Particle.FLAME : Particle.CAMPFIRE_COSY_SMOKE,
                    at, 2, 0.08, 0.08, 0.08, 0);
        }
    }

    private void ring(Location center, double radius, int count, Particle particle,
                      double height, double extra) {
        int capped = Math.min(HARD_PARTICLE_BUDGET, count);
        for (int i = 0; i < capped; i++) {
            Location at = radial(center, radius * (0.75 + random().nextDouble() * 0.25),
                    i * Math.PI * 2 / capped);
            at.add(0, random().nextDouble() * height, 0);
            center.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, extra);
        }
    }

    private void particles(Location at, Particle particle, int count, double spread, double extra) {
        at.getWorld().spawnParticle(particle, at, Math.min(HARD_PARTICLE_BUDGET, count),
                spread, Math.max(0.2, spread * 0.45), spread, extra);
    }

    private void particles(Location at, Particle particle, int count, double spread,
                           double extra, Object data) {
        at.getWorld().spawnParticle(particle, at, Math.min(HARD_PARTICLE_BUDGET, count),
                spread, Math.max(0.2, spread * 0.45), spread, extra, data);
    }

    private Location randomSurface(Location center, int radius) {
        Block top = surface(randomAround(center, radius));
        return top == null ? null : top.getLocation();
    }

    private Location randomAround(Location center, int radius) {
        double angle = random().nextDouble(Math.PI * 2);
        return radial(center, Math.sqrt(random().nextDouble()) * radius, angle);
    }

    private static Location radial(Location center, double radius, double angle) {
        return center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    /** Находит ближайшую непустую поверхность, не пробегая всю высоту мира. */
    private Block surface(Location at) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        World world = at.getWorld();
        int start = Math.min(world.getMaxHeight() - 2, at.getBlockY() + 12);
        int end = Math.max(world.getMinHeight() + 1, start - 40);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(at.getBlockX(), y, at.getBlockZ());
            if (!block.getType().isAir() && block.getType() != Material.FIRE
                    && block.getType() != Material.WATER && block.getType() != Material.LAVA) {
                return block;
            }
        }
        return null;
    }

    private void set(Block block, Material material) {
        if (block == null || block.getType() == material || tickBlockChanges >= plugin.settings().blockBudget()
                || !plugin.restore().canRemember() || plugin.protection().blocked(block.getLocation())) {
            return;
        }
        Material type = block.getType();
        // Хранилища и коренная порода не трогаем даже при opt-in разрушениях.
        if (type == Material.BEDROCK || block.getState() instanceof Container) {
            return;
        }
        plugin.restore().remember(block);
        block.setType(material, false);
        plugin.restore().markChanged(block);
        tickBlockChanges++;
    }

    private static ThreadLocalRandom random() {
        return ThreadLocalRandom.current();
    }
}
