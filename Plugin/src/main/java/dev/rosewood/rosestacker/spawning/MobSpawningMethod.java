package dev.rosewood.rosestacker.spawning;

import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.event.PostStackedSpawnerSpawnEvent;
import dev.rosewood.rosestacker.event.PreStackedSpawnerSpawnEvent;
import dev.rosewood.rosestacker.hook.WorldGuardHook;
import dev.rosewood.rosestacker.manager.EntityCacheManager;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.manager.StackSettingManager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.spawner.StackedSpawnerTile;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackedSpawner;
import dev.rosewood.rosestacker.stack.settings.EntityStackSettings;
import dev.rosewood.rosestacker.stack.settings.SpawnerStackSettings;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.ConditionTag;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.tags.MaxNearbyEntityConditionTag;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.tags.NoneConditionTag;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.tags.NotPlayerPlacedConditionTag;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import dev.rosewood.rosestacker.utils.StackerUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import dev.rosewood.rosestacker.utils.VersionUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.util.Vector;

public class MobSpawningMethod implements SpawningMethod {

    private final EntityType entityType;
    /**
     * Scratch space for the spawn offset cache, reused between spawn cycles. One spawning method is kept
     * per spawner tile and a spawner only ever spawns from one thread at a time, so this never overlaps.
     */
    private final long[] spawnOffsetBuffer;

    public MobSpawningMethod(EntityType entityType) {
        this.entityType = entityType;
        this.spawnOffsetBuffer = new long[StackedSpawner.MAX_CACHED_SPAWN_OFFSETS];
    }

    /**
     * @return the type of entity this spawning method spawns
     */
    public EntityType getEntityType() {
        return this.entityType;
    }

