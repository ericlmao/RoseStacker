package dev.rosewood.rosestacker.utils;

import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.manager.StackManager;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Witch;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpawnerRaidEligibilityTest {

    @Test
    void newlyTaggedSpawnerWitchCannotJoinRaids() {
        Witch witch = mock(Witch.class);
        RoseStacker plugin = mock(RoseStacker.class);
        when(plugin.namespace()).thenReturn("rosestacker");
        when(plugin.getManager(StackManager.class)).thenReturn(mock(StackManager.class));
        when(witch.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        try (MockedStatic<RoseStacker> plugins = mockStatic(RoseStacker.class)) {
            plugins.when(RoseStacker::getInstance).thenReturn(plugin);
            PersistentDataUtils.tagSpawnedFromSpawner(witch);
            verify(witch).setCanJoinRaid(false);
        }
    }

    @Test
    void restoredSpawnerWitchCannotJoinRaidsEvenWithAiEnabled() {
        verifyRestoredSpawnerRaider(mock(Witch.class));
    }

    @Test
    void restoredSpawnerEvokerCannotJoinRaidsEvenWithAiEnabled() {
        verifyRestoredSpawnerRaider(mock(Evoker.class));
    }

    private void verifyRestoredSpawnerRaider(Raider raider) {
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class, CALLS_REAL_METHODS)) {
            data.when(() -> PersistentDataUtils.isSpawnedFromSpawner(raider)).thenReturn(true);
            data.when(() -> PersistentDataUtils.isAiDisabled(raider)).thenReturn(false);
            PersistentDataUtils.applyDisabledAi(raider);
            verify(raider).setCanJoinRaid(false);
            verify(raider, never()).setAware(false);
            verify(raider, never()).setAI(false);
            verify(raider, never()).setGravity(false);
        }
    }

    @Test
    void naturalRaidMobKeepsItsEligibility() {
        Raider raider = mock(Raider.class);
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class, CALLS_REAL_METHODS)) {
            data.when(() -> PersistentDataUtils.isSpawnedFromSpawner(raider)).thenReturn(false);
            data.when(() -> PersistentDataUtils.isAiDisabled(raider)).thenReturn(false);
            PersistentDataUtils.applyDisabledAi(raider);
            verify(raider, never()).setCanJoinRaid(false);
        }
    }

    @Test
    void nonRaiderKeepsItsAiAndGravity() {
        LivingEntity entity = mock(LivingEntity.class);
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class, CALLS_REAL_METHODS)) {
            data.when(() -> PersistentDataUtils.isAiDisabled(entity)).thenReturn(false);
            PersistentDataUtils.applyDisabledAi(entity);
            verify(entity, never()).setAI(false);
            verify(entity, never()).setGravity(false);
        }
    }
}
