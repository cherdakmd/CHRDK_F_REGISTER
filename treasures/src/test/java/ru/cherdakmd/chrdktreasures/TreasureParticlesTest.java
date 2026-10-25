package ru.cherdakmd.chrdktreasures;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эффект находки (1.4.170). В 1.4.169 на сервере с purpur-api 26.3 открытие
 * клада падало с «missing required data class java.lang.Float»: у частиц
 * появились обязательные данные, а вызов шёл без них — событие обрывалось, и
 * игрок не видел ни сообщения, ни витрины. Тест держит контракт помощника:
 * данные передаются ровно тем частицам, которым они нужны.
 *
 * <p>Тест намеренно не фиксирует, какая именно частица требует Float: набор
 * данных меняется от версии к версии, а контракт — нет.</p>
 */
class TreasureParticlesTest {

    @Test
    void everyTierHasItsOwnParticle() {
        Set<Particle> used = new HashSet<>();
        for (TreasureTier tier : TreasureTier.values()) {
            Particle particle = TreasureListener.particleOf(tier);
            assertNotNull(particle, tier.id());
            assertTrue(used.add(particle), tier.id() + ": частица повторяется");
        }
    }

    @Test
    void floatParticlesReceivePowerAndSimpleParticlesReceiveNothing() {
        for (Particle particle : Particle.values()) {
            Object data = TreasureParticles.dataOf(particle);
            if (particle.getDataType() == Float.class) {
                assertEquals(Float.valueOf(1.0f), data, particle.name());
            } else if (particle.getDataType() == Void.class) {
                assertNull(data, particle.name());
            }
        }
        assertNull(TreasureParticles.dataOf(null));
    }

    @Test
    void everyTierIsSpawnableWithTheHelperData() {
        // Если в очередной версии частица ступени потребует не Float, а,
        // например, BlockData, помощник не сможет её нарисовать — тест обязан
        // упасть здесь, а не в бою.
        for (TreasureTier tier : TreasureTier.values()) {
            Particle particle = TreasureListener.particleOf(tier);
            boolean needsData = particle.getDataType() != Void.class;
            Object data = TreasureListener.dataOf(particle);
            assertTrue(needsData == (data != null),
                    tier.id() + ": " + particle + " требует " + particle.getDataType().getSimpleName());
            if (data != null) {
                assertTrue(particle.getDataType().isInstance(data),
                        particle + " ждёт " + particle.getDataType().getSimpleName());
            }
        }
    }

    @Test
    void delegateOnListenerMatchesHelper() {
        for (Particle particle : Particle.values()) {
            assertEquals(TreasureParticles.dataOf(particle), TreasureListener.dataOf(particle),
                    particle.name());
        }
    }

    @Test
    void epicTierGetsDataWhenTheServerAsksForIt() {
        // Регрессия 1.4.169: частица EPIC требовала Float, а вызов шёл без
        // данных. Ступени не должны остаться без данных, если сервер их просит.
        Particle epic = TreasureListener.particleOf(TreasureTier.EPIC);
        assertTrue((epic.getDataType() != Void.class) == (TreasureListener.dataOf(epic) != null));
    }
}