    @Override
    public void spawn(StackedSpawner stackedSpawner, boolean onlyCheckConditions) {
        StackedSpawnerTile spawnerTile = stackedSpawner.getSpawnerTile();
        SpawnerStackSettings stackSettings = stackedSpawner.getStackSettings();
        EntityStackSettings entityStackSettings = RoseStacker.getInstance().getManager(StackSettingManager.class).getEntityStackSettings(this.entityType);

        // Mob spawning logic. The two condition lists are partitioned once when the stack settings are
        // loaded; this used to copy, stream, and removeAll the requirement list on every spawn attempt.
        List<ConditionTag> spawnRequirements = stackSettings.getSpawnerConditions();
        List<ConditionTag> perSpawnConditions = stackSettings.getPerSpawnConditions();

        // Spawn the mobs
        int spawnAmount;
        if (SettingKey.SPAWNER_SPAWN_COUNT_STACK_SIZE_RANDOMIZED.get()) {
            if (stackSettings.getSpawnCountStackSizeMultiplier() != -1) {
                int spawnerSpawnCount = Math.max(spawnerTile.getSpawnCount(), 0);
                spawnAmount = StackerUtils.randomInRange(stackedSpawner.getStackSize(), spawnerSpawnCount);
            } else {
                spawnAmount = ThreadLocalRandom.current().nextInt(spawnerTile.getSpawnCount()) + 1;
            }
        } else {
            spawnAmount = spawnerTile.getSpawnCount();
        }

        StackManager stackManager = RoseStacker.getInstance().getManager(StackManager.class);

        Runnable spawnTask = () -> {
            // StackedSpawner#getLocation allocates a Location on every call, and the attempt loop below
            // used to build two or three of them per attempt just to get at the spawner's coordinates
            Block spawnerBlock = stackedSpawner.getBlock();
            World spawnerWorld = spawnerBlock.getWorld(); // Stack#getWorld builds a Location just to read this
            int spawnerX = spawnerBlock.getX(), spawnerY = spawnerBlock.getY(), spawnerZ = spawnerBlock.getZ();

            // Make sure the chunk is still loaded
            if (!spawnerWorld.isChunkLoaded(spawnerX >> 4, spawnerZ >> 4))
                return;

            Set<ConditionTag> invalidSpawnConditions = new HashSet<>();
            for (ConditionTag conditionTag : spawnRequirements)
                if (!conditionTag.check(stackedSpawner, spawnerBlock))
                    invalidSpawnConditions.add(conditionTag);
            if (SettingKey.SPAWNER_SPAWN_ONLY_PLAYER_PLACED.get() && !stackedSpawner.isPlacedByPlayer())
                invalidSpawnConditions.add(NotPlayerPlacedConditionTag.INSTANCE);

            boolean passedSpawnerChecks = invalidSpawnConditions.isEmpty();
            Set<Location> spawnLocations = new HashSet<>();
            // Failed positions used to be keyed by a Location in a multimap, which allocated a Location per
            // attempt, hashed three doubles per lookup and built a list per failed position. The offsets are
            // small integers, so pack them into a long and tally the unmet conditions as they come up; the
            // failure report at the end only ever needed the counts.
            Set<Long> invalidOffsets = new HashSet<>();
            Map<ConditionTag, Integer> unmetConditionCounts = new HashMap<>();
            int totalInvalidLocations = 0;

            int spawnRange = spawnerTile.getSpawnRange();
            int attempts = 0;
            int maxFailedSpawnAttempts = SettingKey.SPAWNER_MAX_FAILED_SPAWN_ATTEMPTS.get() * spawnRange * spawnRange;
            int desiredLocations = Math.max(2, stackSettings.getSpawnCountStackSizeMultiplier());
            boolean useNearbyEntitiesForStacking = stackManager.isEntityStackingEnabled() && entityStackSettings.isStackingEnabled() && SettingKey.SPAWNER_SPAWN_INTO_NEARBY_STACKS.get();
            if (!useNearbyEntitiesForStacking)
                desiredLocations *= 4;

            boolean verticalSpawnRange = SettingKey.SPAWNER_USE_VERTICAL_SPAWN_RANGE.get();
            ThreadLocalRandom random = ThreadLocalRandom.current();

            // Mob farms are static, so the offsets that produced a valid spawn location last cycle are
            // almost always still valid. Re-check those before sampling randomly: a saturated farm used to
            // run the full maxFailedSpawnAttempts random search every single cycle, which is up to 800
            // block probes with the default settings. Offsets that no longer pass are dropped and offsets
            // found by the random search take their place.
            int cachedOffsetCount = stackedSpawner.getCachedSpawnOffsetCount();
            int cachedOffsetIndex = 0;
            int keptOffsets = 0;

            List<ConditionTag> unmetConditions = new ArrayList<>(perSpawnConditions.size());
            while (true) {
                int xOffset, yOffset, zOffset;
                boolean fromCache = cachedOffsetIndex < cachedOffsetCount;
                if (fromCache) {
                    long cachedOffset = stackedSpawner.getCachedSpawnOffset(cachedOffsetIndex++);
                    xOffset = unpackOffsetX(cachedOffset);
                    yOffset = unpackOffsetY(cachedOffset);
                    zOffset = unpackOffsetZ(cachedOffset);

                    // The spawn range is a config value, so drop anything the current range cannot reach
                    if (Math.abs(xOffset) > spawnRange || Math.abs(zOffset) > spawnRange
                            || Math.abs(yOffset) > (verticalSpawnRange ? spawnRange : 1))
                        continue;
                } else {
                    if (attempts > maxFailedSpawnAttempts)
                        break;

                    xOffset = random.nextInt(spawnRange * 2 + 1) - spawnRange;
                    yOffset = !verticalSpawnRange ? random.nextInt(3) - 1 : random.nextInt(spawnRange * 2 + 1) - spawnRange;
                    zOffset = random.nextInt(spawnRange * 2 + 1) - spawnRange;
                }

                long offsetKey = packOffset(xOffset, yOffset, zOffset);
                if (invalidOffsets.contains(offsetKey)) {
                    // Decrease max failed spawn attempts if the location is invalid to avoid spinning forever
                    if (!fromCache)
                        maxFailedSpawnAttempts--;
                    continue;
                }

                Block target = spawnerWorld.getBlockAt(spawnerX + xOffset, spawnerY + yOffset, spawnerZ + zOffset);

                unmetConditions.clear();
                for (ConditionTag conditionTag : perSpawnConditions)
                    if (!conditionTag.check(stackedSpawner, target))
                        unmetConditions.add(conditionTag);

                if (!unmetConditions.isEmpty()) {
                    invalidOffsets.add(offsetKey);
                    totalInvalidLocations += unmetConditions.size();
                    for (ConditionTag conditionTag : unmetConditions)
                        unmetConditionCounts.merge(conditionTag, 1, Integer::sum);

                    // Only the random search is on a budget; re-checking a cached offset is a few probes
                    if (!fromCache)
                        attempts++;
                    continue;
                }

                if (keptOffsets < StackedSpawner.MAX_CACHED_SPAWN_OFFSETS && !containsOffset(this.spawnOffsetBuffer, keptOffsets, offsetKey))
                    this.spawnOffsetBuffer[keptOffsets++] = offsetKey;

                if (!passedSpawnerChecks)
                    break;

                spawnLocations.add(new Location(spawnerWorld, spawnerX + xOffset + 0.5, spawnerY + yOffset, spawnerZ + zOffset + 0.5));
                if (spawnLocations.size() >= desiredLocations)
                    break;
            }

            // Anything the search stopped short of re-checking is still worth keeping for the next cycle
            while (cachedOffsetIndex < cachedOffsetCount && keptOffsets < StackedSpawner.MAX_CACHED_SPAWN_OFFSETS) {
                long cachedOffset = stackedSpawner.getCachedSpawnOffset(cachedOffsetIndex++);
                if (!containsOffset(this.spawnOffsetBuffer, keptOffsets, cachedOffset))
                    this.spawnOffsetBuffer[keptOffsets++] = cachedOffset;
            }
            stackedSpawner.setCachedSpawnOffsets(this.spawnOffsetBuffer, keptOffsets);

            int successfulSpawns;
            if (!onlyCheckConditions) {
                if (useNearbyEntitiesForStacking) {
                    SpawnResult spawnResult = this.spawnEntitiesIntoNearbyStacks(stackedSpawner, spawnAmount, spawnLocations, stackManager, stackSettings, entityStackSettings, invalidSpawnConditions);
                    successfulSpawns = spawnResult.spawnAmount();
                    if (successfulSpawns > 0)
                        invalidSpawnConditions.removeIf(x -> x instanceof MaxNearbyEntityConditionTag);

                    PostStackedSpawnerSpawnEvent event = new PostStackedSpawnerSpawnEvent(stackedSpawner, spawnResult.modifiedStacks(), spawnResult.spawnedStacks(), spawnResult.spawnAmount());
                    Bukkit.getPluginManager().callEvent(event);
                } else {
                    successfulSpawns = this.spawnEntitiesIndividually(stackedSpawner, spawnAmount, spawnLocations, entityStackSettings, stackManager);
                }
            } else {
                successfulSpawns = 0;
            }

            stackedSpawner.getLastInvalidConditions().clear();
            if (successfulSpawns == 0) {
                ConditionTag mostFrequentCondition = null;
                int highestCount = 0;

                for (Map.Entry<ConditionTag, Integer> entry : unmetConditionCounts.entrySet()) {
                    ConditionTag conditionTag = entry.getKey();
                    int count = entry.getValue();

                    if (count > highestCount) {
                        highestCount = count;
                        mostFrequentCondition = conditionTag;
                    }

                    if (totalInvalidLocations > 0 && (count / (double) totalInvalidLocations) > 0.5)
                        invalidSpawnConditions.add(conditionTag);
                }

                if (invalidSpawnConditions.isEmpty() && mostFrequentCondition != null)
                    invalidSpawnConditions.add(mostFrequentCondition);

                if (invalidSpawnConditions.isEmpty()) {
                    stackedSpawner.getLastInvalidConditions().add(NoneConditionTag.class);
                } else {
                    for (ConditionTag conditionTag : invalidSpawnConditions)
                        stackedSpawner.getLastInvalidConditions().add(conditionTag.getClass());
                }

                if (!onlyCheckConditions) {
                    // Spawn particles indicating the spawn did not occur
                    stackedSpawner.getWorld().spawnParticle(VersionUtils.SMOKE, stackedSpawner.getLocation().clone().add(0.5, 0.5, 0.5), 50, 0.5, 0.5, 0.5, 0);
                }
            } else {
                // Spawn particles indicating the spawn occurred
                stackedSpawner.getWorld().spawnParticle(Particle.FLAME, stackedSpawner.getLocation().clone().add(0.5, 0.5, 0.5), 50, 0.5, 0.5, 0.5, 0);
                if (stackedSpawner.getBlock().getType() == Material.SPAWNER)
                    PersistentDataUtils.increaseSpawnCount(spawnerTile, successfulSpawns);
            }
        };

        if (SettingKey.SPAWNER_SPAWN_ASYNC.get()) {
            ThreadUtils.runOnLocation(stackedSpawner.getLocation(), spawnTask);
        } else {
            spawnTask.run();
        }
    }

