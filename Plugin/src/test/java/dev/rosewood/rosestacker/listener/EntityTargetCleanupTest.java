package dev.rosewood.rosestacker.listener;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EntityTargetCleanupTest {
    @BeforeAll
    static void initializeVersion() throws ClassNotFoundException {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn("26.2-R0.1-SNAPSHOT");
            Class.forName("dev.rosewood.rosegarden.utils.NMSUtil");
        }
    }

    private final EntityListener listener = new EntityListener(mock(RosePlugin.class));
    private final Wither attacker = mock(Wither.class);

    @ParameterizedTest
    @EnumSource(value = TargetReason.class, names = {"TARGET_DIED", "FORGOT_TARGET", "CUSTOM"})
    void disabledAttackerMayForgetRemovedOrAbandonedTarget(TargetReason reason) {
        // Mob.setTarget leaves the previous reference intact if this event is cancelled.
        // Goal.stop emits FORGOT_TARGET; removal can also produce TARGET_DIED.
        when(attacker.getType()).thenReturn(EntityType.WITHER);
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class)) {
            data.when(() -> PersistentDataUtils.isAiDisabled(attacker)).thenReturn(true);
            EntityTargetLivingEntityEvent clear = new EntityTargetLivingEntityEvent(attacker, null, reason);
            listener.onEntityTarget(clear);
            assertFalse(clear.isCancelled(), "Target clearing must not retain a ghost reference");
        }
    }

    @Test
    void acquiringAnotherTargetIsStillBlocked() {
        when(attacker.getType()).thenReturn(EntityType.WITHER);
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class)) {
            data.when(() -> PersistentDataUtils.isAiDisabled(attacker)).thenReturn(true);
            EntityTargetLivingEntityEvent acquire = new EntityTargetLivingEntityEvent(
                    attacker, mock(LivingEntity.class), TargetReason.CLOSEST_ENTITY);
            listener.onEntityTarget(acquire);
            assertTrue(acquire.isCancelled());
        }
    }

    @Test
    void clearingDoesNotUndoAnotherPluginsCancellation() {
        when(attacker.getType()).thenReturn(EntityType.WITHER);
        try (MockedStatic<PersistentDataUtils> data = mockStatic(PersistentDataUtils.class)) {
            data.when(() -> PersistentDataUtils.isAiDisabled(attacker)).thenReturn(true);
            EntityTargetLivingEntityEvent clear = new EntityTargetLivingEntityEvent(attacker, null, TargetReason.FORGOT_TARGET);
            clear.setCancelled(true);
            listener.onEntityTarget(clear);
            assertTrue(clear.isCancelled());
        }
    }
}
