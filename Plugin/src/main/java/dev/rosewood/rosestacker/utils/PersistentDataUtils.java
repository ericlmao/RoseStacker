package dev.rosewood.rosestacker.utils;

import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.manager.StackSettingManager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.spawner.StackedSpawnerTile;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackedItem;
import dev.rosewood.rosestacker.stack.settings.EntityStackSettings;
import java.util.ConcurrentModificationException;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class PersistentDataUtils {

    private static final String UNSTACKABLE_METADATA_NAME = "unstackable";
    private static final String NO_AI_METADATA_NAME = "no_ai";
    private static final String SPAWNED_FROM_SPAWNER_METADATA_NAME = "spawner_spawned";
    private static final String SPAWNED_FROM_TRIAL_SPAWNER_METADATA_NAME = "trial_spawner_spawned";
    private static final String SPAWNED_FROM_DISPENSER_METADATA_NAME = "dispenser_spawned";
    private static final String TOTAL_SPAWNS_METADATA_NAME = "total_spawns";

    // Built on first use rather than in a static initializer so the plugin instance is guaranteed to exist.
    // These used to be allocated per call; the NamespacedKey constructor lowercases and validates both
    // halves and PersistentDataContainer#has then builds the "namespace:key" string again, which added up
    // to millions of throwaway strings a minute across the stacking passes and the entity event handlers.
    private static NamespacedKey unstackableKey;
    private static NamespacedKey noAiKey;
    private static NamespacedKey spawnedFromSpawnerKey;
    private static NamespacedKey spawnedFromTrialSpawnerKey;
    private static NamespacedKey spawnedFromDispenserKey;
    private static NamespacedKey totalSpawnsKey;

    private static NamespacedKey getUnstackableKey() {
        NamespacedKey key = unstackableKey;
        if (key == null)
            unstackableKey = key = new NamespacedKey(RoseStacker.getInstance(), UNSTACKABLE_METADATA_NAME);
        return key;
    }

    private static NamespacedKey getNoAiKey() {
        NamespacedKey key = noAiKey;
        if (key == null)
            noAiKey = key = new NamespacedKey(RoseStacker.getInstance(), NO_AI_METADATA_NAME);
        return key;
    }

    private static NamespacedKey getSpawnedFromSpawnerKey() {
        NamespacedKey key = spawnedFromSpawnerKey;
        if (key == null)
            spawnedFromSpawnerKey = key = new NamespacedKey(RoseStacker.getInstance(), SPAWNED_FROM_SPAWNER_METADATA_NAME);
        return key;
    }

    private static NamespacedKey getSpawnedFromTrialSpawnerKey() {
        NamespacedKey key = spawnedFromTrialSpawnerKey;
        if (key == null)
            spawnedFromTrialSpawnerKey = key = new NamespacedKey(RoseStacker.getInstance(), SPAWNED_FROM_TRIAL_SPAWNER_METADATA_NAME);
        return key;
    }

    private static NamespacedKey getSpawnedFromDispenserKey() {
        NamespacedKey key = spawnedFromDispenserKey;
        if (key == null)
            spawnedFromDispenserKey = key = new NamespacedKey(RoseStacker.getInstance(), SPAWNED_FROM_DISPENSER_METADATA_NAME);
        return key;
    }

    private static NamespacedKey getTotalSpawnsKey() {
        NamespacedKey key = totalSpawnsKey;
        if (key == null)
            totalSpawnsKey = key = new NamespacedKey(RoseStacker.getInstance(), TOTAL_SPAWNS_METADATA_NAME);
        return key;
    }

    /**
     * Drops the flags a stack caches for an entity, so the next read goes back to the container.
     * Every writer below calls this; without it a cached value could outlive the write that changed it.
     *
     * @param entity The entity whose stack should forget its cached flags
     */
    private static void invalidateCachedFlags(Entity entity) {
        StackManager stackManager = RoseStacker.getInstance().getManager(StackManager.class);
        if (entity instanceof LivingEntity livingEntity) {
            StackedEntity stackedEntity = stackManager.getStackedEntity(livingEntity);
            if (stackedEntity != null)
                stackedEntity.invalidateCachedFlags();
        } else if (entity instanceof Item item) {
            StackedItem stackedItem = stackManager.getStackedItem(item);
            if (stackedItem != null)
                stackedItem.invalidateCachedFlags();
        }
    }

    public static void setUnstackable(Entity entity, boolean unstackable) {
        if (unstackable) {
            entity.getPersistentDataContainer().set(getUnstackableKey(), PersistentDataType.INTEGER, 1);
        } else {
            entity.getPersistentDataContainer().remove(getUnstackableKey());
        }

        invalidateCachedFlags(entity);
    }

    public static boolean isUnstackable(Entity entity) {
        return entity.getPersistentDataContainer().has(getUnstackableKey(), PersistentDataType.INTEGER);
    }

    public static void removeEntityAi(LivingEntity entity) {
        PersistentDataContainer dataContainer = entity.getPersistentDataContainer();
        NamespacedKey key = getNoAiKey();
        if (!dataContainer.has(key, PersistentDataType.INTEGER)) {
            dataContainer.set(key, PersistentDataType.INTEGER, 1);
            invalidateCachedFlags(entity);
        }

        applyDisabledAi(entity);
    }

    public static void reenableEntityAi(LivingEntity entity) {
        PersistentDataContainer dataContainer = entity.getPersistentDataContainer();
        dataContainer.remove(getNoAiKey());
        invalidateCachedFlags(entity);

        applyDisabledAi(entity, false);
    }

    public static void applyDisabledAi(LivingEntity entity) {
        applyDisabledAi(entity, true);
    }

    public static void applyDisabledAi(LivingEntity entity, boolean disable) {
        if (isAiDisabled(entity) || !disable) {
            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_REMOVE_GOALS.get() && disable) {
                NMSHandler nmsHandler = NMSAdapter.getHandler();
                nmsHandler.removeEntityGoals(entity);
            }

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_DISABLE_ITEM_PICKUP.get())
                entity.setCanPickupItems(!disable);

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_SET_UNAWARE.get() && entity instanceof Mob mob)
                mob.setAware(!disable);

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_SILENCE.get())
                entity.setSilent(disable);

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_NO_KNOCKBACK.get()) {
                AttributeInstance knockbackAttribute = entity.getAttribute(VersionUtils.KNOCKBACK_RESISTANCE);
                if (knockbackAttribute != null)
                    knockbackAttribute.setBaseValue(disable ? Double.MAX_VALUE : 0);
            }

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_DISABLE_ZOMBIFICATION.get()) {
                if (entity instanceof PiglinAbstract piglin) {
                    piglin.setImmuneToZombification(disable);
                } else if (entity instanceof Hoglin hoglin) {
                    hoglin.setImmuneToZombification(disable);
                }
            }

            if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_DISABLE_COLLISION.get())
                entity.setCollidable(!disable);
        }
    }

    public static boolean isAiDisabled(LivingEntity entity) {
        EntityStackSettings entityStackSettings = RoseStacker.getInstance().getManager(StackSettingManager.class).getEntityStackSettings(entity.getType());
        if (entityStackSettings != null && entityStackSettings.isMobAIDisabled())
            return true;

        return entity.getPersistentDataContainer().has(getNoAiKey(), PersistentDataType.INTEGER);
    }

    public static void tagSpawnedFromSpawner(Entity entity) {
        entity.getPersistentDataContainer().set(getSpawnedFromSpawnerKey(), PersistentDataType.INTEGER, 1);
        invalidateCachedFlags(entity);
    }

    /**
     * Checks if an entity was spawned from a spawner
     *
     * @param entity The entity to check
     * @return true if the entity was spawned from a spawner, otherwise false
     */
    public static boolean isSpawnedFromSpawner(Entity entity) {
        return entity.getPersistentDataContainer().has(getSpawnedFromSpawnerKey(), PersistentDataType.INTEGER)
                || EntityUtils.hasSpawnerSpawnReason(entity);
    }

    public static void tagSpawnedFromTrialSpawner(Entity entity) {
        entity.getPersistentDataContainer().set(getSpawnedFromTrialSpawnerKey(), PersistentDataType.INTEGER, 1);
        invalidateCachedFlags(entity);
    }

    /**
     * Checks if an entity was spawned from a trial spawner
     *
     * @param entity The entity to check
     * @return true if the entity was spawned from a trial spawner, otherwise false
     */
    public static boolean isSpawnedFromTrialSpawner(Entity entity) {
        return entity.getPersistentDataContainer().has(getSpawnedFromTrialSpawnerKey(), PersistentDataType.INTEGER)
                || EntityUtils.hasTrialSpawnerSpawnReason(entity);
    }

    public static void tagSpawnedFromDispenser(Entity entity) {
        entity.getPersistentDataContainer().set(getSpawnedFromDispenserKey(), PersistentDataType.INTEGER, 1);
        invalidateCachedFlags(entity);
    }

    /**
     * Checks if an entity was spawned from a spawn egg in a dispenser
     *
     * @param entity The entity to check
     * @return true if the entity was spawned from a dispenser, otherwise false
     */
    public static boolean isSpawnedFromDispenser(Entity entity) {
        return entity.getPersistentDataContainer().has(getSpawnedFromDispenserKey(), PersistentDataType.INTEGER);
    }

    public static void increaseSpawnCount(StackedSpawnerTile spawner, long amount) {
        PersistentDataContainer dataContainer = spawner.getPersistentDataContainer();
        if (dataContainer != null) {
            NamespacedKey key = getTotalSpawnsKey();
            if (!dataContainer.has(key, PersistentDataType.LONG)) {
                dataContainer.set(key, PersistentDataType.LONG, amount);
            } else {
                dataContainer.set(key, PersistentDataType.LONG, getTotalSpawnCount(spawner) + amount);
            }
        }
    }

    public static long getTotalSpawnCount(StackedSpawnerTile spawner) {
        try {
            PersistentDataContainer persistentDataContainer = spawner.getPersistentDataContainer();
            if (persistentDataContainer == null)
                return 0;

            Long amount = persistentDataContainer.get(getTotalSpawnsKey(), PersistentDataType.LONG);
            return amount != null ? amount : 0;
        } catch (ConcurrentModificationException e) {
            return 0; // StackedSpawner#updateDisplay can cause a CME sometimes here when run async
        }
    }

}
