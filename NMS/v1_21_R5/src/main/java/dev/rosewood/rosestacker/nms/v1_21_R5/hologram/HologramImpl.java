package dev.rosewood.rosestacker.nms.v1_21_R5.hologram;

import dev.rosewood.rosestacker.nms.hologram.Hologram;
import dev.rosewood.rosestacker.nms.hologram.HologramLine;
import dev.rosewood.rosestacker.nms.util.BoundedCache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_21_R5.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_21_R5.util.CraftChatMessage;
import org.bukkit.entity.Player;

public class HologramImpl extends Hologram {

    // The client only needs a hologram line's UUID to be stable for its entity id, so it is derived from the
    // id rather than drawn fresh per line per watcher. UUID.randomUUID() goes through a shared SecureRandom
    // with a synchronized nextBytes, and create() runs every time any player starts watching any hologram.
    private static final long HOLOGRAM_UUID_HIGH_BITS = 0x526F736553746163L; // "RoseStac"

    private static final BoundedCache<String, Component> TEXT_COMPONENT_CACHE = new BoundedCache<>(2048);

    // Hoisted out of the per-line update path, this accessor was allocated on every hologram update
    private static final EntityDataAccessor<Component> DATA_TEXT = EntityDataSerializers.COMPONENT.createAccessor(23); // Text
    private static final List<SynchedEntityData.DataValue<?>> DATA_VALUES = List.of(
            SynchedEntityData.DataValue.create(EntityDataSerializers.BYTE.createAccessor(15), (byte) 3), // Billboard Constraint (Center)
            SynchedEntityData.DataValue.create(EntityDataSerializers.FLOAT.createAccessor(17), 1.0F)     // Visibility, always visible since these are hidden behind walls
    );

    public HologramImpl(List<String> text, Location location, Supplier<Integer> entityIdSupplier) {
        super(text, location, entityIdSupplier);
    }

    @Override
    protected void create(Player player) {
        for (HologramLine line : this.hologramLines) {
            ClientboundAddEntityPacket packet = new ClientboundAddEntityPacket(
                    line.getEntityId(),
                    new UUID(HOLOGRAM_UUID_HIGH_BITS, line.getEntityId()),
                    line.getLocation().getX(),
                    line.getLocation().getY() + 0.75,
                    line.getLocation().getZ(),
                    90,
                    0,
                    EntityType.TEXT_DISPLAY,
                    1,
                    Vec3.ZERO,
                    0
            );

            ((CraftPlayer) player).getHandle().connection.send(packet);
        }
    }

    @Override
    protected void update(Collection<Player> players, boolean force) {
        for (HologramLine line : this.hologramLines) {
            if (!force && !line.checkDirty())
                continue;

            List<SynchedEntityData.DataValue<?>> dataValues = new ArrayList<>(DATA_VALUES);
            Component chatMessage = TEXT_COMPONENT_CACHE.get(line.getText(), CraftChatMessage::fromStringOrNull);
            dataValues.add(SynchedEntityData.DataValue.create(DATA_TEXT, chatMessage));

            // Every watcher of a line gets byte-identical contents and the packet is immutable, so one
            // instance per line is enough; this used to allocate a packet per line per player per update
            ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(line.getEntityId(), dataValues);
            for (Player player : players) {
                Boolean visible = this.watchers.get(player);
                if (visible == null)
                    return;

                ((CraftPlayer) player).getHandle().connection.send(packet);
            }
        }
    }

    @Override
    protected void delete(Player player) {
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(this.hologramLines.stream().mapToInt(HologramLine::getEntityId).toArray());

        ((CraftPlayer) player).getHandle().connection.send(packet);
    }

}