    private int spawnEntitiesIndividually(StackedSpawner stackedSpawner, int spawnAmount, Set<Location> locations, EntityStackSettings entityStackSettings, StackManager stackManager) {
        if (this.entityType.getEntityClass() == null || locations.isEmpty())
            return 0;

        PreStackedSpawnerSpawnEvent event = new PreStackedSpawnerSpawnEvent(stackedSpawner, spawnAmount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return 0;

        spawnAmount = event.getSpawnAmount();

        boolean bypassRegions = SettingKey.SPAWNER_BYPASS_REGION_SPAWNING_RULES.get();

        List<Location> possibleLocations = new ArrayList<>(locations);
        int finalSpawnAmount = spawnAmount;
        ThreadUtils.runOnLocation(stackedSpawner.getLocation(), () -> { // No poof particles to show where the mobs spawn with this setting, they immediately try stacking and are entirely unpredictable
            NMSHandler nmsHandler = NMSAdapter.getHandler();
            for (int i = 0; i < finalSpawnAmount; i++) {
                if (locations.isEmpty())
                    break;

                Location location = possibleLocations.get(ThreadLocalRandom.current().nextInt(possibleLocations.size()));
                if (NMSUtil.isPaper()) {
                    var result = PreCreatureSpawnEventHelper.call(location, this.entityType, CreatureSpawnEvent.SpawnReason.SPAWNER);
                    if (result.abort())
                        return;
                    if (result.cancel())
                        continue;
                }

                LivingEntity entity = this.createNewEntity(nmsHandler, location, stackedSpawner, stackManager, entityStackSettings);

                SpawnerSpawnEvent spawnerSpawnEvent = new SpawnerSpawnEvent(entity, stackedSpawner.getSpawner());
                Bukkit.getPluginManager().callEvent(spawnerSpawnEvent);
                if (spawnerSpawnEvent.isCancelled()) {
                    entity.remove();
                    continue;
                }

                nmsHandler.spawnExistingEntity(entity, CreatureSpawnEvent.SpawnReason.SPAWNER, bypassRegions);

                // Nudge the entities a little so they unstack easier
                entity.setVelocity(Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).multiply(0.01));
            }
        });

        return spawnAmount;
    }

