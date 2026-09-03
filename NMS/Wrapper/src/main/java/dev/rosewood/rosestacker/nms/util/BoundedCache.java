package dev.rosewood.rosestacker.nms.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A small, thread-safe, least-recently-used cache.
 * <p>
 * Used to memoize the legacy-string to chat component conversions that stack nametags and holograms
 * perform. Display strings repeat heavily (every "5x Zombie" tag is the same string), so parsing them
 * once and sharing the immutable component is much cheaper than re-parsing per player per update.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class BoundedCache<K, V> {

    private final Map<K, V> map;

    public BoundedCache(int maxSize) {
        this.map = new LinkedHashMap<>(Math.min(maxSize, 256), 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return this.size() > maxSize;
            }
        };
    }

    /**
     * Gets the cached value for the key, computing and caching it if absent.
     * The mapping function may return null; null results are not cached.
     *
     * @param key the key
     * @param mappingFunction the function to compute a missing value
     * @return the cached or newly computed value
     */
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        synchronized (this.map) {
            V value = this.map.get(key);
            if (value != null)
                return value;
        }

        V value = mappingFunction.apply(key);
        if (value == null)
            return null;

        synchronized (this.map) {
            V existing = this.map.putIfAbsent(key, value);
            return existing != null ? existing : value;
        }
    }

    public void clear() {
        synchronized (this.map) {
            this.map.clear();
        }
    }

}
