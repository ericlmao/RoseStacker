package dev.rosewood.rosestacker.nms.v1_18_R2.storage;

import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataIOException;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorage;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorageType;
import dev.rosewood.rosestacker.nms.util.ExtraUtils;
import dev.rosewood.rosestacker.nms.v1_18_R2.NMSHandlerImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.bukkit.entity.LivingEntity;

public class NBTStackedEntityDataStorage extends StackedEntityDataStorage {

    // Captured lazily by getBase() instead of in the constructor. The base is only ever used as a template for
    // new entries and as the deduplication baseline, so nothing reads it until the stack actually grows past
    // size 1, and roughly 99% of stacks never do. The spawner path used to pay for it twice per mob: once
    // saving the mob into its own throwaway storage and again saving it into the stack it merged into.
    private volatile CompoundTag base;
    // Guarded by itself. Every bulk operation already took this lock and copied the contents into an
    // ArrayList, so the backing queue's own locking was redundant; an ArrayDeque under the same lock drops
    // both the second lock acquisition and the node allocation per entry.
    private final Queue<CompoundTag> data;
    // Mirrors data.size() so size() stays a plain field read. Only written while holding the data lock.
    private volatile int size;

    public NBTStackedEntityDataStorage(LivingEntity livingEntity) {
        super(StackedEntityDataStorageType.NBT, livingEntity);

        this.data = createBackingQueue();
    }

