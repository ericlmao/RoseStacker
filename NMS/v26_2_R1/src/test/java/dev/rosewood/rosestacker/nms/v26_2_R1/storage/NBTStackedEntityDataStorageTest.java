package dev.rosewood.rosestacker.nms.v26_2_R1.storage;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NBTStackedEntityDataStorageTest {

    @ParameterizedTest
    @ValueSource(strings = {"NoAI", "NoGravity"})
    void normalMemberDoesNotInheritDisabledPhysics(String flag) throws Exception {
        CompoundTag base = new CompoundTag();
        base.putBoolean(flag, true);
        NBTStackedEntityDataStorage target = storage(base);

        // Vanilla omits false flags entirely. Exercise the real add/deduplicate/load path.
        target.addAll(storage(new CompoundTag(), new CompoundTag()));
        target = reload(target);

        assertFalse(target.peek().get().getBooleanOr(flag, true));
        assertFalse(target.pop().get().getBooleanOr(flag, true));
        assertTrue(target.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NoAI", "NoGravity"})
    void deliberatelyDisabledMemberKeepsItsFlag(String flag) throws Exception {
        CompoundTag member = new CompoundTag();
        member.putBoolean(flag, true);
        NBTStackedEntityDataStorage target = storage(new CompoundTag());
        target.addAll(storage(new CompoundTag(), member));

        assertTrue(reload(target).pop().get().getBooleanOr(flag, false));
    }

    @Test
    void missingPdcDoesNotInheritTemplateAiMarker() throws Exception {
        NBTStackedEntityDataStorage target = storage(flaggedPdc());
        target.addAll(storage(new CompoundTag(), new CompoundTag()));

        assertTrue(reload(target).pop().get().getCompoundOrEmpty("BukkitValues").isEmpty());
    }

    @Test
    void explicitEmptyLegacyPdcOverridesTemplate() throws Exception {
        CompoundTag member = new CompoundTag();
        member.put("BukkitValues", new CompoundTag());

        // Existing entries already stored a complete compound when it differed from the base.
        NBTStackedEntityDataStorage target = storage(flaggedPdc(), member);
        assertTrue(reload(target).pop().get().getCompoundOrEmpty("BukkitValues").isEmpty());
    }

    @Test
    void memberPdcReplacesTemplateWithoutLosingItsOwnData() throws Exception {
        CompoundTag member = new CompoundTag();
        CompoundTag pdc = new CompoundTag();
        pdc.putString("other:owner", "member");
        pdc.putByteArray("rosestacker:stacked_entity_data", new byte[] {1, 2, 3});
        member.put("BukkitValues", pdc);
        NBTStackedEntityDataStorage target = storage(flaggedPdc());
        target.addAll(storage(new CompoundTag(), member));

        CompoundTag rebuilt = reload(target).pop().get().getCompoundOrEmpty("BukkitValues");
        assertEquals("member", rebuilt.getStringOr("other:owner", ""));
        assertFalse(rebuilt.contains("rosestacker:no_ai"));
        assertFalse(rebuilt.contains("rosestacker:stacked_entity_data"));
        assertFalse(rebuilt.contains("other:template"));
    }

    @Test
    void intentionallyFlaggedPdcIsPreserved() throws Exception {
        NBTStackedEntityDataStorage target = storage(new CompoundTag());
        target.addAll(storage(new CompoundTag(), flaggedPdc()));

        assertEquals(1, reload(target).pop().get().getCompoundOrEmpty("BukkitValues")
                .getIntOr("rosestacker:no_ai", 0));
    }

    @Test
    void identicalMembersAndClonesStillInheritIntendedTemplate() throws Exception {
        CompoundTag base = flaggedPdc();
        base.putBoolean("NoAI", true);
        base.putBoolean("NoGravity", true);
        NBTStackedEntityDataStorage target = storage(base);
        target.addAll(storage(new CompoundTag(), base.copy()));
        target.addClones(2);
        target = reload(target);

        assertEquals(3, target.size());
        for (var entry : target.pop(3)) {
            CompoundTag tag = ((NBTEntityDataEntry) entry).get();
            assertTrue(tag.getBooleanOr("NoAI", false));
            assertTrue(tag.getBooleanOr("NoGravity", false));
            assertEquals(1, tag.getCompoundOrEmpty("BukkitValues").getIntOr("rosestacker:no_ai", 0));
        }
    }

    @Test
    void transferBetweenOppositeTemplatesKeepsEachMembersPhysics() throws Exception {
        CompoundTag disabled = flaggedPdc();
        disabled.putBoolean("NoAI", true);
        disabled.putBoolean("NoGravity", true);
        NBTStackedEntityDataStorage source = storage(disabled);
        source.addAll(storage(new CompoundTag(), new CompoundTag()));
        source.addClones(1);
        NBTStackedEntityDataStorage target = storage(new CompoundTag());
        target.addAll(reload(source));
        target = reload(target);

        CompoundTag normal = target.pop().get();
        assertFalse(normal.getBooleanOr("NoAI", true));
        assertFalse(normal.getBooleanOr("NoGravity", true));
        assertTrue(normal.getCompoundOrEmpty("BukkitValues").isEmpty());
        CompoundTag flagged = target.pop().get();
        assertTrue(flagged.getBooleanOr("NoAI", false));
        assertTrue(flagged.getBooleanOr("NoGravity", false));
        assertEquals(1, flagged.getCompoundOrEmpty("BukkitValues").getIntOr("rosestacker:no_ai", 0));
    }

    @Test
    void rebuiltCompoundsAreIndependentOfStoredEntriesAndTemplate() throws Exception {
        CompoundTag member = new CompoundTag();
        CompoundTag pdc = new CompoundTag();
        pdc.putString("other:owner", "original");
        member.put("BukkitValues", pdc);
        NBTStackedEntityDataStorage target = storage(flaggedPdc(), member);

        target.peek().get().getCompoundOrEmpty("BukkitValues").putString("other:owner", "changed");
        assertEquals("original", target.peek().get().getCompoundOrEmpty("BukkitValues")
                .getStringOr("other:owner", ""));
        target.pop();
        target.addClones(1);
        assertEquals(1, target.pop().get().getCompoundOrEmpty("BukkitValues").getIntOr("rosestacker:no_ai", 0));
    }

    @Test
    void ambiguousLegacyFlagsAreNotSilentlyCleared() throws Exception {
        CompoundTag base = flaggedPdc();
        base.putBoolean("NoAI", true);
        base.putBoolean("NoGravity", true);
        // An old omitted field could mean intentional deduplication or lost false state.
        // Preserve it rather than guessing and enabling deliberately disabled mobs.
        NBTStackedEntityDataStorage target = reload(storage(base, new CompoundTag()));
        CompoundTag tag = target.pop().get();
        assertTrue(tag.getBooleanOr("NoAI", false));
        assertTrue(tag.getBooleanOr("NoGravity", false));
        assertEquals(1, tag.getCompoundOrEmpty("BukkitValues").getIntOr("rosestacker:no_ai", 0));
    }

    private static CompoundTag flaggedPdc() {
        CompoundTag tag = new CompoundTag();
        CompoundTag pdc = new CompoundTag();
        pdc.putInt("rosestacker:no_ai", 1);
        pdc.putString("other:template", "template-only");
        tag.put("BukkitValues", pdc);
        return tag;
    }

    private static NBTStackedEntityDataStorage reload(NBTStackedEntityDataStorage storage) {
        return new NBTStackedEntityDataStorage(null, storage.serialize(Integer.MAX_VALUE), Set.of());
    }

    private static NBTStackedEntityDataStorage storage(CompoundTag base, CompoundTag... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            NbtIo.write(base, output);
            output.writeInt(entries.length);
            for (CompoundTag entry : entries)
                NbtIo.write(entry, output);
        }
        return new NBTStackedEntityDataStorage(null, bytes.toByteArray(), Set.of());
    }
}
