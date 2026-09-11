package dev.rosewood.rosestacker.nms.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public final class ExtraUtils {

    private static final Map<NamespacedKey, EntityType> ENTITYTYPE_BY_KEY;
    static {
        ENTITYTYPE_BY_KEY = new HashMap<>();
        for (EntityType entityType : EntityType.values())
            if (entityType != EntityType.UNKNOWN)
                ENTITYTYPE_BY_KEY.put(entityType.getKey(), entityType);
    }

    public static EntityType getEntityTypeFromKey(NamespacedKey key) {
        return ENTITYTYPE_BY_KEY.get(key);
    }

    /**
     * Creates a random type 4 UUID without going through {@link UUID#randomUUID()}, which draws from a shared
     * SecureRandom whose nextBytes is synchronized and therefore serializes the world tick threads against the
     * main thread. Only for UUIDs that need to be unique, never for ones that need to be unguessable.
     *
     * @return a randomly generated type 4 UUID
     */
    public static UUID insecureRandomUuid() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificantBits = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L; // Version 4
        long leastSignificantBits = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // IETF variant
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

}
