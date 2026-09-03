package dev.rosewood.rosestacker.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.Lootable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class EntityUtils {

    private static final boolean HAS_FROM_MOB_SPAWNER = NMSUtil.isPaper() && NMSUtil.getVersionNumber() >= 19;
    private static final Random RANDOM = new Random();
    private static Map<EntityType, BoundingBox> cachedBoundingBoxes;

    private static final Cache<ChunkLocation, ChunkSnapshot> chunkSnapshotCache = CacheBuilder.newBuilder()
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .build();

    /**
     * Get loot for a given entity
     *
     * @param entity The entity to drop loot for
     * @param killer The player who is killing that entity
     * @param lootedLocation The location the entity is being looted at
     * @return The loot
     */
    public static Collection<ItemStack> getEntityLoot(LivingEntity entity, Player killer, Location lootedLocation) {
        if (entity instanceof Lootable lootable) {
            if (lootable.getLootTable() == null)
                return Set.of();

            LootContext lootContext = new LootContext.Builder(lootedLocation)
                    .lootedEntity(entity)
                    .killer(killer)
                    .build();

            return lootable.getLootTable().populateLoot(RANDOM, lootContext);
        }

        return Set.of();
    }

    /**
     * Get loot for a given entity with a looting modifier
     *
     * @param entity The entity to drop loot for
     * @param killer The player who is killing that entity
     * @param lootedLocation The location the entity is being looted at
     * @param lootingModifier The looting modifier to use
     * @return The loot
     */
    public static Collection<ItemStack> getEntityLoot(LivingEntity entity, Player killer, Location lootedLocation, int lootingModifier) {
        if (entity instanceof Lootable lootable) {
            if (lootable.getLootTable() == null)
                return Set.of();

            LootContext lootContext = new LootContext.Builder(lootedLocation)
                    .lootedEntity(entity)
                    .killer(killer)
                    .lootingModifier(lootingModifier)
                    .build();

            return lootable.getLootTable().populateLoot(RANDOM, lootContext);
        }

        return Set.of();
    }

    /**
     * Gets the approximate amount of experience that an entity of a certain type would drop.
     * This is only an incredibly rough estimate and isn't 1:1 with vanilla.
     *
     * @param entity The entity
     * @return The amount of experience that the entity would probably drop
     */
    public static int getApproximateExperience(LivingEntity entity) {
        if (entity == null || entity.getKiller() == null || entity instanceof NPC || entity instanceof Golem || entity instanceof Bat) {
            return 0;
        } else if (entity instanceof Animals) {
            return StackerUtils.randomInRange(1, 3);
        } else if (entity instanceof Wither) {
            return 50;
        } else if (entity instanceof Blaze || entity instanceof Guardian) {
            return 10;
        } else {
            return 5;
        }
    }

    public static boolean hasSpawnerSpawnReason(Entity entity) {
        return (NMSUtil.isPaper() && entity.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) || (HAS_FROM_MOB_SPAWNER && entity.fromMobSpawner() && !hasTrialSpawnerSpawnReason(entity));
    }

    public static boolean hasTrialSpawnerSpawnReason(Entity entity) {
        return NMSUtil.isPaper() && NMSUtil.getVersionNumber() >= 21 && entity.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER;
    }

    /**
     * A line of sight algorithm to check if two entities can see each other without obstruction
     *
     * @param entity1 The first entity
     * @param entity2 The second entity
     * @param accuracy How often should we check for obstructions? Smaller numbers = more checks (Recommended 0.75)
     * @param requireOccluding Should occluding blocks be required to count as a solid block?
     * @return true if the entities can see each other, otherwise false
     */
    public static boolean hasLineOfSight(Entity entity1, Entity entity2, double accuracy, boolean requireOccluding) {
        if (entity1 instanceof LivingEntity && RoseStacker.getInstance().getScheduler().isEntityThread(entity1)) // Try to use the NMS method if possible, it's significantly faster
            return NMSAdapter.getHandler().hasLineOfSight((LivingEntity) entity1, entity2);

        Location location1 = entity1.getLocation().clone();
        Location location2 = entity2.getLocation().clone();

        if (entity2 instanceof LivingEntity)
            location2.add(0, ((LivingEntity) entity2).getEyeHeight(), 0);

        World world = location1.getWorld();
        if (world == null || !world.equals(location2.getWorld()))
            return false;

        return hasLineOfSight(world, location1.getX(), location1.getY(), location1.getZ(),
                location2.getX(), location2.getY(), location2.getZ(), accuracy, requireOccluding);
    }

    /**
     * Checks if a Player is looking at a dropped item
     *
     * @param player The Player
     * @param item The Item
     * @return true if the Player is looking at the Item, otherwise false
     */
    public static boolean isLookingAtItem(Player player, Item item) {
        Location playerLocation = player.getEyeLocation();
        Vector playerVision = playerLocation.getDirection();

        Vector playerVector = playerLocation.toVector();
        Vector itemLocation = item.getLocation().toVector().add(new Vector(0, 0.3, 0));
        Vector direction = playerVector.clone().subtract(itemLocation).normalize();

        Vector crossProduct = playerVision.getCrossProduct(direction);
        return crossProduct.lengthSquared() <= 0.01;
    }

    /**
     * Gets all blocks that an EntityType would intersect at a Location
     *
     * @param entityType The type of Entity
     * @param location The Location the Entity would be at
     * @return A List of Blocks the Entity intersects with
     */
    public static Map<Location, Material> getIntersectingBlocks(EntityType entityType, Location location) {
        Map<Location, Material> intersectingBlocks = new HashMap<>();
        World world = location.getWorld();
        if (world == null)
            return intersectingBlocks;

        forEachIntersectingBlock(entityType, location, world, (x, y, z, type) -> {
            intersectingBlocks.put(new Location(world, x, y, z), type);
            return true;
        });

        return intersectingBlocks;
    }

    /**
     * Tests every block an EntityType would intersect at a Location against a predicate, stopping at the
     * first block that fails. Unlike {@link #getIntersectingBlocks} this allocates no Locations and no map,
     * and it reuses a chunk snapshot across the whole bounding box instead of looking one up per block.
     *
     * @param entityType The type of Entity
     * @param location The Location the Entity would be at
     * @param predicate The test to apply to each intersecting block's Material
     * @return true if every intersecting block passed the predicate, otherwise false
     */
    public static boolean allIntersectingBlocksMatch(EntityType entityType, Location location, Predicate<Material> predicate) {
        World world = location.getWorld();
        if (world == null)
            return true;

        return forEachIntersectingBlock(entityType, location, world, (x, y, z, type) -> predicate.test(type));
    }

    /**
     * Walks the blocks an EntityType would intersect at a Location, holding onto one chunk snapshot for as
     * long as consecutive blocks stay in the same chunk.
     *
     * @return true if every block was visited, false if the consumer stopped the walk early
     */
    private static boolean forEachIntersectingBlock(EntityType entityType, Location location, World world, IntersectingBlockConsumer consumer) {
        BoundingBox bounds = getBoundingBox(entityType, location).expand(-0.1);

        int minX = floorCoordinate(bounds.getMinX());
        int maxX = floorCoordinate(bounds.getMaxX());
        int minY = floorCoordinate(bounds.getMinY());
        int maxY = floorCoordinate(bounds.getMaxY());
        int minZ = floorCoordinate(bounds.getMinZ());
        int maxZ = floorCoordinate(bounds.getMaxZ());

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        ChunkSnapshot snapshot = null;
        int snapshotChunkX = 0;
        int snapshotChunkZ = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (snapshot == null || chunkX != snapshotChunkX || chunkZ != snapshotChunkZ) {
                    snapshot = getChunkSnapshot(world, chunkX, chunkZ);
                    snapshotChunkX = chunkX;
                    snapshotChunkZ = chunkZ;
                }

                for (int y = minY; y <= maxY; y++) {
                    // Out of build bounds and unreadable chunks both read as air, matching getLazyBlockMaterial
                    Material type = snapshot == null || y < minHeight || y >= maxHeight
                            ? Material.AIR
                            : snapshot.getBlockType(x & 15, y, z & 15);
                    if (!consumer.accept(x, y, z, type))
                        return false;
                }
            }
        }

        return true;
    }

    public static Material getLazyBlockMaterial(Location location) {
        World world = location.getWorld();
        if (world == null)
            return Material.AIR;

        return getLazyBlockMaterial(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Reads a block type through the short-lived chunk snapshot cache without allocating a Location.
     * Unloaded chunks and out-of-bounds coordinates read as air.
     */
    public static Material getLazyBlockMaterial(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight())
            return Material.AIR;

        // TODO: Account for the maximum size of slimes and magma cubes

        ChunkSnapshot snapshot = getChunkSnapshot(world, x >> 4, z >> 4);
        if (snapshot == null)
            return Material.AIR;

        return snapshot.getBlockType(x & 15, y, z & 15);
    }

    /**
     * Checks whether a straight line between two points is free of solid (or occluding) blocks.
     * <p>
     * Samples the segment every {@code accuracy} blocks like the Location-based variants, but works in
     * primitives and only reads a block when the sample crosses into a new block, so a 32 block ray costs
     * a few dozen snapshot reads and zero allocations instead of ~40 Location and Vector clones.
     *
     * @param world The world both points are in
     * @param accuracy How often to sample along the line, in blocks (0.75 recommended)
     * @param requireOccluding true to only treat occluding blocks as obstructions
     * @return true if nothing solid sits between the points
     */
    public static boolean hasLineOfSight(World world, double x1, double y1, double z1, double x2, double y2, double z2, double accuracy, boolean requireOccluding) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0)
            return true;

        double stepX = dx / distance * accuracy;
        double stepY = dy / distance * accuracy;
        double stepZ = dz / distance * accuracy;

        int lastBlockX = Integer.MIN_VALUE, lastBlockY = Integer.MIN_VALUE, lastBlockZ = Integer.MIN_VALUE;
        double x = x1, y = y1, z = z1;
        for (double travelled = 0; travelled < distance; travelled += accuracy) {
            int blockX = floorCoordinate(x);
            int blockY = floorCoordinate(y);
            int blockZ = floorCoordinate(z);
            if (blockX != lastBlockX || blockY != lastBlockY || blockZ != lastBlockZ) {
                lastBlockX = blockX;
                lastBlockY = blockY;
                lastBlockZ = blockZ;

                Material type = getLazyBlockMaterial(world, blockX, blockY, blockZ);
                if (type.isSolid() && (!requireOccluding || StackerUtils.isOccluding(type)))
                    return false;
            }

            x += stepX;
            y += stepY;
            z += stepZ;
        }

        return true;
    }

    private static ChunkSnapshot getChunkSnapshot(World world, int chunkX, int chunkZ) {
        try {
            ChunkLocation pair = new ChunkLocation(world.getName(), chunkX, chunkZ);
            ChunkSnapshot snapshot = chunkSnapshotCache.getIfPresent(pair);
            if (snapshot != null)
                return snapshot;

            // Never load chunks here; snapshotting an unloaded chunk fires a synchronous chunk load whose
            // ChunkLoadEvent re-enters this method for the same chunk and recurses until the server dies
            if (!world.isChunkLoaded(chunkX, chunkZ))
                return null;

            // Snapshot outside a cache loader so a re-entrant call for the same chunk can't trip
            // Guava's recursive load detection
            snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot();
            chunkSnapshotCache.put(pair, snapshot);
            return snapshot;
        } catch (Exception e) {
            RoseStacker.getInstance().getLogger().warning("Failed to fetch chunk snapshot at " + world.getName() + " " + chunkX + "," + chunkZ);
            e.printStackTrace();
            return null;
        }
    }

    @FunctionalInterface
    private interface IntersectingBlockConsumer {

        /**
         * @return true to keep walking, false to stop
         */
        boolean accept(int x, int y, int z, Material type);

    }

    /**
     * Gets the would-be bounding box of an entity at a location
     *
     * @param entityType The entity type the entity would be
     * @param location The location the entity would be at
     * @return A bounding box for the entity type at the location
     */
    public static BoundingBox getBoundingBox(EntityType entityType, Location location) {
        if (cachedBoundingBoxes == null)
            cachedBoundingBoxes = new HashMap<>();

        if (entityType == EntityType.SLIME || entityType == EntityType.MAGMA_CUBE)
            return new BoundingBox(-2.1, 0, -2.1, 2.1, 2.1, 2.1).shift(location.clone().subtract(0.5, 0, 0.5));

        BoundingBox boundingBox = cachedBoundingBoxes.get(entityType);
        if (boundingBox == null) {
            if (entityType == EntityType.ENDER_DRAGON) {
                boundingBox = new BoundingBox(-4, 0, -4, 4, 8, 4);
            } else {
                LivingEntity entity = null;
                try {
                    entity = NMSAdapter.getHandler().createNewEntityUnspawned(entityType, new Location(location.getWorld(), 0, 0, 0), CreatureSpawnEvent.SpawnReason.CUSTOM);
                } catch (Exception ignored) { }

                if (entity != null) {
                    boundingBox = entity.getBoundingBox();
                    cachedBoundingBoxes.put(entityType, boundingBox);
                } else {
                    // This should never happen unless the entity type is not a LivingEntity
                    boundingBox = new BoundingBox();
                }
            }
        }

        boundingBox = boundingBox.clone();
        boundingBox.shift(location.clone().subtract(0.5, 0, 0.5));
        return boundingBox;
    }

    private static int floorCoordinate(double value) {
        int floored = (int) value;
        return value < (double) floored ? floored - 1 : floored;
    }

    public static void clearCache() {
        cachedBoundingBoxes = null;
    }

    private record ChunkLocation(String world, int x, int z) { }

}