    private SpawnResult spawnEntitiesIntoNearbyStacks(StackedSpawner stackedSpawner, int spawnAmount, Set<Location> locations, StackManager stackManager, SpawnerStackSettings spawnerStackSettings, EntityStackSettings entityStackSettings, Set<ConditionTag> invalidSpawnConditions) {
        if (!invalidSpawnConditions.isEmpty() && !invalidSpawnConditions.stream().allMatch(x -> x instanceof MaxNearbyEntityConditionTag))
            return SpawnResult.empty();

        boolean canSpawnNewEntities = invalidSpawnConditions.isEmpty();
        EntityType entityType = stackedSpawner.getSpawnerTile().getSpawnerType().getOrThrow();
        Predicate<Entity> predicate = entity -> entity.getType() == entityType;
        EntityCacheManager entityCacheManager = RoseStacker.getInstance().getManager(EntityCacheManager.class);
        Collection<Entity> nearbyEntities = entityCacheManager.getNearbyEntities(stackedSpawner.getLocation(), spawnerStackSettings.getSpawnRange(), predicate);
        List<StackedEntity> nearbyStackedEntities = new ArrayList<>();
        for (Entity entity : nearbyEntities) {
            StackedEntity stackedEntity = stackManager.getStackedEntity((LivingEntity) entity);
            if (stackedEntity != null && stackedEntity.getStackSize() < entityStackSettings.getMaxStackSize())
                nearbyStackedEntities.add(stackedEntity);
        }

        List<Location> possibleLocations = new ArrayList<>(locations);

        if (this.entityType.getEntityClass() == null || (possibleLocations.isEmpty() && nearbyStackedEntities.isEmpty()))
            return SpawnResult.empty();

        PreStackedSpawnerSpawnEvent event = new PreStackedSpawnerSpawnEvent(stackedSpawner, spawnAmount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return SpawnResult.empty();

        spawnAmount = event.getSpawnAmount();

        int successfulSpawns = 0;
        Set<StackedEntity> modifiedStacks = new HashSet<>();
        Set<StackedEntity> spawnedStacks = new HashSet<>();
        List<StackedEntity> newStacks = new ArrayList<>();
        NMSHandler nmsHandler = NMSAdapter.getHandler();
        // Scanning every nearby stack per spawned mob is wasted work: once a stack matches it keeps
        // matching until it fills up, so try the last one that matched before walking the list again
        StackedEntity lastMatchedStack = null;

        for (int i = spawnAmount; i > 0; i--) {
            Location location = possibleLocations.isEmpty() ? stackedSpawner.getLocation() : possibleLocations.get(ThreadLocalRandom.current().nextInt(possibleLocations.size()));
            switch (stackManager.getEntityDataStorageType(this.entityType)) {
                case NBT -> {
                    StackedEntity newStack = new StackedEntity(this.createNewEntity(nmsHandler, location, stackedSpawner, stackManager, entityStackSettings));
                    StackedEntity matchingEntity = this.findMatchingStack(nearbyStackedEntities, lastMatchedStack, newStack, entityStackSettings);
                    if (matchingEntity != null) {
                        matchingEntity.increaseStackSize(newStack.getEntity(), false);
                        modifiedStacks.add(matchingEntity);
                        lastMatchedStack = matchingEntity;
                    } else if (canSpawnNewEntities) {
                        if (possibleLocations.isEmpty())
                            break;

                        nearbyStackedEntities.add(newStack);
                        newStacks.add(newStack);
                    }

                    successfulSpawns++;
                }

                case SIMPLE -> {
                    StackedEntity matchingEntity = this.findMatchingStack(nearbyStackedEntities, lastMatchedStack, null, entityStackSettings);
                    if (matchingEntity != null) {
                        // Increase stack size by as much as we can
                        int amountToIncrease = Math.min(i, entityStackSettings.getMaxStackSize() - matchingEntity.getStackSize());
                        matchingEntity.increaseStackSize(amountToIncrease, false);
                        modifiedStacks.add(matchingEntity);
                        lastMatchedStack = matchingEntity;
                        i -= amountToIncrease;
                        successfulSpawns += amountToIncrease;
                    } else if (canSpawnNewEntities) {
                        StackedEntity newStack = new StackedEntity(this.createNewEntity(nmsHandler, location, stackedSpawner, stackManager, entityStackSettings));
                        nearbyStackedEntities.add(newStack);
                        newStacks.add(newStack);
                        successfulSpawns++;
                    }
                }
            }
        }

        modifiedStacks.forEach(StackedEntity::updateDisplaySafely);

        ThreadUtils.runOnLocation(stackedSpawner.getLocation(), () -> {
            stackManager.setEntityStackingTemporarilyDisabled(true);
            Iterator<StackedEntity> iterator = newStacks.iterator();
            while (iterator.hasNext()) {
                StackedEntity stackedEntity = iterator.next();
                LivingEntity entity = stackedEntity.getEntity();

                if (NMSUtil.isPaper()) {
                    var result = PreCreatureSpawnEventHelper.call(entity.getLocation(), this.entityType, CreatureSpawnEvent.SpawnReason.SPAWNER);
                    if (result.abort()) {
                        newStacks.clear();
                        break;
                    }
                    if (result.cancel()) {
                        iterator.remove();
                        continue;
                    }
                }

                SpawnerSpawnEvent spawnerSpawnEvent = new SpawnerSpawnEvent(entity, stackedSpawner.getSpawner());
                Bukkit.getPluginManager().callEvent(spawnerSpawnEvent);
                if (spawnerSpawnEvent.isCancelled())
                    continue;

                nmsHandler.spawnExistingEntity(entity, CreatureSpawnEvent.SpawnReason.SPAWNER, SettingKey.SPAWNER_BYPASS_REGION_SPAWNING_RULES.get());
                entity.setVelocity(Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).multiply(0.01));

                spawnedStacks.add(stackedEntity);
                stackManager.addEntityStack(stackedEntity);
            }
            stackManager.setEntityStackingTemporarilyDisabled(false);

            // Spawn particles for new entities and update nametags
            for (StackedEntity entity : newStacks) {
                entity.updateDisplaySafely();
                World world = entity.getLocation().getWorld();
                if (world != null)
                    world.spawnParticle(VersionUtils.POOF, entity.getLocation().clone().add(0, 0.75, 0), 5, 0.25, 0.25, 0.25, 0.01);
            }
        });

        return new SpawnResult(successfulSpawns, modifiedStacks, spawnedStacks);
    }

