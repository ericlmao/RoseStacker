package dev.rosewood.rosestacker.nms.v1_16_R3.storage;

import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataIOException;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorage;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorageType;
import dev.rosewood.rosestacker.nms.v1_16_R3.NMSHandlerImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.v1_16_R3.NBTBase;
import net.minecraft.server.v1_16_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.NBTTagList;
import org.bukkit.entity.LivingEntity;

public class NBTStackedEntityDataStorage extends StackedEntityDataStorage {

    // Captured lazily by getBase() instead of in the constructor. The base is only ever used as a template for
    // new entries and as the deduplication baseline, so nothing reads it until the stack actually grows past
    // size 1, and roughly 99% of stacks never do. The spawner path used to pay for it twice per mob: once
    // saving the mob into its own throwaway storage and again saving it into the stack it merged into.
    private volatile NBTTagCompound base;
    private final Queue<NBTTagCompound> data;

    public NBTStackedEntityDataStorage(LivingEntity livingEntity) {
        super(StackedEntityDataStorageType.NBT, livingEntity);

        this.data = createBackingQueue();
    }

    public NBTStackedEntityDataStorage(LivingEntity livingEntity, byte[] data) {
        super(StackedEntityDataStorageType.NBT, livingEntity);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
             ObjectInputStream dataInput = new ObjectInputStream(inputStream)) {

            this.base = NBTCompressedStreamTools.a((DataInput) dataInput);
            int length = dataInput.readInt();
            this.data = createBackingQueue();
            for (int i = 0; i < length; i++)
                this.data.add(NBTCompressedStreamTools.a((DataInput) dataInput));
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
    private NBTTagCompound getBase() {
        NBTTagCompound base = this.base;
        if (base != null)
            return base;

        synchronized (this) {
            base = this.base;
            if (base != null)
                return base;

            LivingEntity livingEntity = this.entity.get();
            if (livingEntity == null) {
                // The head entity is already gone, there is nothing left to template from
                base = new NBTTagCompound();
            } else {
                base = new NBTTagCompound();
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
        NBTTagCompound compoundTag = new NBTTagCompound();
        ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(entity, compoundTag);
        this.stripUnneeded(compoundTag);
        this.stripAttributeUuids(compoundTag);
        this.removeDuplicates(compoundTag);
        this.data.add(compoundTag);
    }

    @Override
    public void addAll(StackedEntityDataStorage stackedEntityDataStorage) {
        stackedEntityDataStorage.getAll().forEach(entry -> {
            NBTTagCompound compoundTag = ((NBTEntityDataEntry) entry).get();
            this.stripUnneeded(compoundTag);
            this.stripAttributeUuids(compoundTag);
            this.removeDuplicates(compoundTag);
            this.data.add(compoundTag);
        });
    }

    @Override
    public void addClones(int amount) {
        NBTTagCompound base = this.getBase();
        for (int i = 0; i < amount; i++)
            this.data.add(base.clone());
    }

    @Override
    public NBTEntityDataEntry peek() {
        return new NBTEntityDataEntry(this.rebuild(this.data.element()));
    }

    @Override
    public NBTEntityDataEntry pop() {
        return new NBTEntityDataEntry(this.rebuild(this.data.remove()));
    }

    @Override
    public List<EntityDataEntry> pop(int amount) {
        amount = Math.min(amount, this.data.size());

        List<EntityDataEntry> popped = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++)
            popped.add(new NBTEntityDataEntry(this.rebuild(this.data.remove())));
        return popped;
    }

    @Override
    public int size() {
        return this.data.size();
    }

    @Override
    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public boolean isHeadRepresentative() {
        NBTTagCompound front = this.data.peek();
        if (front == null)
            return true;

        // Entries are stored deduplicated against the base, and the base already has every UNSAFE_NBT_KEYS
        // entry stripped from it. So anything the front entry still carries that is itself an unsafe key
        // (health, equipment, attributes, brain, ...) is a field no stack condition ever looks at, and the
        // head entity is an equally good stand-in for it. A spawner clone or an identical mob leaves nothing
        // else behind, which on a spawner-fed farm is very nearly every stack.
        for (String key : front.getKeys())
            if (!NMSHandler.UNSAFE_NBT_KEY_SET.contains(key))
                return false;

        return true;
    }

    @Override
    public List<EntityDataEntry> getAll() {
        List<EntityDataEntry> wrapped = new ArrayList<>(this.data.size());
        for (NBTTagCompound compoundTag : new ArrayList<>(this.data))
            wrapped.add(new NBTEntityDataEntry(this.rebuild(compoundTag)));
        return wrapped;
    }

    @Override
    public byte[] serialize(int maxAmount) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream)) {

            int targetAmount = Math.min(maxAmount, this.data.size());
            List<NBTTagCompound> tagsToSave = new ArrayList<>(targetAmount);
            Iterator<NBTTagCompound> iterator = this.data.iterator();
            for (int i = 0; i < targetAmount; i++)
                tagsToSave.add(iterator.next());

            NBTCompressedStreamTools.a(this.getBase(), (DataOutput) dataOutput);
            dataOutput.writeInt(tagsToSave.size());
            for (NBTTagCompound compoundTag : tagsToSave)
                NBTCompressedStreamTools.a(compoundTag, (DataOutput) dataOutput);

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
        if (count > this.data.size())
            count = this.data.size();

        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return;

        Iterator<NBTTagCompound> iterator = this.data.iterator();
        for (int i = 0; i < count; i++) {
            NBTTagCompound compoundTag = iterator.next();
            LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag)).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
            consumer.accept(entity);
        }
    }

    @Override
    public void forEachTransforming(Function<LivingEntity, Boolean> function) {
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return;

        synchronized (this.data) {
            List<NBTTagCompound> data = new ArrayList<>(this.data);
            ListIterator<NBTTagCompound> dataIterator = data.listIterator();
            while (dataIterator.hasNext()) {
                NBTTagCompound compoundTag = dataIterator.next();
                LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag)).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
                if (function.apply(entity)) {
                    NBTTagCompound replacementTag = new NBTTagCompound();
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
        List<LivingEntity> removedEntries = new ArrayList<>(this.data.size());
        LivingEntity thisEntity = this.entity.get();
        if (thisEntity == null)
            return removedEntries;

        synchronized (this.data) {
            List<NBTTagCompound> data = new ArrayList<>(this.data);
            ListIterator<NBTTagCompound> dataIterator = data.listIterator();
            while (dataIterator.hasNext()) {
                NBTTagCompound compoundTag = dataIterator.next();
                LivingEntity entity = new NBTEntityDataEntry(this.rebuild(compoundTag)).createEntity(thisEntity.getLocation(), false, thisEntity.getType());
                if (function.apply(entity)) {
                    removedEntries.add(entity);
                    dataIterator.remove();
                } else {
                    NBTTagCompound replacementTag = new NBTTagCompound();
                    ((NMSHandlerImpl) NMSAdapter.getHandler()).saveEntityToTag(entity, replacementTag);
                    this.stripUnneeded(replacementTag);
                    this.stripAttributeUuids(replacementTag);
                    this.removeDuplicates(replacementTag);
                    dataIterator.set(replacementTag);
                }
            }

            this.data.clear();
            this.data.addAll(data);
            return removedEntries;
        }
    }

    private void removeDuplicates(NBTTagCompound compoundTag) {
        NBTTagCompound base = this.getBase();
        for (String key : new ArrayList<>(compoundTag.getKeys())) {
            NBTBase baseValue = base.get(key);
            NBTBase thisValue = compoundTag.get(key);
            if (baseValue != null && baseValue.equals(thisValue))
                compoundTag.remove(key);
        }
    }

    private NBTTagCompound rebuild(NBTTagCompound compoundTag) {
        NBTTagCompound merged = new NBTTagCompound();
        merged.a(this.getBase());
        merged.a(compoundTag);
        this.fillAttributeUuids(merged);
        return merged;
    }

    private void stripUnneeded(NBTTagCompound compoundTag) {
        NMSHandler.REMOVABLE_NBT_KEYS.forEach(compoundTag::remove);
        NBTTagCompound bukkitValues = compoundTag.getCompound("BukkitValues");
        bukkitValues.remove("rosestacker:stacked_entity_data");
    }

    private void stripAttributeUuids(NBTTagCompound compoundTag) {
        NBTTagList attributes = compoundTag.getList("Attributes", 10);
        for (int i = 0; i < attributes.size(); i++) {
            NBTTagCompound attribute = attributes.getCompound(i);
            attribute.remove("UUID");
            NBTTagList modifiers = attribute.getList("Modifiers", 10);
            for (int j = 0; j < modifiers.size(); j++) {
                NBTTagCompound modifier = modifiers.getCompound(j);
                if (modifier.getString("Name").equals("Random spawn bonus")) {
                    modifiers.remove(j);
                    j--;
                } else {
                    modifier.remove("UUID");
                }
            }
        }
    }

    private void fillAttributeUuids(NBTTagCompound compoundTag) {
        NBTTagList attributes = compoundTag.getList("Attributes", 10);
        for (int i = 0; i < attributes.size(); i++) {
            NBTTagCompound attribute = attributes.getCompound(i);
            attribute.a("UUID", UUID.randomUUID());
            NBTTagList modifiers = attribute.getList("Modifiers", 10);
            for (int j = 0; j < modifiers.size(); j++) {
                NBTTagCompound modifier = modifiers.getCompound(j);
                modifier.a("UUID", UUID.randomUUID());
            }
            if (modifiers.size() == 0)
                attribute.remove("Modifiers");
        }
    }

}