    public NBTStackedEntityDataStorage(LivingEntity livingEntity, byte[] data) {
        super(StackedEntityDataStorageType.NBT, livingEntity);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
             ObjectInputStream dataInput = new ObjectInputStream(inputStream)) {

            this.base = NbtIo.read(dataInput);
            int length = dataInput.readInt();
            this.data = createBackingQueue();
            for (int i = 0; i < length; i++)
                this.data.add(NbtIo.read(dataInput));
            this.size = this.data.size();
        } catch (Exception e) {
            throw new StackedEntityDataIOException(e);
        }
    }

    /**
     * Lazily captures the base tag from the head entity the first time anything needs it. The deserializing
     * constructor sets it up front instead, since the stored bytes are the only copy of it that exists.
     *
     * @return the tag that every entry in this storage is stored as a delta against, never null
     */
    private CompoundTag getBase() {
        CompoundTag base = this.base;
        if (base != null)
            return base;

        synchronized (this) {
            base = this.base;
            if (base != null)
                return base;

            LivingEntity livingEntity = this.entity.get();
            if (livingEntity == null) {
                // The head entity is already gone, there is nothing left to template from
                base = new CompoundTag();
            } else {
                base = new CompoundTag();
                ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(livingEntity, base);
                this.stripUnneeded(base);
                this.stripAttributeUuids(base);
                NMSHandler.UNSAFE_NBT_KEYS.forEach(base::remove);
            }

            this.base = base;
            return base;
        }
    }

    @Override
    public void add(LivingEntity entity) {
        CompoundTag compoundTag = new CompoundTag();
        ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(entity, compoundTag);
        this.stripUnneeded(compoundTag);
        this.stripAttributeUuids(compoundTag);
        this.removeDuplicates(compoundTag);
        synchronized (this.data) {
            this.data.add(compoundTag);
            this.size = this.data.size();
        }
    }

    @Override
    public void addAll(StackedEntityDataStorage stackedEntityDataStorage) {
        List<EntityDataEntry> entries = stackedEntityDataStorage.getAll();
        List<CompoundTag> compoundTags = new ArrayList<>(entries.size());
        for (EntityDataEntry entry : entries) {
            CompoundTag compoundTag = ((NBTEntityDataEntry) entry).get();
            this.stripUnneeded(compoundTag);
            this.stripAttributeUuids(compoundTag);
            this.removeDuplicates(compoundTag);
            compoundTags.add(compoundTag);
        }

        synchronized (this.data) {
            this.data.addAll(compoundTags);
            this.size = this.data.size();
        }
    }

    @Override
    public void addClones(int amount) {
        CompoundTag base = this.getBase();
        synchronized (this.data) {
            for (int i = 0; i < amount; i++)
                this.data.add(base.copy());
            this.size = this.data.size();
        }
    }

    @Override
    public NBTEntityDataEntry peek() {
        CompoundTag front;
        synchronized (this.data) {
            front = this.data.element();
        }

        return new NBTEntityDataEntry(this.rebuild(front), true);
    }

    @Override
    public NBTEntityDataEntry pop() {
        CompoundTag front;
        synchronized (this.data) {
            front = this.data.remove();
            this.size = this.data.size();
        }

        return new NBTEntityDataEntry(this.rebuild(front), true);
    }

    @Override
    public List<EntityDataEntry> pop(int amount) {
        List<CompoundTag> removed;
        synchronized (this.data) {
            amount = Math.min(amount, this.data.size());
            removed = new ArrayList<>(amount);
            for (int i = 0; i < amount; i++)
                removed.add(this.data.remove());
            this.size = this.data.size();
        }

        List<EntityDataEntry> popped = new ArrayList<>(removed.size());
        for (CompoundTag compoundTag : removed)
            popped.add(new NBTEntityDataEntry(this.rebuild(compoundTag), true));
        return popped;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        return this.size == 0;
    }

    @Override
    public boolean isHeadRepresentative() {
        CompoundTag front;
        synchronized (this.data) {
            front = this.data.peek();
        }

        if (front == null)
            return true;

        // Entries are stored deduplicated against the base, and the base already has every UNSAFE_NBT_KEYS
        // entry stripped from it. So anything the front entry still carries that is itself an unsafe key
        // (health, equipment, attributes, brain, ...) is a field no stack condition ever looks at, and the
        // head entity is an equally good stand-in for it. A spawner clone or an identical mob leaves nothing
        // else behind, which on a spawner-fed farm is very nearly every stack. This only describes the stored
        // entry; the head entity may still drift away from the base while it is alive, which is why callers
        // bound how many checks in a row they answer this way (see StackedEntity#shouldStayStacked).
        for (String key : front.getAllKeys())
            if (!NMSHandler.UNSAFE_NBT_KEY_SET.contains(key))
                return false;

        return true;
    }

    @Override
    public Boolean getBaseAdultState() {
        // The entries are stored as a delta against the base, so the base holds the age every entry that
        // does not override it has. "Age" is negative for a baby and zero or higher for an adult; the
        // lowercase spelling is checked too, for the same reason NMSHandler lists both spellings of every
        // other key it strips.
        CompoundTag base = this.getBase();
        if (base.contains("Age"))
            return base.getInt("Age") >= 0;

        if (base.contains("age"))
            return base.getInt("age") >= 0;

        return null;
    }

    @Override
    public List<EntityDataEntry> getAll() {
        List<CompoundTag> snapshot;
        synchronized (this.data) {
            snapshot = new ArrayList<>(this.data);
        }

        List<EntityDataEntry> wrapped = new ArrayList<>(snapshot.size());
        for (CompoundTag compoundTag : snapshot)
            wrapped.add(new NBTEntityDataEntry(this.rebuild(compoundTag), true));
        return wrapped;
    }

    @Override
    public byte[] serialize(int maxAmount) {
        List<CompoundTag> tagsToSave;
        synchronized (this.data) {
            int targetAmount = Math.min(maxAmount, this.data.size());
            tagsToSave = new ArrayList<>(targetAmount);
            Iterator<CompoundTag> iterator = this.data.iterator();
            for (int i = 0; i < targetAmount; i++)
                tagsToSave.add(iterator.next());
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream)) {

            NbtIo.write(this.getBase(), dataOutput);
            dataOutput.writeInt(tagsToSave.size());
            for (CompoundTag compoundTag : tagsToSave)
                NbtIo.write(compoundTag, dataOutput);

            dataOutput.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new StackedEntityDataIOException(e);
        }
    }

    @Override
    public void forEach(Consumer<LivingEntity> consumer) {
        this.forEachCapped(Integer.MAX_VALUE, consumer);
    }

    @Override
    public void forEachCapped(int count, Consumer<LivingEntity> consumer) {
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return;

        List<CompoundTag> snapshot;
        synchronized (this.data) {
            if (count > this.data.size())
                count = this.data.size();

            snapshot = new ArrayList<>(count);
            Iterator<CompoundTag> iterator = this.data.iterator();
            for (int i = 0; i < count; i++)
                snapshot.add(iterator.next());
        }

        for (CompoundTag compoundTag : snapshot) {
            LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag), true).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
            consumer.accept(entity);
        }
    }

    @Override
    public void forEachTransforming(Function<LivingEntity, Boolean> function) {
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return;

        synchronized (this.data) {
            List<CompoundTag> data = new ArrayList<>(this.data);
            ListIterator<CompoundTag> dataIterator = data.listIterator();
            while (dataIterator.hasNext()) {
                CompoundTag compoundTag = dataIterator.next();
                LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag), true).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
                if (function.apply(entity)) {
                    CompoundTag replacementTag = new CompoundTag();
                    ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(entity, replacementTag);
                    this.stripUnneeded(replacementTag);
                    this.stripAttributeUuids(replacementTag);
                    this.removeDuplicates(replacementTag);
                    dataIterator.set(replacementTag);
                }
            }

            this.data.clear();
            this.data.addAll(data);
        }
    }

    @Override
    public List<LivingEntity> removeIf(Function<LivingEntity, Boolean> function) {
        List<LivingEntity> removedEntries = new ArrayList<>(this.size);
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return removedEntries;

        synchronized (this.data) {
            List<CompoundTag> data = new ArrayList<>(this.data);
            ListIterator<CompoundTag> dataIterator = data.listIterator();
            while (dataIterator.hasNext()) {
                CompoundTag compoundTag = dataIterator.next();
                LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag), true).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
                if (function.apply(entity)) {
                    removedEntries.add(entity);
                    dataIterator.remove();
                } else {
                    CompoundTag replacementTag = new CompoundTag();
                    ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(entity, replacementTag);
                    this.stripUnneeded(replacementTag);
                    this.stripAttributeUuids(replacementTag);
                    this.removeDuplicates(replacementTag);
                    dataIterator.set(replacementTag);
                }
            }

            this.data.clear();
            this.data.addAll(data);
            this.size = this.data.size();
            return removedEntries;
        }
    }

    @Override
    public List<LivingEntity> damageAll(double damage) {
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return List.of();

        // Health lives in the stored tag as a plain float, so shared damage can be applied to it directly.
        // Building an entity per member to read and write its health, and saving every survivor back out
        // again, cost a createCreature plus a saveWithoutId per member per damage tick.
        List<CompoundTag> killedTags = new ArrayList<>();
        synchronized (this.data) {
            // Entries added by addClones() are exact copies of the base, and the base has every
            // UNSAFE_NBT_KEYS entry stripped from it, health included, so they carry no health of their own.
            // One entity built from the base tells us the health they would have had; each of them picks up a
            // health value of its own below, so this is needed at most once per stack.
            Float cloneHealth = null;
            Iterator<CompoundTag> iterator = this.data.iterator();
            while (iterator.hasNext()) {
                CompoundTag compoundTag = iterator.next();
                float health;
                if (compoundTag.contains("Health")) {
                    health = compoundTag.getFloat("Health");
                } else {
                    if (cloneHealth == null) {
                        LivingEntity clone = new NBTEntityDataEntry(this.rebuild(new CompoundTag()), true).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
                        cloneHealth = (float) (clone != null ? clone.getHealth() : thisEntity.getHealth());
                    }
                    health = cloneHealth;
                }

                if (health - damage <= 0) {
                    // Don't set the health below 0, as that will trigger the death event which we want to avoid
                    killedTags.add(compoundTag);
                    iterator.remove();
                } else {
                    compoundTag.putFloat("Health", (float) (health - damage));
                }
            }

            this.size = this.data.size();
        }

        // Only the members that actually died need to become entities, the loot code works off real entities
        List<LivingEntity> killedEntities = new ArrayList<>(killedTags.size());
        for (CompoundTag compoundTag : killedTags)
            killedEntities.add(new NBTEntityDataEntry(this.rebuild(compoundTag), true).createEntity(thisEntity.getLocation(), false, thisEntity.getType()));
        return killedEntities;
    }

    private void removeDuplicates(CompoundTag compoundTag) {
        CompoundTag base = this.getBase();
        for (String key : new ArrayList<>(compoundTag.getAllKeys())) {
            Tag baseValue = base.get(key);
            Tag thisValue = compoundTag.get(key);
            if (baseValue != null && baseValue.equals(thisValue))
                compoundTag.remove(key);
        }
    }

    private CompoundTag rebuild(CompoundTag compoundTag) {
        CompoundTag merged = new CompoundTag();
        merged.merge(this.getBase());
        merged.merge(compoundTag);
        this.fillAttributeUuids(merged);
        return merged;
    }

    private void stripUnneeded(CompoundTag compoundTag) {
        NMSHandler.REMOVABLE_NBT_KEYS.forEach(compoundTag::remove);
        CompoundTag bukkitValues = compoundTag.getCompound("BukkitValues");
        bukkitValues.remove("rosestacker:stacked_entity_data");
    }

    private void stripAttributeUuids(CompoundTag compoundTag) {
        ListTag attributes = compoundTag.getList("Attributes", Tag.TAG_COMPOUND);
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i);
            attribute.remove("UUID");
            ListTag modifiers = attribute.getList("Modifiers", Tag.TAG_COMPOUND);
            for (int j = 0; j < modifiers.size(); j++) {
                CompoundTag modifier = modifiers.getCompound(j);
                if (modifier.getString("Name").equals("Random spawn bonus")) {
                    modifiers.remove(j);
                    j--;
                } else {
                    modifier.remove("UUID");
                }
            }
        }
    }

    private void fillAttributeUuids(CompoundTag compoundTag) {
        ListTag attributes = compoundTag.getList("Attributes", Tag.TAG_COMPOUND);
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i);
            attribute.putUUID("UUID", ExtraUtils.insecureRandomUuid());
            ListTag modifiers = attribute.getList("Modifiers", Tag.TAG_COMPOUND);
            for (int j = 0; j < modifiers.size(); j++) {
                CompoundTag modifier = modifiers.getCompound(j);
                modifier.putUUID("UUID", ExtraUtils.insecureRandomUuid());
            }
            if (modifiers.size() == 0)
                attribute.remove("Modifiers");
        }
    }

}
