package dev.rosewood.rosestacker.nms.v1_21_R7.storage;

import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.v1_21_R7.NMSHandlerImpl;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_21_R7.CraftWorld;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

public class NBTEntityDataEntry implements EntityDataEntry {

    private final CompoundTag compoundTag;
    private final boolean owned;

    public NBTEntityDataEntry(LivingEntity livingEntity) {
        this.compoundTag = ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(livingEntity);
        this.owned = false;
    }

    public NBTEntityDataEntry(CompoundTag compoundTag) {
        this(compoundTag, false);
    }

    /**
     * @param compoundTag The tag to build entities out of
     * @param owned true if this entry may edit the given tag in place instead of copying it first.
     *              Only safe for tags built for this entry alone and referenced nowhere else.
     */
    public NBTEntityDataEntry(CompoundTag compoundTag, boolean owned) {
        this.compoundTag = compoundTag;
        this.owned = owned;
    }

    public CompoundTag get() {
        return this.compoundTag;
    }

    @Override
    public LivingEntity createEntity(Location location, boolean addToWorld, EntityType entityType) {
        try {
            NMSHandlerImpl nmsHandler = (NMSHandlerImpl) NMSAdapter.getHandler();
            // rebuild() already hands back a freshly merged tag that nothing else references,
            // so copying the entire entity tag a second time here is pure waste
            CompoundTag nbt = this.owned ? this.compoundTag : this.compoundTag.copy();

            ListTag positionTagList = nbt.getListOrEmpty("Pos");
            this.setTag(positionTagList, 0, DoubleTag.valueOf(location.getX()));
            this.setTag(positionTagList, 1, DoubleTag.valueOf(location.getY()));
            this.setTag(positionTagList, 2, DoubleTag.valueOf(location.getZ()));
            nbt.put("Pos", positionTagList);
            ListTag rotationTagList = nbt.getListOrEmpty("Rotation");
            this.setTag(rotationTagList, 0, FloatTag.valueOf(location.getYaw()));
            this.setTag(rotationTagList, 1, FloatTag.valueOf(location.getPitch()));
            nbt.put("Rotation", rotationTagList);
            nbt.remove("UUID"); // Drop any stored UUID so the entity keeps the fresh one createCreature gave it

            if (nbt.getCompoundOrEmpty("BukkitValues").isEmpty()) // fix error on Spigot when looking up BukkitValues
                nbt.remove("BukkitValues");

            Optional<net.minecraft.world.entity.EntityType<?>> optionalEntity = net.minecraft.world.entity.EntityType.byString(entityType.getKey().getKey());
            if (optionalEntity.isPresent()) {
                ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();

                Entity entity = nmsHandler.createCreature(
                        optionalEntity.get(),
                        world,
                        new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                        EntitySpawnReason.COMMAND
                );

                if (entity == null)
                    throw new NullPointerException("Unable to create entity from NBT");

                // Load NBT
                ProblemReporter.Collector reporter = new ProblemReporter.Collector();
                ValueInput valueInput = TagValueInput.create(reporter, entity.registryAccess(), nbt);
                if (!reporter.isEmpty())
                    RoseStacker.getInstance().getLogger().severe(reporter.getTreeReport());

                entity.load(valueInput);

                if (entity instanceof Villager villager)
                    villager.setCanPickUpLoot(true);

                if (addToWorld) {
                    nmsHandler.addEntityToWorld(world, entity);
                    entity.invulnerableTime = 0;
                }

                return (LivingEntity) entity.getBukkitEntity();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private void setTag(ListTag tag, int index, Tag value) {
        if (index >= tag.size()) {
            tag.addTag(index, value);
        } else {
            tag.setTag(index, value);
        }
    }

}