    /**
     * Finds a nearby stack that a newly spawned mob can be merged into, checking the stack that matched
     * last before falling back to scanning the whole list.
     *
     * @param nearbyStackedEntities The nearby stacks to search
     * @param lastMatchedStack The stack that matched for the previous mob, or null if there was none
     * @param newStack The stack being merged in, or null to test each candidate against itself
     * @param entityStackSettings The stack settings of the entity being spawned
     * @return the stack to merge into, or null if none of them match
     */
    private StackedEntity findMatchingStack(List<StackedEntity> nearbyStackedEntities, StackedEntity lastMatchedStack, StackedEntity newStack, EntityStackSettings entityStackSettings) {
        if (lastMatchedStack != null && this.canStackInto(lastMatchedStack, newStack, entityStackSettings))
            return lastMatchedStack;

        for (StackedEntity stackedEntity : nearbyStackedEntities)
            if (stackedEntity != lastMatchedStack && this.canStackInto(stackedEntity, newStack, entityStackSettings))
                return stackedEntity;

        return null;
    }

    private boolean canStackInto(StackedEntity stackedEntity, StackedEntity newStack, EntityStackSettings entityStackSettings) {
        return WorldGuardHook.testLocation(stackedEntity.getLocation())
                && entityStackSettings.testCanStackWith(stackedEntity, newStack == null ? stackedEntity : newStack, false, true);
    }

