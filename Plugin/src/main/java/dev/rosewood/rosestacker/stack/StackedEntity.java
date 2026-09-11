package dev.rosewood.rosestacker.stack;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import dev.rosewood.rosegarden.utils.EntitySpawnUtil;
import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.api.RoseStackerAPI;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.event.EntityStackMultipleDeathEvent;
import dev.rosewood.rosestacker.event.EntityStackMultipleDeathEvent.EntityDrops;
import dev.rosewood.rosestacker.hook.SpawnerFlagPersistenceHook;
import dev.rosewood.rosestacker.hook.WorldGuardHook;
import dev.rosewood.rosestacker.manager.EntityCacheManager;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.manager.StackSettingManager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorage;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorageType;
import dev.rosewood.rosestacker.stack.settings.EntityStackSettings;
import dev.rosewood.rosestacker.stack.settings.MultikillBound;
import dev.rosewood.rosestacker.utils.DataUtils;
import dev.rosewood.rosestacker.utils.EntityUtils;
import dev.rosewood.rosestacker.utils.ItemUtils;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import dev.rosewood.rosestacker.utils.StackerUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class StackedEntity extends Stack<EntityStackSettings> implements Comparable<StackedEntity> {

    private LivingEntity entity;
    private StackedEntityDataStorage stackedEntityDataStorage;
    private int npcCheckCounter;
    private boolean npc;

    private String displayName;
    private boolean displayNameVisible;
    private double x, y, z;
    private int lastModifiedTicks;

    // Persistent data container flags, read once and kept here. The stacking conditions consult these for
    // every candidate pair of every stacking pass, and a container lookup is a map lookup plus two string
    // builds. The setters in PersistentDataUtils clear these through the stack, and they are dropped
    // whenever the head entity is replaced, so a stale value cannot survive a chunk unload cycle either.
    private volatile Boolean spawnedFromSpawner;
    private volatile Boolean spawnedFromTrialSpawner;
    private volatile Boolean spawnedFromDispenser;
    private volatile Boolean unstackable;
    private volatile Boolean aiDisabled;

    // The players whose client currently has this entity, maintained by EntityTrackingListener. The
    // nametag pass used to schedule a main-thread task per stack purely so it could call
    // Entity#getTrackedBy() on the right thread; owning the set means it never has to.
    private volatile Set<UUID> trackingPlayers;

    // Unstack pass bookkeeping; see needsUnstackCheck()
    private int lastUnstackCheckModifiedTicks = Integer.MIN_VALUE;
    private int unstackCheckIdleCycles;

    // Autosave bookkeeping; see needsSave()
    private int lastSavedModifiedTicks = Integer.MIN_VALUE;
    private int lastSavedStackSize = -1;

    // Set while a freshly created stack is being instant-stacked, before its entity is valid
    private volatile boolean newlyCreated;

    // The nametag state each tracking player last received, so periodic nametag passes only send a packet
    // when the name or visibility actually changed for that player. Created lazily; most stacks never have
    // a player near them. Entries are dropped when a player stops tracking the entity (see
    // EntityTrackingListener) so a re-tracked entity, whose client just received vanilla metadata, is resent.
    private volatile Map<UUID, NametagState> sentNametags;

    // Without track/untrack events we cannot tell when a client dropped our tag, so fall back to resending
    private static volatile boolean nametagStateTrackingEnabled = true;

    // Whether display updates may be built and sent from the async passes instead of a main-thread task
    private static volatile boolean asyncDisplayUpdates;

    /**
     * An idle stack is re-checked by the unstack pass this many cycles apart even when nothing has touched
     * it, so a stack that somehow mutated without bumping its modified tick cannot get stuck stacked
     * forever. At the default unstack frequency of 50 ticks that is a worst case of 10 seconds.
     */
    private static final int IDLE_UNSTACK_RECHECK_CYCLES = 4;

    /**
     * A player and a stack that both stand still can only have the wall between them change when blocks
     * change, so the ray is skipped and re-run this many nametag cycles later. At the default nametag
     * update frequency of 30 ticks that is a worst case of 4.5 seconds, in the same range as the 5 seconds
     * HologramManager already allows its watchers, and less than the staleness the chunk snapshot cache
     * this replaces used to add on top of it.
     */
    private static final int IDLE_LINE_OF_SIGHT_RECHECK_CYCLES = 3;
    private static final double MOVEMENT_THRESHOLD_SQRD = 0.25 * 0.25;

    public static void setNametagStateTrackingEnabled(boolean enabled) {
        nametagStateTrackingEnabled = enabled;
    }

    /**
     * @return true if the per-player nametag state is being tracked, otherwise false
     */
    public static boolean isNametagStateTrackingEnabled() {
        return nametagStateTrackingEnabled;
    }

    /**
     * Sets whether display updates may be sent from the async passes.
     *
     * @param enabled true to send display updates off the main thread, otherwise false
     */
    public static void setAsyncDisplayUpdates(boolean enabled) {
        asyncDisplayUpdates = enabled;
    }

    /**
     * Nametags are per-player metadata packets, which Paper is happy to have queued from any thread. Doing
     * that instead of scheduling a task per (stack, player) pair is what takes the nametag pass off the
     * main thread entirely. It requires the track/untrack events, because without them we have no
     * off-thread way to know which clients actually have the entity.
     *
     * @return true if display updates should be built and sent on whichever thread asks for them
     */
    public static boolean isAsyncDisplayUpdates() {
        return asyncDisplayUpdates && nametagStateTrackingEnabled;
    }

    private EntityStackSettings stackSettings;

    public StackedEntity(LivingEntity entity, StackedEntityDataStorage stackedEntityDataStorage, boolean updateDisplay) {
        this.entity = entity;
        this.stackedEntityDataStorage = stackedEntityDataStorage;
        this.npcCheckCounter = 0;

        this.displayName = null;
        this.displayNameVisible = false;
        this.lastModifiedTicks = this.entity != null ? this.entity.getTicksLived() : 0;

        if (this.entity != null) {
            this.stackSettings = RoseStacker.getInstance().getManager(StackSettingManager.class).getEntityStackSettings(this.entity);

            // An entity can already be tracked by players before it becomes a stack, on chunk load or on a
            // plugin reload. The track events only fire on a change, so seed the set here when we are on a
            // thread that may read it; otherwise it stays empty until the next track event fills it.
            if (isAsyncDisplayUpdates() && this.entity.isValid() && ThreadUtils.isEntityThread(this.entity))
                this.seedTrackingPlayers();

            if (updateDisplay)
                this.updateDisplaySafely();
        }
    }

    public StackedEntity(LivingEntity entity, StackedEntityDataStorage stackedEntityDataStorage) {
        this(entity, stackedEntityDataStorage, true);
    }

    public StackedEntity(LivingEntity entity) {
        this(entity, NMSAdapter.getHandler().createEntityDataStorage(entity, RoseStacker.getInstance().getManager(StackManager.class).getEntityDataStorageType(entity.getType())), true);
    }

    // We are going to check if this entity is an NPC multiple times, since MythicMobs annoyingly doesn't
    // actually register it as an NPC until a few ticks after it spawns
    public boolean checkNPC() {
        if (!this.npc && this.npcCheckCounter > 0) {
            this.npcCheckCounter--;
        }
        return this.npc;
    }

    public LivingEntity getEntity() {
        return this.entity;
    }

    public int getLastModifiedTicks() {
        return this.lastModifiedTicks;
    }

    public void updateEntity() {
        LivingEntity entity = (LivingEntity) Bukkit.getEntity(this.entity.getUniqueId());
        if (entity == null || entity == this.entity)
            return;

        this.entity = entity;
        this.onEntityReplaced();
        this.stackedEntityDataStorage.updateEntity(entity);
        this.resetHasMoved();
        this.updateDisplaySafely();
    }

    public void increaseStackSize(LivingEntity entity) {
        this.increaseStackSize(entity, true);
    }

    public void increaseStackSize(LivingEntity entity, boolean updateDisplay) {
        Runnable task = () -> {
            this.stackedEntityDataStorage.add(entity);
            this.markModified();
            if (updateDisplay)
                this.updateDisplaySafely();
        };

        // EnderDragonChangePhaseEvents is called when reading the entity NBT data.
        // Since we usually do this async and the event isn't allowed to be async, Spigot throws a fit.
        // We switch over to a non-async thread specifically for ender dragons because of this.
        if (!Bukkit.isPrimaryThread() && entity instanceof EnderDragon) {
            ThreadUtils.runSync(task);
        } else {
            task.run();
        }
    }

    /**
     * Increases the stack size by a certain amount, clones the main entity
     *
     * @param updateDisplay Whether to update the entity's nametag or not
     */
    public void increaseStackSize(int amount, boolean updateDisplay) {
        this.stackedEntityDataStorage.addClones(amount);
        this.markModified();

        if (updateDisplay)
            this.updateDisplaySafely();
    }

    public void increaseStackSize(StackedEntityDataStorage serializedStackedEntities) {
        this.stackedEntityDataStorage.addAll(serializedStackedEntities);
        this.markModified();
        this.updateDisplaySafely();
    }

    /**
     * Unstacks the visible entity from the stack and moves the next in line to the front
     *
     * @return The new StackedEntity of size 1 that was just created
     */
    public StackedEntity decreaseStackSize() {
        if (this.stackedEntityDataStorage.isEmpty())
            return null;

        StackManager stackManager = RoseStacker.getInstance().getManager(StackManager.class);
        EntityCacheManager entityCacheManager = RoseStacker.getInstance().getManager(EntityCacheManager.class);
        LivingEntity oldEntity = this.entity;

        stackManager.setEntityStackingTemporarilyDisabled(true);
        this.entity = this.stackedEntityDataStorage.pop().createEntity(oldEntity.getLocation(), true, oldEntity.getType());
        this.onEntityReplaced();
        stackManager.setEntityStackingTemporarilyDisabled(false);
        this.stackSettings.applyUnstackProperties(this.entity, oldEntity);
        stackManager.updateStackedEntityKey(oldEntity, this);
        entityCacheManager.preCacheEntity(this.entity);
        this.entity.setVelocity(this.entity.getVelocity().add(Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).multiply(0.01))); // Nudge the entity to unstack it from the old entity

        // Attempt to prevent adult entities from going into walls when a baby entity gets unstacked
        if (oldEntity instanceof Ageable ageable1 && this.entity instanceof Ageable ageable2 && !ageable1.isAdult() && ageable2.isAdult()) {
            Location centered = ageable1.getLocation();
            centered.setX(centered.getBlockX() + 0.5);
            centered.setZ(centered.getBlockZ() + 0.5);
            ageable2.teleport(centered);
        }

        this.stackedEntityDataStorage.updateEntity(this.entity);
        this.markModified();
        this.updateDisplaySafely();
        PersistentDataUtils.applyDisabledAi(this.entity);

        DataUtils.clearStackedEntityData(oldEntity);
        return new StackedEntity(oldEntity, NMSAdapter.getHandler().createEntityDataStorage(oldEntity, RoseStacker.getInstance().getManager(StackManager.class).getEntityDataStorageType(oldEntity.getType())));
    }

    /**
     * @deprecated Use {@link #getDataStorage()} instead
     */
    @Deprecated(forRemoval = true)
    public StackedEntityDataStorage getStackedEntityNBT() {
        return this.getDataStorage();
    }

    public StackedEntityDataStorage getDataStorage() {
        return this.stackedEntityDataStorage;
    }

    /**
     * Warning! This method should not be used outside this plugin.
     * This method overwrites the data storage and NOTHING ELSE.
     * If the stack size were to change, there would be no way of detecting it, you have been warned!
     *
     * @param stackedEntityDataStorage The data storage to overwrite with
     * @deprecated Use {@link #setDataStorage(StackedEntityDataStorage)} instead
     */
    @Deprecated(forRemoval = true)
    public void setStackedEntityNBT(StackedEntityDataStorage stackedEntityDataStorage) {
        this.setDataStorage(stackedEntityDataStorage);
    }

    /**
     * Warning! This method should not be used outside this plugin.
     * This method overwrites the data storage and NOTHING ELSE.
     * If the stack size were to change, there would be no way of detecting it, you have been warned!
     *
     * @param stackedEntityDataStorage The data storage to overwrite with
     */
    public void setDataStorage(StackedEntityDataStorage stackedEntityDataStorage) {
        stackedEntityDataStorage.updateEntity(this.entity);
        this.stackedEntityDataStorage = stackedEntityDataStorage;
        this.markModified();
        this.updateDisplaySafely();
    }

    private void markModified() {
        this.lastModifiedTicks = this.entity != null ? this.entity.getTicksLived() : 0;
    }

    /**
     * Drops all loot and experience for all internally-stacked entities.
     * Does not include loot for the current entity.
     *
     * @param existingLoot The loot from this.entity, nullable
     * @param droppedExp The exp dropped from this.entity
     */
    public void dropStackLoot(Collection<ItemStack> existingLoot, int droppedExp) {
        this.dropPartialStackLoot(this.getStackSize(), existingLoot, droppedExp);
    }

    /**
     * Drops loot for entities that are part of the stack.
     * Does not include loot for the current entity.
     *
     * @param existingLoot The loot from this.entity, nullable
     * @param droppedExp The exp dropped from this.entity
     */
    public void dropPartialStackLoot(int count, Collection<ItemStack> existingLoot, int droppedExp) {
        int originalStackSize = this.getStackSize();
        this.calculateAndDropPartialStackLoot(() -> this.calculateEntityDrops(count - 1, false, droppedExp, null, null, new EntityDrops(new ArrayList<>(existingLoot), droppedExp), originalStackSize, count));
    }

    /**
     * @deprecated this should be static, it doesn't really use the stacked entity state at all
     */
    @Deprecated
    public void dropPartialStackLoot(Collection<LivingEntity> internalEntities) {
        int killedEntities = internalEntities.size();
        int originalStackSize = this.getStackSize() + killedEntities;
        this.calculateAndDropPartialStackLoot(() -> {
            List<EntityDataEntry> entityDataEntries = internalEntities.stream().map(EntityDataEntry::createFromEntity).toList();
            return this.calculateEntityDrops(entityDataEntries, EntityUtils.getApproximateExperience(this.entity), null, null, null, originalStackSize, killedEntities);
        });
    }

    /**
     * @deprecated this should be static, it doesn't really use the stacked entity state at all
     */
    @Deprecated
    public void dropPartialStackLoot(Collection<EntityDataEntry> internalEntityData, Collection<ItemStack> existingLoot, int droppedExp) {
        LivingEntity thisEntity = this.entity;
        int killedEntities = internalEntityData.size() + 1;
        int originalStackSize = this.getStackSize() + internalEntityData.size() + 1;
        this.calculateAndDropPartialStackLoot(() ->
                this.calculateEntityDrops(internalEntityData, droppedExp, null, thisEntity, new EntityDrops(new ArrayList<>(existingLoot), droppedExp), originalStackSize, killedEntities));
    }

    private void calculateAndDropPartialStackLoot(Supplier<EntityDrops> calculator) {
        // The stack loot can either be processed synchronously or asynchronously depending on a setting
        // It should always be processed async unless errors are caused by other plugins
        Player killer = this.entity.getKiller();
        boolean async = SettingKey.ENTITY_DEATH_EVENT_RUN_ASYNC.get();
        Runnable mainTask = () -> {
            EntityDrops drops = calculator.get();

            Runnable finishTask = () -> {
                Location location = this.entity.getLocation();
                RoseStacker.getInstance().getManager(StackManager.class).preStackItems(drops.getDrops(), location, false);
                int finalDroppedExp = drops.getExperience();
                if (SettingKey.ENTITY_DROP_ACCURATE_EXP.get() && finalDroppedExp > 0 && WorldGuardHook.testCanDropExperience(killer, location))
                    StackerUtils.dropExperience(location, finalDroppedExp, finalDroppedExp, finalDroppedExp / 2);
            };

            if (!Bukkit.isPrimaryThread()) {
                ThreadUtils.runSync(finishTask);
            } else {
                finishTask.run();
            }
        };

        if (async && Bukkit.isPrimaryThread()) {
            ThreadUtils.runAsync(mainTask);
        } else if (!async && !Bukkit.isPrimaryThread()) {
            ThreadUtils.runSync(mainTask);
        } else {
            mainTask.run();
        }
    }

    /**
     * Calculates the entity drops. May be called async or sync.
     *
     * @param count The number of entities to drop items for
     * @param includeMainEntity Whether to include the main entity in the calculation
     * @param entityExpValue The exp value of the entity
     * @return The calculated entity drops
     */
    @ApiStatus.Internal
    public EntityDrops calculateEntityDrops(int count, boolean includeMainEntity, int entityExpValue) {
        return this.calculateEntityDrops(count, includeMainEntity, entityExpValue, null);
    }

    /**
     * Calculates the entity drops. May be called async or sync.
     *
     * @param count The number of entities to drop items for
     * @param includeMainEntity Whether to include the main entity in the calculation
     * @param entityExpValue The exp value of the entity
     * @param lootingModifier The looting modifier, nullable
     * @return The calculated entity drops
     */
    @ApiStatus.Internal
    public EntityDrops calculateEntityDrops(int count, boolean includeMainEntity, int entityExpValue, Integer lootingModifier) {
        return this.calculateEntityDrops(count, includeMainEntity, entityExpValue, lootingModifier, null, null, null, null);
    }

    /**
     * Calculates the entity drops. May be called async or sync.
     *
     * @param internalEntities The entities to calculate drops for, unapproximated
     * @param entityExpValue The exp value of the entity
     * @param lootingModifier The looting modifier, nullable, defaults to the killer's looting value
     * @param mainEntity The main entity to use for loot calculations, primarily used to copy entity properties such as the killer, nullable, defaults to the stack entity
     * @param mainEntityDrops The main entity drops to include in the loot calculations, nullable
     * @return The calculated entity drops
     */
    @ApiStatus.Internal
    public EntityDrops calculateEntityDrops(Collection<EntityDataEntry> internalEntities, int entityExpValue, Integer lootingModifier,
                                            LivingEntity mainEntity, EntityDrops mainEntityDrops,
                                            Integer originalStackSize, Integer entityKillCount) {
        // Cache the current entity just in case it somehow changes while we are processing the loot
        if (mainEntity == null)
            mainEntity = this.entity;

        int count = internalEntities.size();
        Location location = this.entity.getLocation();
        EntityType type = this.entity.getType();
        List<LivingEntity> finalEntities = new ArrayList<>();
        double multiplier = 1;
        int threshold = SettingKey.ENTITY_LOOT_APPROXIMATION_THRESHOLD.get();
        int approximationAmount = SettingKey.ENTITY_LOOT_APPROXIMATION_AMOUNT.get();
        if (SettingKey.ENTITY_LOOT_APPROXIMATION_ENABLED.get() && count > threshold) {
            Iterator<EntityDataEntry> entryIterator = internalEntities.iterator();
            int offset = mainEntityDrops != null ? 1 : 0; // If main entity drops are present, we've already approximated one entity, make sure to account for it
            while (entryIterator.hasNext() && finalEntities.size() < approximationAmount - offset)
                finalEntities.add(entryIterator.next().createEntity(location, false, type));
            multiplier = (internalEntities.size() + offset) / (double) approximationAmount;
        } else {
            finalEntities.addAll(internalEntities.stream().map(x -> x.createEntity(location, false, type)).toList());
        }

        return this.calculateEntityDrops(finalEntities, multiplier, entityExpValue, lootingModifier, mainEntity, mainEntityDrops, originalStackSize, entityKillCount);
    }

    /**
     * Calculates the entity drops. May be called async or sync.
     *
     * @param count The number of entities to drop items for
     * @param includeMainEntity Whether to include the main entity in the calculation
     * @param entityExpValue The exp value of the entity
     * @param lootingModifier The looting modifier, nullable, defaults to the killer's looting value
     * @param mainEntity The main entity to use for loot calculations, primarily used to copy entity properties such as the killer, nullable, defaults to the stack entity
     * @param mainEntityDrops The main entity drops to include in the loot calculations, nullable
     * @return The calculated entity drops
     */
    @ApiStatus.Internal
    public EntityDrops calculateEntityDrops(int count, boolean includeMainEntity, int entityExpValue, Integer lootingModifier,
                                            LivingEntity mainEntity, EntityDrops mainEntityDrops,
                                            Integer originalStackSize, Integer entityKillCount) {
        // Cache the current entity just in case it somehow changes while we are processing the loot
        if (mainEntity == null)
            mainEntity = this.entity;

        count = Math.min(count, this.getStackSize() - 1);

        List<LivingEntity> finalEntities = new ArrayList<>();
        if (includeMainEntity && mainEntityDrops == null) {
            finalEntities.add(mainEntity);
            count++;
        }

        double multiplier = 1;
        int threshold = SettingKey.ENTITY_LOOT_APPROXIMATION_THRESHOLD.get();
        int approximationAmount = SettingKey.ENTITY_LOOT_APPROXIMATION_AMOUNT.get();
        if (SettingKey.ENTITY_LOOT_APPROXIMATION_ENABLED.get() && count > threshold) {
            int offset = mainEntityDrops != null ? 1 : 0; // If main entity drops are present, we've already approximated one entity, make sure to account for it
            this.stackedEntityDataStorage.forEachCapped(approximationAmount - offset, finalEntities::add);
            multiplier = (count + offset) / (double) approximationAmount;
        } else {
            this.stackedEntityDataStorage.forEachCapped(count, finalEntities::add);
        }

        return this.calculateEntityDrops(finalEntities, multiplier, entityExpValue, lootingModifier, mainEntity, mainEntityDrops, originalStackSize, entityKillCount);
    }

    private EntityDrops calculateEntityDrops(List<LivingEntity> stackEntities, double multiplier, int entityExpValue, Integer lootingModifier,
                                             LivingEntity mainEntity, EntityDrops mainEntityDrops, Integer originalStackSize, Integer entityKillCount) {
        if (originalStackSize == null)
            originalStackSize = this.getStackSize();

        if (entityKillCount == null)
            entityKillCount = stackEntities.size() + (mainEntityDrops != null ? 1 : 0);

        boolean propagateKiller = SettingKey.ENTITY_LOOT_PROPAGATE_KILLER.get();
        boolean fromSpawner = this.isSpawnedFromSpawner();
        Location location = mainEntity.getLocation();
        Player killer = propagateKiller ? mainEntity.getKiller() : null;
        Entity froglightKiller = NMSUtil.getVersionNumber() >= 19 && mainEntity.getType() == EntityType.MAGMA_CUBE && mainEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent && damageEvent.getDamager().getType() == EntityType.FROG ? damageEvent.getDamager() : null;
        boolean callEvents = !RoseStackerAPI.getInstance().isEntityStackMultipleDeathEventCalled();
        boolean isAnimal = mainEntity instanceof Animals;
        boolean isWither = mainEntity.getType() == EntityType.WITHER;
        boolean killedByWither = mainEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent
                && (damageEvent.getDamager().getType() == EntityType.WITHER || damageEvent.getDamager().getType() == EntityType.WITHER_SKULL);
        boolean isSlime = mainEntity.getType() == EntityType.SLIME || mainEntity.getType() == EntityType.MAGMA_CUBE;
        boolean isAccurateSlime = isSlime && this.stackSettings.getSettingValue(EntityStackSettings.SLIME_ACCURATE_DROPS_WITH_KILL_ENTIRE_STACK_ON_DEATH).getBoolean();

        ListMultimap<LivingEntity, EntityDrops> entityDrops = MultimapBuilder.linkedHashKeys().arrayListValues().build();
        if (mainEntityDrops != null)
            entityDrops.put(mainEntity, mainEntityDrops);

        NMSHandler nmsHandler = NMSAdapter.getHandler();
        for (LivingEntity entity : stackEntities) {
            // Propagate fire ticks and last damage cause
            entity.setFireTicks(mainEntity.getFireTicks());
            if (fromSpawner)
                SpawnerFlagPersistenceHook.flagSpawnerSpawned(entity);
            nmsHandler.setLastHurtBy(entity, killer);
            entity.setLastDamageCause(propagateKiller ? mainEntity.getLastDamageCause() : null);

            int iterations = 1;
            if (isSlime) {
                int size = switch (entity.getType()) {
                    case SLIME -> ((Slime) entity).getSize();
                    case MAGMA_CUBE -> ((MagmaCube) entity).getSize();
                    default -> throw new IllegalStateException("Invalid slime type");
                };
                if (isAccurateSlime) {
                    int totalSlimes = 1;
                    while (size > 1) {
                        size /= 2;
                        int currentSlimes = totalSlimes;
                        totalSlimes = StackerUtils.randomInRange(currentSlimes * 2, currentSlimes * 4);
                    }
                    iterations = totalSlimes;
                }
                switch (entity.getType()) { // Slimes require size 1 to drop items, magma cubes require > size 1
                    case SLIME -> ((Slime) entity).setSize(1);
                    case MAGMA_CUBE -> ((MagmaCube) entity).setSize(2);
                }
            }

            boolean isBaby = isAnimal && !((Animals) entity).isAdult();
            int desiredExp = isBaby ? 0 : entityExpValue;
            for (int i = 0; i < iterations; i++) {
                List<ItemStack> entityItems;
                if (isBaby) {
                    entityItems = new ArrayList<>();
                } else {
                    if (lootingModifier != null) {
                        entityItems = new ArrayList<>(EntityUtils.getEntityLoot(entity, killer, location, lootingModifier));
                    } else {
                        entityItems = new ArrayList<>(EntityUtils.getEntityLoot(entity, killer, location));
                    }
                }

                if (isWither)
                    entityItems.add(new ItemStack(Material.NETHER_STAR));
                if (killedByWither)
                    entityItems.add(new ItemStack(Material.WITHER_ROSE));
                if (froglightKiller != null) {
                    Frog frog = (Frog) froglightKiller;
                    Material froglightType = switch (frog.getVariant().getKey().getKey()) {
                        case "cold" -> Material.VERDANT_FROGLIGHT;
                        case "temperate" -> Material.OCHRE_FROGLIGHT;
                        case "warm" -> Material.PEARLESCENT_FROGLIGHT;
                        default -> {
                            RoseStacker.getInstance().getLogger().warning("Unhandled frog type: " + frog.getVariant().getKey());
                            yield null;
                        }
                    };
                    if (froglightType != null)
                        entityItems.add(new ItemStack(froglightType));
                }

                int entityExperience;
                if (callEvents) {
                    EntityDeathEvent deathEvent = nmsHandler.createAsyncEntityDeathEvent(entity, entityItems, desiredExp);
                    Bukkit.getPluginManager().callEvent(deathEvent);
                    entityExperience = deathEvent.getDroppedExp();
                } else {
                    entityExperience = desiredExp;
                }

                entityDrops.put(entity, new EntityDrops(entityItems, entityExperience));
            }

            // Prevent magma cubes from splitting
            if (isSlime && entity.getType() == EntityType.MAGMA_CUBE)
                ((MagmaCube) entity).setSize(1);

            if (fromSpawner)
                SpawnerFlagPersistenceHook.unflagSpawnerSpawned(entity);
        }

        // Call the EntityStackMultipleDeathEvent if enabled
        if (!callEvents) {
            EntityStackMultipleDeathEvent event = new EntityStackMultipleDeathEvent(this, entityDrops, originalStackSize, entityKillCount, multiplier, mainEntity, killer, this::calculateFinalEntityDrops);
            Bukkit.getPluginManager().callEvent(event);
        }

        return this.calculateFinalEntityDrops(entityDrops, multiplier);
    }

    private EntityDrops calculateFinalEntityDrops(Multimap<LivingEntity, EntityDrops> entityDrops, double multiplier) {
        List<ItemStack> finalItems = new ArrayList<>();
        int finalExp = 0;
        for (EntityDrops drops : entityDrops.values()) {
            finalItems.addAll(drops.getDrops());
            finalExp += drops.getExperience();
        }

        // Multiply loot
        if (multiplier > 1) {
            finalItems = ItemUtils.getMultipliedItemStacks(finalItems, multiplier, true);
            finalExp = (int) Math.min(Math.round(finalExp * multiplier), Integer.MAX_VALUE);
        }

        return new EntityDrops(finalItems, finalExp);
    }

    /**
     * @return true if this entity should stay stacked, otherwise false
     */
    public boolean shouldStayStacked() {
        if (this.entity == null || this.stackSettings == null || this.stackedEntityDataStorage.isEmpty())
            return true;

        // Ender dragons call an EnderDragonChangePhaseEvent upon entity construction
        // We want to be able to do this check async, we just won't let ender dragons unstack without dying
        if (this.entity instanceof EnderDragon)
            return true;

        // SIMPLE storage doesn't keep any per-entity data; every entry it hands out is this stack's own
        // entity re-serialized at read time. Materializing one costs an NBT save plus a throwaway entity
        // construction for every stack on every unstack cycle, and the result is only ever compared
        // against the entity it was copied from, so compare the stack against itself instead. The
        // conditions then take their entity1 == entity2 path, which the stacking code already relies on
        // when looking for a stack to merge into, and reach the same verdict.
        //
        // NBT storage reaches the same place whenever the entry at the front of the storage carries nothing
        // that a stack condition could read differently from the head entity, which is the normal shape of
        // a spawner-fed farm stack: the members are clones that differ only in health, attributes and
        // equipment, all of which the storage strips into its shared base tag anyway.
        if (this.stackedEntityDataStorage.getType() == StackedEntityDataStorageType.SIMPLE
                || this.stackedEntityDataStorage.isHeadRepresentative())
            return this.stackSettings.testCanStackWith(this, this, true);

        // The wrapper around the deserialized copy only exists so the conditions can read getEntity() and
        // getStackSize(); nothing ever reads its data storage. Building an NBT storage for it re-serialized
        // the copy straight back to NBT for every stack on every unstack cycle, so use a SIMPLE storage,
        // which stores nothing up front and reports the same stack size of 1.
        NMSHandler nmsHandler = NMSAdapter.getHandler();
        LivingEntity entity = this.stackedEntityDataStorage.peek().createEntity(this.entity.getLocation(), false, this.entity.getType());
        StackedEntity stackedEntity = new StackedEntity(entity, nmsHandler.createEntityDataStorage(entity, StackedEntityDataStorageType.SIMPLE), false);
        return this.stackSettings.testCanStackWith(this, stackedEntity, true);
    }

    @Override
    public int getStackSize() {
        return this.stackedEntityDataStorage.size() + 1;
    }

    @Override
    public Location getLocation() {
        return this.entity.getLocation();
    }

    /**
     * @return true if the head entity was spawned from a spawner, otherwise false
     */
    public boolean isSpawnedFromSpawner() {
        Boolean value = this.spawnedFromSpawner;
        if (value != null)
            return value;

        boolean spawnedFromSpawner = PersistentDataUtils.isSpawnedFromSpawner(this.entity);
        // Half of this answer is the entity's spawn reason, which is only set once the entity has been
        // added to the world, so an answer computed before that is not worth remembering
        if (this.entity.isValid())
            this.spawnedFromSpawner = spawnedFromSpawner;
        return spawnedFromSpawner;
    }

    /**
     * @return true if the head entity was spawned from a trial spawner, otherwise false
     */
    public boolean isSpawnedFromTrialSpawner() {
        Boolean value = this.spawnedFromTrialSpawner;
        if (value != null)
            return value;

        boolean spawnedFromTrialSpawner = PersistentDataUtils.isSpawnedFromTrialSpawner(this.entity);
        if (this.entity.isValid()) // Same spawn reason caveat as isSpawnedFromSpawner
            this.spawnedFromTrialSpawner = spawnedFromTrialSpawner;
        return spawnedFromTrialSpawner;
    }

    /**
     * @return true if the head entity was spawned from a spawn egg in a dispenser, otherwise false
     */
    public boolean isSpawnedFromDispenser() {
        Boolean value = this.spawnedFromDispenser;
        if (value == null)
            this.spawnedFromDispenser = value = PersistentDataUtils.isSpawnedFromDispenser(this.entity);
        return value;
    }

    /**
     * @return true if the head entity is marked unstackable, otherwise false
     */
    public boolean isUnstackable() {
        Boolean value = this.unstackable;
        if (value == null)
            this.unstackable = value = PersistentDataUtils.isUnstackable(this.entity);
        return value;
    }

    /**
     * @return true if the head entity has its AI disabled, otherwise false
     */
    public boolean isAiDisabled() {
        Boolean value = this.aiDisabled;
        if (value == null)
            this.aiDisabled = value = PersistentDataUtils.isAiDisabled(this.entity);
        return value;
    }

    /**
     * Drops everything that was about the previous head entity after it has been swapped out: the cached
     * container flags, whatever each client was last sent (their client has a different entity now) and
     * the tracked player set, which is refilled by the track events for the new entity.
     */
    private void onEntityReplaced() {
        this.invalidateCachedFlags();
        this.clearNametagStates();

        Set<UUID> tracking = this.trackingPlayers;
        if (tracking != null)
            tracking.clear();

        if (isAsyncDisplayUpdates() && this.entity != null && this.entity.isValid() && ThreadUtils.isEntityThread(this.entity))
            this.seedTrackingPlayers();
    }

    /**
     * Forgets every cached persistent data container flag so the next read goes back to the container.
     */
    public void invalidateCachedFlags() {
        this.spawnedFromSpawner = null;
        this.spawnedFromTrialSpawner = null;
        this.spawnedFromDispenser = null;
        this.unstackable = null;
        this.aiDisabled = null;
    }

    public String getDisplayName() {
        if (this.displayName != null)
            return this.displayName;

        if (!SettingKey.ENTITY_DISPLAY_TAGS.get() || this.stackSettings == null || this.entity == null) {
            this.displayNameVisible = false;
            return this.displayName = this.entity == null ? null : this.entity.getCustomName();
        }

        if (this.entity.isDead()) {
            this.displayNameVisible = false;
            return null;
        }

        String customName = this.entity.getCustomName();
        if (this.getStackSize() > 1 || SettingKey.ENTITY_DISPLAY_TAGS_SINGLE.get()) {
            // The string only depends on the stack size and the custom name, and both repeat across
            // thousands of stacks, so the settings for this entity type hold the finished strings
            boolean useCustomName = customName != null && SettingKey.ENTITY_DISPLAY_TAGS_CUSTOM_NAME.get();
            String displayString = this.stackSettings.getStackDisplayString(this.getStackSize(), useCustomName ? customName : null);

            this.displayNameVisible = !SettingKey.ENTITY_DISPLAY_TAGS_HOVER.get();
            return this.displayName = displayString;
        } else if (this.getStackSize() == 1 && customName != null) {
            this.displayNameVisible = this.entity.isCustomNameVisible();
            return this.displayName = customName;
        }

        this.displayNameVisible = false;
        return null;
    }

    public boolean isDisplayNameVisible() {
        return this.displayNameVisible;
    }

    @Override
    public void updateDisplay() {
        this.displayName = null;
        String displayName = this.getDisplayName();
        boolean displayNameVisible = this.displayNameVisible;

        // Only the players whose client actually has this entity can see the tag; anyone else gets the
        // correct tag from the nametag pass once they start tracking it. This replaces a loop over every
        // online player that scheduled a task per player just to distance-check them.
        if (isAsyncDisplayUpdates()) {
            for (UUID playerId : this.getTrackingPlayers()) {
                if (!this.markNametagSent(playerId, displayName, displayNameVisible))
                    continue;

                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isValid())
                    NMSAdapter.getHandler().updateEntityNameTagForPlayer(player, this.entity, displayName, displayNameVisible);
            }
            return;
        }

        for (Player player : this.entity.getTrackedBy()) {
            if (!this.markNametagSent(player.getUniqueId(), displayName, displayNameVisible))
                continue;

            ThreadUtils.runOnEntity(player, () -> {
                if (player.isValid())
                    NMSAdapter.getHandler().updateEntityNameTagForPlayer(player, this.entity, displayName, displayNameVisible);
            });
        }
    }

    /**
     * Records the nametag state about to be sent to a player.
     *
     * @param playerId The player the tag is being sent to
     * @param displayName The name being sent, nullable
     * @param visible Whether the tag is being sent as visible
     * @return true if this differs from what the player last received and a packet should be sent
     */
    public boolean markNametagSent(UUID playerId, String displayName, boolean visible) {
        if (!nametagStateTrackingEnabled)
            return true;

        NametagState state = this.getNametagState(playerId);
        if (state.sent && state.visible == visible && Objects.equals(state.displayName, displayName))
            return false;

        state.sent = true;
        state.displayName = displayName;
        state.visible = visible;
        return true;
    }

    /**
     * Runs a line of sight check between a player and this stack, reusing the previous answer while both
     * ends of the ray have stayed put and the answer is not too old.
     * <p>
     * The wall check was the most expensive part of the nametag pass, and in a farm most stacks and most
     * players are standing still between one pass and the next, so the ray is only walked when something
     * actually moved or the cached answer has aged out.
     *
     * @param playerId The player looking at this stack
     * @param playerX The player's eye X
     * @param playerY The player's eye Y
     * @param playerZ The player's eye Z
     * @param targetX The X of the point on this stack being looked at
     * @param targetY The Y of the point on this stack being looked at
     * @param targetZ The Z of the point on this stack being looked at
     * @param check Walks the ray, only called when the cached answer cannot be reused
     * @return true if the player can see this stack's nametag
     */
    public boolean checkLineOfSight(UUID playerId, double playerX, double playerY, double playerZ,
                                    double targetX, double targetY, double targetZ, BooleanSupplier check) {
        // The per-player states are only pruned by the track/untrack events; without them, don't create any
        if (!nametagStateTrackingEnabled)
            return check.getAsBoolean();

        NametagState state = this.getNametagState(playerId);
        if (state.lineOfSightChecked
                && ++state.lineOfSightIdleCycles < IDLE_LINE_OF_SIGHT_RECHECK_CYCLES
                && distanceSquared(playerX, playerY, playerZ, state.lastPlayerX, state.lastPlayerY, state.lastPlayerZ) <= MOVEMENT_THRESHOLD_SQRD
                && distanceSquared(targetX, targetY, targetZ, state.lastTargetX, state.lastTargetY, state.lastTargetZ) <= MOVEMENT_THRESHOLD_SQRD)
            return state.lineOfSight;

        boolean lineOfSight = check.getAsBoolean();
        state.lineOfSightChecked = true;
        state.lineOfSightIdleCycles = 0;
        state.lineOfSight = lineOfSight;
        state.lastPlayerX = playerX;
        state.lastPlayerY = playerY;
        state.lastPlayerZ = playerZ;
        state.lastTargetX = targetX;
        state.lastTargetY = targetY;
        state.lastTargetZ = targetZ;
        return lineOfSight;
    }

    private static double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private NametagState getNametagState(UUID playerId) {
        Map<UUID, NametagState> sent = this.sentNametags;
        if (sent == null) {
            synchronized (this) {
                sent = this.sentNametags;
                if (sent == null)
                    this.sentNametags = sent = new ConcurrentHashMap<>(4);
            }
        }

        return sent.computeIfAbsent(playerId, x -> new NametagState());
    }

    /**
     * Forgets what a player last received so the next nametag pass sends them the tag again.
     *
     * @param playerId The player to forget
     */
    public void forgetNametagState(UUID playerId) {
        Map<UUID, NametagState> sent = this.sentNametags;
        if (sent != null)
            sent.remove(playerId);
    }

    /**
     * Forgets what every player last received.
     */
    public void clearNametagStates() {
        Map<UUID, NametagState> sent = this.sentNametags;
        if (sent != null)
            sent.clear();
    }

    /**
     * What one player last received for this stack, and the last wall check run for them. Only ever
     * touched from whichever thread is updating this stack's display, so the fields are plain; the worst a
     * concurrent update can cost is a nametag packet sent twice or one cycle late.
     */
    private static final class NametagState {

        private boolean sent;
        private String displayName;
        private boolean visible;

        private boolean lineOfSightChecked;
        private boolean lineOfSight;
        private int lineOfSightIdleCycles;
        private double lastPlayerX, lastPlayerY, lastPlayerZ;
        private double lastTargetX, lastTargetY, lastTargetZ;

    }

    /**
     * Records that a player's client now has this entity.
     *
     * @param playerId The player that started tracking this entity
     */
    public void addTrackingPlayer(UUID playerId) {
        Set<UUID> tracking = this.trackingPlayers;
        if (tracking == null) {
            synchronized (this) {
                tracking = this.trackingPlayers;
                if (tracking == null)
                    this.trackingPlayers = tracking = ConcurrentHashMap.newKeySet(4);
            }
        }

        tracking.add(playerId);
    }

    /**
     * Records that a player's client no longer has this entity.
     *
     * @param playerId The player that stopped tracking this entity
     */
    public void removeTrackingPlayer(UUID playerId) {
        Set<UUID> tracking = this.trackingPlayers;
        if (tracking != null)
            tracking.remove(playerId);
    }

    /**
     * @return the players whose client currently has this entity, never null
     */
    public Set<UUID> getTrackingPlayers() {
        Set<UUID> tracking = this.trackingPlayers;
        return tracking != null ? tracking : Set.of();
    }

    /**
     * Fills the tracked player set from the entity itself. Must only be called on the entity's thread.
     */
    public void seedTrackingPlayers() {
        for (Player player : this.entity.getTrackedBy())
            this.addTrackingPlayer(player.getUniqueId());
    }

    /**
     * Decides whether the unstack pass needs to look at this stack this cycle.
     * <p>
     * Everything the unstack check consults about a stack changes through the stack itself, which bumps the
     * modified tick, so a stack that has not been touched since the last check will reach the same verdict
     * it reached last time. Checking it anyway costs a materialized entity per stack per cycle on NBT
     * storage, which for an idle spawner farm is the whole cost of the pass. Idle stacks are still
     * re-checked every {@link #IDLE_UNSTACK_RECHECK_CYCLES} cycles so nothing can get stuck.
     *
     * @return true if this stack should be checked, otherwise false
     */
    public boolean needsUnstackCheck() {
        if (this.lastModifiedTicks == this.lastUnstackCheckModifiedTicks
                && ++this.unstackCheckIdleCycles < IDLE_UNSTACK_RECHECK_CYCLES)
            return false;

        this.lastUnstackCheckModifiedTicks = this.lastModifiedTicks;
        this.unstackCheckIdleCycles = 0;
        return true;
    }

    /**
     * Checks whether anything has changed since the last time this stack was written to its entity.
     * <p>
     * The stack size is compared as well as the modified tick, so a storage mutation that went around
     * {@link #markModified()} still counts as a change.
     *
     * @return true if this stack has changed since it was last saved, otherwise false
     */
    public boolean needsSave() {
        return this.lastModifiedTicks != this.lastSavedModifiedTicks || this.getStackSize() != this.lastSavedStackSize;
    }

    /**
     * Records that this stack has just been written to its entity.
     */
    public void markSaved() {
        this.lastSavedModifiedTicks = this.lastModifiedTicks;
        this.lastSavedStackSize = this.getStackSize();
    }

    /**
     * @return true if this stack was just created and its entity has not finished spawning yet
     */
    public boolean isNewlyCreated() {
        return this.newlyCreated;
    }

    /**
     * Marks this stack as freshly created, so the stacking pass does not mistake an entity that has not
     * finished spawning for one that has been removed.
     *
     * @param newlyCreated true while the stack is being created, otherwise false
     */
    public void setNewlyCreated(boolean newlyCreated) {
        this.newlyCreated = newlyCreated;
    }

    @Override
    public void updateDisplaySafely() {
        if (this.entity == null)
            return;

        // The display update only reads the stack and sends per-player packets, so on Paper it does not
        // need the entity's thread at all; this used to schedule a task on every single stack size change
        if (isAsyncDisplayUpdates()) {
            this.updateDisplay();
            return;
        }

        ThreadUtils.runOnEntity(this.entity, this::updateDisplay);
    }

    @Override
    public EntityStackSettings getStackSettings() {
        return this.stackSettings;
    }

    /**
     * Gets the StackedEntity that two stacks should stack into
     *
     * @param stack2 the second StackedEntity
     * @return a positive int if this stack should be preferred, or a negative int if the other should be preferred
     */
    @Override
    public int compareTo(StackedEntity stack2) {
        Entity entity1 = this.getEntity();
        Entity entity2 = stack2.getEntity();

        if (this == stack2)
            return 0;

        if (SettingKey.ENTITY_STACK_FLYING_DOWNWARDS.get() && this.stackSettings.getEntityTypeData().flyingMob())
            return entity1.getLocation().getY() < entity2.getLocation().getY() ? 3 : -3;

        if (this.getStackSize() == stack2.getStackSize())
            return entity1.getTicksLived() > entity2.getTicksLived() ? 2 : -2;

        return this.getStackSize() > stack2.getStackSize() ? 1 : -1;
    }

    /**
     * Checks if the entity stack should die at once
     *
     * @param overrideKiller The player that is causing the entity to die, nullable
     * @return true if the whole stack should die, otherwise false
     */
    public boolean isEntireStackKilledOnDeath(@Nullable Player overrideKiller) {
        EntityDamageEvent lastDamageCause = this.entity.getLastDamageCause();
        if (overrideKiller == null)
            overrideKiller = this.entity.getKiller();

        return this.stackSettings.shouldKillEntireStackOnDeath()
                || (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_KILL_ENTIRE_STACK_ON_DEATH.get() && this.isAiDisabled())
                || (lastDamageCause != null && SettingKey.ENTITY_KILL_ENTIRE_STACK_CONDITIONS.get().stream().anyMatch(x -> x.equalsIgnoreCase(lastDamageCause.getCause().name())))
                || (overrideKiller != null && SettingKey.ENTITY_KILL_ENTIRE_STACK_ON_DEATH_PERMISSION.get() && overrideKiller.hasPermission("rosestacker.killentirestack"));
    }

    /**
     * @return true if the whole stack should die at once, otherwise false
     */
    public boolean isEntireStackKilledOnDeath() {
        return this.isEntireStackKilledOnDeath(null);
    }

    /**
     * Kills the entire entity stack and drops its loot
     *
     * @param event The event that caused the entity to die, nullable
     */
    public void killEntireStack(@Nullable EntityDeathEvent event) {
        int amount = this.getStackSize();
        int experience = event != null ? event.getDroppedExp() : EntityUtils.getApproximateExperience(this.entity);
        if (SettingKey.ENTITY_DROP_ACCURATE_ITEMS.get()) {
            // Make sure the entity size is correct to allow drops
            if (this.entity.getType() == EntityType.SLIME) {
                ((Slime) this.entity).setSize(1);
            } else if (this.entity.getType() == EntityType.MAGMA_CUBE) {
                ((MagmaCube) this.entity).setSize(2);
            }

            if (event == null) {
                this.dropStackLoot(new ArrayList<>(), experience);
            } else {
                this.dropStackLoot(new ArrayList<>(event.getDrops()), experience);
                event.getDrops().clear();
            }

            // Make sure it doesn't split
            if (this.entity.getType() == EntityType.MAGMA_CUBE)
                ((MagmaCube) this.entity).setSize(1);
            if (this.entity.getType().name().equals("SULFUR_CUBE"))
                ((SulfurCube) this.entity).setSize(1);
        } else if (SettingKey.ENTITY_DROP_ACCURATE_EXP.get()) {
            if (event == null) {
                EntitySpawnUtil.spawn(this.entity.getLocation(), ExperienceOrb.class, x -> x.setExperience(experience));
            } else {
                event.setDroppedExp(experience * amount);
            }
        }

        Player killer = this.entity.getKiller();
        if (killer != null && amount - 1 > 0 && SettingKey.MISC_STACK_STATISTICS.get())
            killer.incrementStatistic(Statistic.KILL_ENTITY, this.entity.getType(), amount - 1);

        RoseStacker.getInstance().getManager(StackManager.class).removeEntityStack(this);

        if (!this.entity.isDead())
            this.entity.remove();
    }

    /**
     * Kills the entire entity stack and drops its loot
     */
    public void killEntireStack() {
        this.killEntireStack(null);
    }

    public void killPartialStack(@Nullable EntityDeathEvent event, int amount) {
        if (amount == 1) {
            if (this.getStackSize() == 1) {
                RoseStacker.getInstance().getManager(StackManager.class).removeEntityStack(this);
            } else {
                this.decreaseStackSize();
            }
            return;
        }

        List<EntityDataEntry> killedEntities = this.stackedEntityDataStorage.pop(amount - 1);
        if (SettingKey.ENTITY_DROP_ACCURATE_ITEMS.get()) {
            if (event == null) {
                this.dropPartialStackLoot(killedEntities, new ArrayList<>(), EntityUtils.getApproximateExperience(this.entity));
            } else {
                this.dropPartialStackLoot(killedEntities, new ArrayList<>(event.getDrops()), event.getDroppedExp());
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
        } else if (SettingKey.ENTITY_DROP_ACCURATE_EXP.get()) {
            if (event == null) {
                EntitySpawnUtil.spawn(this.entity.getLocation(), ExperienceOrb.class, x -> x.setExperience(EntityUtils.getApproximateExperience(this.entity)));
            } else {
                event.setDroppedExp(event.getDroppedExp() * (killedEntities.size() + 1));
            }
        }

        LivingEntity originalEntity = this.entity;

        this.decreaseStackSize();

        // Prevent the entity from splitting
        switch (originalEntity.getType()) {
            case SLIME -> ((Slime) originalEntity).setSize(1);
            case MAGMA_CUBE -> ((MagmaCube) originalEntity).setSize(1);
        }

        Player killer = originalEntity.getKiller();
        if (killer != null && amount - 1 > 0 && SettingKey.MISC_STACK_STATISTICS.get())
            killer.incrementStatistic(Statistic.KILL_ENTITY, this.entity.getType(), amount - 1);
    }

    public static int getNextMultikillAmount(LivingEntity entity, int stackSize) {
        int enchantmentMultiplier = 1;
        Player killer = entity.getKiller();
        if (!SettingKey.ENTITY_MULTIKILL_PLAYER_ONLY.get() || killer != null) {
            if (SettingKey.ENTITY_MULTIKILL_ENCHANTMENT_ENABLED.get()) {
                Enchantment requiredEnchantment = Enchantment.getByKey(NamespacedKey.fromString(SettingKey.ENTITY_MULTIKILL_ENCHANTMENT_TYPE.get()));
                if (requiredEnchantment == null) {
                    // Only decrease stack size by 1 and print a warning to the console
                    RoseStacker.getInstance().getLogger().warning("Invalid multikill enchantment type: " + SettingKey.ENTITY_MULTIKILL_ENCHANTMENT_TYPE.get());
                    enchantmentMultiplier = 0;
                } else if (killer != null) {
                    enchantmentMultiplier = killer.getInventory().getItemInMainHand().getEnchantmentLevel(requiredEnchantment);
                } else {
                    enchantmentMultiplier = 0;
                }
            }
        }

        MultikillBound lowerBound = StackManager.getLowerMultikillBound();
        MultikillBound upperBound = StackManager.getUpperMultikillBound();

        int lowerValue = lowerBound.getValue(stackSize);
        int upperValue = upperBound.getValue(stackSize);
        if (upperValue < lowerValue)
            upperValue = lowerValue;

        long seed = entity.getUniqueId().getMostSignificantBits(); // Consistent seed for same entity UUID so other plugins can calculate the same kill amount
        int targetAmount = StackerUtils.seededRandomInRange(seed, lowerValue, upperValue);
        return Math.max(1, targetAmount * enchantmentMultiplier);
    }

    /**
     * Checks if multiple entities are dying from the EntityDeathEvent and an {@link EntityStackMultipleDeathEvent} is
     * going to be called.
     *
     * @param event The EntityDeathEvent to check
     * @return true if multiple entities are dying during this event, false otherwise
     */
    public boolean areMultipleEntitiesDying(@NotNull EntityDeathEvent event) {
        // Individual events will be called if we are triggering death events
        if (SettingKey.ENTITY_TRIGGER_DEATH_EVENT_FOR_ENTIRE_STACK_KILL.get()
                || !SettingKey.ENTITY_DROP_ACCURATE_ITEMS.get()
                || this.getStackSize() == 1)
            return false;

        // Ignore if entire stack kill
        if (this.isEntireStackKilledOnDeath())
            return true;

        // Is partial stack kill
        Player killer = event.getEntity().getKiller();
        if (!SettingKey.ENTITY_MULTIKILL_ENABLED.get())
            return false;

        if (SettingKey.ENTITY_MULTIKILL_PLAYER_ONLY.get() && killer == null)
            return false;

        return getNextMultikillAmount(event.getEntity(), this.getStackSize()) > 1;
    }

    /**
     * @return true if the entity has moved since the last time this method was called
     */
    public boolean hasMoved() {
        Location location = this.entity.getLocation();
        boolean moved = location.getX() != this.x || location.getY() != this.y || location.getZ() != this.z;
        if (moved) {
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
        }
        return moved;
    }

    public void resetHasMoved() {
        this.x = this.y = this.z = 0;
    }

}
