package dev.rosewood.rosestacker.stack.settings.conditions.spawner.tags;

import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.manager.EntityCacheManager;
import dev.rosewood.rosestacker.manager.LocaleManager;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackedSpawner;
import dev.rosewood.rosestacker.stack.settings.SpawnerStackSettings;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.ConditionTag;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

public class MaxNearbyEntityConditionTag extends ConditionTag {

    private int maxNearbyEntities;
    private StackManager stackManager;
    private EntityCacheManager entityCacheManager;

    public MaxNearbyEntityConditionTag(String tag) {
        super(tag, false);
    }

    @Override
    public boolean check(StackedSpawner stackedSpawner, Block spawnBlock) {
        if (this.stackManager == null || this.entityCacheManager == null) {
            this.stackManager = RoseStacker.getInstance().getManager(StackManager.class);
            this.entityCacheManager = RoseStacker.getInstance().getManager(EntityCacheManager.class);
        }

        SpawnerStackSettings stackSettings = stackedSpawner.getStackSettings();
        int detectionRange = stackSettings.getEntitySearchRange() == -1 ? stackedSpawner.getSpawnerTile().getSpawnRange() : stackSettings.getEntitySearchRange();
        Block block = stackedSpawner.getBlock();
        List<EntityType> entityTypes = stackedSpawner.getSpawnerTile().getSpawnerType().getEntityTypes();

        // This only ever needs to know whether there are fewer than N entities nearby, so count with an
        // early exit instead of collecting every nearby entity into a Set that is thrown away after a
        // size comparison. The counter stops as soon as the threshold is reached, which is the common
        // case on a saturated farm.
        Predicate<Entity> predicate = entity -> entityTypes.contains(entity.getType());
        double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;

        int nearbyAmount;
        if (SettingKey.SPAWNER_MAX_NEARBY_ENTITIES_INCLUDE_STACKS.get()) {
            nearbyAmount = this.entityCacheManager.countNearbyEntities(block.getWorld(), x, y, z, detectionRange, predicate, entity -> {
                StackedEntity stackedEntity = this.stackManager.getStackedEntity((LivingEntity) entity);
                return stackedEntity == null ? 1 : stackedEntity.getStackSize();
            }, this.maxNearbyEntities);
        } else {
            nearbyAmount = this.entityCacheManager.countNearbyEntities(block.getWorld(), x, y, z, detectionRange, predicate, this.maxNearbyEntities);
        }

        return nearbyAmount < this.maxNearbyEntities;
    }

    @Override
    public boolean parseValues(String[] values) {
        if (values.length != 1)
            return false;

        try {
            this.maxNearbyEntities = Integer.parseInt(values[0]);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @Override
    protected List<String> getInfoMessageValues(LocaleManager localeManager) {
        return List.of(String.valueOf(this.maxNearbyEntities));
    }

}