    /**
     * Packs a block offset relative to the spawner into a single long, 21 bits per axis. Spawn offsets are
     * bounded by the spawn range, so a primitive key is enough to identify a candidate spawn position and
     * it keeps both the offset cache and the invalid-position bookkeeping free of Location allocation.
     */
    private static long packOffset(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (z & 0x1FFFFF) << 21) | (y & 0x1FFFFFL);
    }

    private static int unpackOffsetX(long packedOffset) {
        return (int) (packedOffset << 1 >> 43);
    }

    private static int unpackOffsetY(long packedOffset) {
        return (int) (packedOffset << 43 >> 43);
    }

    private static int unpackOffsetZ(long packedOffset) {
        return (int) (packedOffset << 22 >> 43);
    }

    private static boolean containsOffset(long[] offsets, int count, long offset) {
        for (int i = 0; i < count; i++)
            if (offsets[i] == offset)
                return true;
        return false;
    }

    private LivingEntity createNewEntity(NMSHandler nmsHandler, Location location, StackedSpawner stackedSpawner, StackManager stackManager, EntityStackSettings entityStackSettings) {
        LivingEntity entity = nmsHandler.createNewEntityUnspawned(this.entityType, location, CreatureSpawnEvent.SpawnReason.SPAWNER);

        if (!stackManager.isAreaDisabled(location)) {
            if ((stackedSpawner.getStackSettings().isMobAIDisabled() && (!SettingKey.SPAWNER_DISABLE_MOB_AI_ONLY_PLAYER_PLACED.get() || stackedSpawner.isPlacedByPlayer())) || entityStackSettings.isMobAIDisabled())
                PersistentDataUtils.removeEntityAi(entity);

            entityStackSettings.applySpawnerSpawnedProperties(entity);
        }

        return entity;
    }

    private record SpawnResult(int spawnAmount, Set<StackedEntity> modifiedStacks, Set<StackedEntity> spawnedStacks) {
        public static SpawnResult empty() {
            return new SpawnResult(0, Set.of(), Set.of());
        }
    }

}
