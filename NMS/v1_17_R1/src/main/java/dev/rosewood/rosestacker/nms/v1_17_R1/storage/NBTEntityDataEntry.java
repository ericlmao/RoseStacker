package dev.rosewood.rosestacker.nms.v1_17_R1.storage;

import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.util.ExtraUtils;
import dev.rosewood.rosestacker.nms.v1_17_R1.NMSHandlerImpl;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_17_R1.CraftWorld;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

public class NBTEntityDataEntry implements EntityDataEntry {

    private final CompoundTag compoundTag;
    private final boolean owned;

    public NBTEntityDataEntry(LivingEntity livingEntity) {
        this.compoundTag = new CompoundTag();
        ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(livingEntity, this.compoundTag);
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

            ListTag positionTagList = nbt.getList("Pos", Tag.TAG_DOUBLE);
            if (positionTagList == null)
                positionTagList = new ListTag();
            this.setTag(positionTagList, 0, DoubleTag.valueOf(location.getX()));
            this.setTag(positionTagList, 1, DoubleTag.valueOf(location.getY()));
            this.setTag(positionTagList, 2, DoubleTag.valueOf(location.getZ()));
            nbt.put("Pos", positionTagList);
            ListTag rotationTagList = nbt.getList("Rotation", Tag.TAG_FLOAT);
            if (rotationTagList == null)
                rotationTagList = new ListTag();
            this.setTag(rotationTagList, 0, FloatTag.valueOf(location.getYaw()));
            this.setTag(rotationTagList, 1, FloatTag.valueOf(location.getPitch()));
            nbt.put("Rotation", rotationTagList);
            nbt.putUUID("UUID", ExtraUtils.insecureRandomUuid()); // Reset the UUID to resolve possible duplicates
            nbt.remove("AngryAt"); // Causes issues if this value is parsed async

            Optional<net.minecraft.world.entity.EntityType<?>> optionalEntity = net.minecraft.world.entity.EntityType.byString(entityType.getKey().getKey());
            if (optionalEntity.isPresent()) {
                ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();

                Entity entity = nmsHandler.createCreature(
                        optionalEntity.get(),
                        world,
                        nbt,
                        null,
                        null,
                        new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                        MobSpawnType.COMMAND
                );

                if (entity == null)
                    throw new NullPointerException("Unable to create entity from NBT");

                // Load NBT
                entity.load(nbt);

                if (entity instanceof Villager villager)
                    villager.setCanPickUpLoot(true);

                if (addToWorld) {
                    nmsHandler.registerEntity(world, entity);
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
